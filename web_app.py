#!/usr/bin/env python3
"""
Photos Backup Fix — browser UI.

Run via Start.bat, or manually:   python web_app.py [--port N] [--no-browser]
Then open the URL it prints (http://127.0.0.1:5000 by default).

Security: the server only listens on 127.0.0.1, rejects requests whose Host
header isn't localhost (DNS-rebinding), and every /api call must carry the
random per-launch token embedded in the page (so other websites open in
the same browser can't start jobs or rename folders).
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import queue
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from dataclasses import asdict
from pathlib import Path
from typing import Optional

from flask import Flask, Response, abort, jsonify, render_template, request, send_file, stream_with_context

from core import (
    _SYS, OUTPUT_MODES, DriveFixer, DuplicateFinder, FolderRenamer, Job,
    ProcessOptions, Processor, _check_exiftool, _fmt_size, _norm_key, collect_dated_files,
    find_duplicate_groups, find_exiftool, iter_media, plan_folder_renames,
)

app = Flask(__name__)
TOKEN = secrets.token_urlsafe(24)
_allowed_hosts: set = set()


def _echo(lvl: str, txt: str) -> None:
    if lvl != 'file':
        prefix = {'info': 'INFO ', 'ok': 'OK   ', 'warn': 'WARN ', 'error': 'ERROR'}.get(lvl, lvl.upper()[:5])
        try:
            print(f'  [{prefix}] {txt}', flush=True)
        except UnicodeEncodeError:       # legacy Windows console code pages
            print(f'  [{prefix}] {txt.encode("ascii", "replace").decode()}', flush=True)


# ── Event hub (SSE fan-out with replay) ───────────────────────────────────────

class Hub:
    """Broadcasts events to every open browser tab. New/reconnecting tabs get
    the recent log and the latest progress/stats replayed, so a page refresh
    mid-run loses nothing."""

    def __init__(self):
        self._subs: list = []
        self._lock = threading.Lock()
        self.history: collections.deque = collections.deque(maxlen=3000)
        self.latest: dict = {}

    def publish(self, ev: dict) -> None:
        with self._lock:
            t = ev.get('type')
            if t == 'log':
                self.history.append(ev)
            elif t in ('progress', 'stats', 'phase'):
                self.latest[t] = ev
            elif t == 'reset':
                self.history.clear()
                self.latest.clear()
            subs = list(self._subs)
        for q in subs:
            try:
                q.put_nowait(ev)
            except queue.Full:
                pass               # a stalled tab drops events rather than blocking work

    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=5000)
        with self._lock:
            replay = list(self.history) + list(self.latest.values())
            self._subs.append(q)
        for ev in replay:
            q.put_nowait(ev)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._lock:
            if q in self._subs:
                self._subs.remove(q)


hub = Hub()


def _log(lvl: str, txt: str) -> None:
    hub.publish({'type': 'log', 'level': lvl, 'text': txt})
    _echo(lvl, txt)


# ── Job runner ────────────────────────────────────────────────────────────────

class Runner:
    def __init__(self):
        self.lock = threading.Lock()
        self.job: Optional[Job] = None
        self.kind = ''
        self.running = False
        self.started = 0.0
        self.last: dict = {}                 # kind, ok, report, output of the last finished job
        self.history: list = []              # recent runs this session (newest first)
        self.decision = threading.Event()
        self.decision_value: Optional[str] = None
        self.cancelled = threading.Event()
        self.awaiting_decision = False

    def status(self) -> dict:
        job = self.job
        return {
            'running': self.running,
            'kind': self.kind,
            'paused': bool(job and job.paused),
            'awaiting_decision': self.awaiting_decision,
            'stats': asdict(job.stats) if job else None,
            'last': self.last,
            'history': self.history[:10],
            'started': self.started if self.running else 0,
            'dry_run': bool(job and _is_dry(job)),
        }


def _is_dry(job) -> bool:
    opts = getattr(job, 'opts', None)
    return bool(getattr(opts, 'dry_run', False) or getattr(job, 'dry_run', False))


runner = Runner()


def _callbacks() -> dict:
    return dict(
        on_log=_log,
        on_progress=lambda cur, tot, f, fps: hub.publish(
            {'type': 'progress', 'current': cur, 'total': tot, 'file': f, 'fps': round(fps, 1)}),
        on_stats=lambda s: hub.publish({'type': 'stats', 'stats': asdict(s)}),
        on_event=hub.publish,
    )


def _bool(d: dict, k: str, default: bool) -> bool:
    v = d.get(k, default)
    return v if isinstance(v, bool) else str(v).lower() in ('1', 'true', 'yes', 'on')


def _valid_day(v: str) -> bool:
    try:
        time.strptime(v, '%Y-%m-%d')
        return True
    except ValueError:
        return False


def _workers(d: dict) -> int:
    try:
        return max(0, min(16, int(d.get('workers') or 0)))
    except (TypeError, ValueError):
        return 0


def _build_job(tool: str, d: dict):
    """Validate the request and construct the job. Returns (job, error, extra)."""
    cb = _callbacks()
    if tool in ('takeout', 'filename'):
        src, dst = (d.get('src') or '').strip(), (d.get('dst') or '').strip()
        if not src or not Path(src).is_dir():
            return None, 'Source folder not found.', {}
        if not dst:
            return None, 'No output folder specified.', {}
        if _norm_key(Path(src).resolve()) == _norm_key(Path(dst).resolve()):
            return None, 'Source and output must be different folders.', {}
        mode = d.get('output_mode', 'date')
        opts = ProcessOptions(
            output_mode=mode if mode in OUTPUT_MODES else 'date',
            use_sidecars=(tool == 'takeout'),
            keep_existing_dates=_bool(d, 'keep_existing', True),
            use_embedded_dates=True,
            copy_undated=_bool(d, 'copy_undated', True),
            write_gps=_bool(d, 'write_gps', True),
            write_extras=_bool(d, 'write_extras', True),
            set_file_times=_bool(d, 'set_file_times', True),
            filename_tz='utc' if d.get('filename_tz') == 'utc' else 'local',
            dry_run=_bool(d, 'dry_run', False),
            kinds=d.get('kinds') if d.get('kinds') in ('photos', 'videos') else 'all',
            date_from=str(d.get('date_from') or ''),
            date_to=str(d.get('date_to') or ''),
            rename_to_date=_bool(d, 'rename_to_date', False),
        )
        for k in ('date_from', 'date_to'):
            v = getattr(opts, k)
            if v and not _valid_day(v):
                return None, f'Invalid date: {v} (use YYYY-MM-DD).', {}
        if opts.date_from and opts.date_to and opts.date_from > opts.date_to:
            return None, 'The start date is after the end date.', {}
        job = Processor(src, dst, options=opts, workers=_workers(d), **cb)
        return job, '', {'output': dst, 'check_dupes': tool == 'takeout' and _bool(d, 'check_dupes', False)}
    if tool in ('fixdrive', 'rename'):
        root = (d.get('root') or '').strip()
        if not root or not Path(root).is_dir():
            return None, 'Drive folder not found.', {}
        if tool == 'fixdrive':
            job = DriveFixer(root, dry_run=_bool(d, 'dry_run', False),
                             set_file_times=_bool(d, 'set_file_times', True),
                             filename_tz='utc' if d.get('filename_tz') == 'utc' else 'local',
                             fix_mismatched=_bool(d, 'fix_mismatched', False),
                             workers=_workers(d), **cb)
        else:
            job = FolderRenamer(root, dry_run=_bool(d, 'dry_run', False), **cb)
        return job, '', {'output': root}
    if tool == 'dupes':
        roots = [r.strip() for r in (d.get('roots') or []) if isinstance(r, str) and r.strip()]
        if not roots:
            return None, 'Choose a folder to scan.', {}
        for r in roots:
            if not Path(r).is_dir():
                return None, f'Folder not found: {r}', {}
        return DuplicateFinder(roots, move=_bool(d, 'move', False), **cb), '', {'output': roots[0]}
    return None, f'Unknown tool: {tool}', {}


def _dup_review(job: Processor) -> Optional[frozenset]:
    """Pre-scan for duplicates and wait for the user's choice.
    Returns files to skip, or None if the run was cancelled."""
    hub.publish({'type': 'scan_start'})
    _log('info', f'Scanning for duplicates in {job.src} …')
    groups = find_duplicate_groups(
        [job.src], stop=runner.cancelled,
        on_progress=lambda c, t: hub.publish({'type': 'scan_progress', 'current': c, 'total': t}))
    if runner.cancelled.is_set():
        hub.publish({'type': 'scan_done'})
        return None
    if not groups:
        hub.publish({'type': 'scan_done'})
        _log('info', 'No duplicates found — proceeding.')
        return frozenset()

    pairs = []
    for g in groups:
        size = _fmt_size(os.path.getsize(g[0]))
        for dup in g[1:]:
            pairs.append({'a': _rel(g[0], job.src), 'b': _rel(dup, job.src), 'size': size})
    _log('info', f'Found {len(pairs):,} duplicate file(s) in {len(groups):,} group(s).')
    runner.decision.clear()
    runner.decision_value = None
    runner.awaiting_decision = True
    hub.publish({'type': 'duplicates_found', 'count': len(pairs), 'groups': len(groups),
                 'matches': pairs[:2000]})
    try:
        while not runner.decision.wait(0.5):
            if runner.cancelled.is_set():
                return None
    finally:
        runner.awaiting_decision = False
        hub.publish({'type': 'scan_done'})
    if runner.decision_value == 'skip_dupes':
        skip = frozenset(str(dup) for g in groups for dup in g[1:])
        _log('info', f'Skipping {len(skip):,} duplicate copies (keeping one of each).')
        return skip
    if runner.decision_value == 'keep_all':
        _log('info', 'Keeping all copies.')
        return frozenset()
    return None


def _rel(p: Path, root: Path) -> str:
    try:
        return str(p.relative_to(root))
    except ValueError:
        return str(p)


def _start(tool: str, d: dict):
    job, err, extra = _build_job(tool, d)
    params = {k: v for k, v in d.items() if k != 'tool'}
    if err:
        return jsonify({'error': err}), 400
    with runner.lock:
        if runner.running:
            return jsonify({'error': 'Another task is already running.'}), 409
        runner.running = True
        runner.kind = tool
        runner.job = job
        runner.started = time.time()
        runner.cancelled.clear()

    hub.publish({'type': 'reset'})
    hub.publish({'type': 'started', 'kind': tool, 'dry_run': _is_dry(job)})
    print(f'\n  [START] {tool}', flush=True)

    def worker():
        ok = False
        try:
            if extra.get('check_dupes'):
                skip = _dup_review(job)
                if skip is None:
                    _log('warn', 'Cancelled before processing started.')
                    return
                job.skip_files = frozenset(_norm_key(Path(p)) for p in skip)
            if runner.cancelled.is_set():
                _log('warn', 'Cancelled before processing started.')
                return
            ok = job.run()
        except Exception as exc:          # pragma: no cover — job.run() already guards
            _log('error', f'Unexpected error: {exc}')
        finally:
            report = str(job.report_path) if job.report_path else ''
            runner.last = {'kind': tool, 'ok': ok, 'report': report,
                           'output': extra.get('output', ''), 'stopped': job.stopped or runner.cancelled.is_set(),
                           'dry_run': _is_dry(job), 'stats': asdict(job.stats),
                           'started': runner.started, 'finished': time.time(), 'params': params}
            runner.history.insert(0, runner.last)
            del runner.history[20:]
            with runner.lock:
                runner.running = False
            hub.publish({'type': 'done', **runner.last})

    threading.Thread(target=worker, daemon=True, name=f'job-{tool}').start()
    return jsonify({'ok': True})


# ── Security ──────────────────────────────────────────────────────────────────

@app.before_request
def _guard():
    host = (request.host or '').lower()
    if _allowed_hosts and host not in _allowed_hosts:
        abort(403)
    if request.path.startswith('/api/'):
        tok = request.headers.get('X-Token') or request.args.get('t', '')
        if not secrets.compare_digest(tok, TOKEN):
            abort(403)
        if request.method == 'POST' and not request.is_json:
            abort(415)


@app.after_request
def _headers(resp):
    resp.headers['X-Content-Type-Options'] = 'nosniff'
    resp.headers['X-Frame-Options'] = 'DENY'
    resp.headers['Referrer-Policy'] = 'no-referrer'
    return resp


# ── Routes ────────────────────────────────────────────────────────────────────

@app.route('/')
def index():
    return render_template('index.html', token=TOKEN)


@app.route('/api/exiftool')
def api_exiftool():
    ver = _check_exiftool()
    return jsonify({'ok': bool(ver), 'version': ver or '', 'path': find_exiftool() or '',
                    'cpus': os.cpu_count() or 1, 'workers': Job().workers,
                    'python': sys.version.split()[0], 'platform': _SYS})


@app.route('/api/browse', methods=['POST'])
def api_browse():
    title = str((request.get_json(silent=True) or {}).get('title') or 'Select folder')[:80]
    return jsonify({'path': _pick_folder(title)})


@app.route('/api/run', methods=['POST'])
def api_run():
    d = request.get_json(silent=True) or {}
    return _start(str(d.get('tool') or ''), d)


@app.route('/api/start', methods=['POST'])
def api_start():
    """Back-compat alias for the original Takeout start call."""
    d = request.get_json(silent=True) or {}
    return _start('takeout', d)


@app.route('/api/preflight', methods=['POST'])
def api_preflight():
    """Quick look at a folder: media count, size, free space at output."""
    d = request.get_json(silent=True) or {}
    src = (d.get('src') or '').strip()
    out: dict = {}
    if src and Path(src).is_dir():
        files = iter_media(Path(src))
        size = 0
        for p in files:
            try:
                size += p.stat().st_size
            except OSError:
                pass
        sidecars = 0
        for _dp, _dn, fns in os.walk(src):
            sidecars += sum(1 for f in fns if f.lower().endswith('.json'))
        out.update(count=len(files), size=size, size_h=_fmt_size(size), sidecars=sidecars)
    dst = (d.get('dst') or '').strip()
    if dst:
        out['exists'] = Path(dst).is_dir()
        probe = Path(dst)
        while not probe.exists() and probe.parent != probe:
            probe = probe.parent
        try:
            free = shutil.disk_usage(str(probe)).free
            out.update(free=free, free_h=_fmt_size(free))
        except OSError:
            pass
    return jsonify(out)


@app.route('/api/inspect', methods=['POST'])
def api_inspect():
    """Look at a backup drive: dated folders found, old-style folders to rename."""
    root = Path(((request.get_json(silent=True) or {}).get('root') or '').strip())
    if not str(root) or not root.is_dir():
        return jsonify({'ok': False, 'error': 'Folder not found.'})
    try:
        items, notes = collect_dated_files(root)
        renames = plan_folder_renames(root)
    except OSError as e:
        return jsonify({'ok': False, 'error': str(e)})
    years = sorted({d.year for _, d in items})
    return jsonify({'ok': True, 'files': len(items), 'years': years,
                    'renames': len(renames), 'skipped_folders': len(notes),
                    'examples': [f'{p.relative_to(root)} → {n}' for p, n in renames[:3]]})


@app.route('/api/decide', methods=['POST'])
def api_decide():
    action = (request.get_json(silent=True) or {}).get('action')
    if action not in ('skip_dupes', 'keep_all'):
        runner.cancelled.set()
        action = None
    runner.decision_value = action
    runner.decision.set()
    return jsonify({'ok': True})


@app.route('/api/stop', methods=['POST'])
def api_stop():
    runner.cancelled.set()
    runner.decision.set()
    if runner.job:
        runner.job.stop()
    return jsonify({'ok': True})


@app.route('/api/pause', methods=['POST'])
def api_pause():
    if runner.job and runner.running:
        runner.job.pause()
        hub.publish({'type': 'paused', 'paused': True})
        _log('warn', 'Paused — files in progress will finish first.')
    return jsonify({'ok': True})


@app.route('/api/resume', methods=['POST'])
def api_resume():
    if runner.job:
        runner.job.resume()
        hub.publish({'type': 'paused', 'paused': False})
        _log('info', 'Resumed.')
    return jsonify({'ok': True})


@app.route('/api/status')
def api_status():
    return jsonify(runner.status())


@app.route('/api/records')
def api_records():
    """Paged file records of the current/last job, optionally by status."""
    job = runner.job
    if not job:
        return jsonify({'total': 0, 'all': 0, 'records': []})
    wanted = {s for s in (request.args.get('status') or '').split(',') if s}
    q = (request.args.get('q') or '').lower()
    try:
        offset = max(0, int(request.args.get('offset', 0)))
        limit = max(1, min(2000, int(request.args.get('limit', 500))))
    except ValueError:
        offset, limit = 0, 500
    recs = list(job.records)
    sel = [r for r in recs if (not wanted or r.status in wanted)
           and (not q or q in r.file.lower() or q in r.dest.lower())]
    return jsonify({'total': len(sel), 'all': len(recs),
                    'records': [r.as_dict() for r in sel[offset:offset + limit]]})


@app.route('/api/report')
def api_report():
    rep = runner.last.get('report') or (str(runner.job.report_path) if runner.job and runner.job.report_path else '')
    if not rep:
        abort(404)
    path = Path(rep)
    if request.args.get('fmt') == 'csv':
        path = path.with_suffix('.csv')
    if not path.is_file():
        abort(404)
    return send_file(str(path), as_attachment=True, download_name=path.name)


@app.route('/api/open', methods=['POST'])
def api_open():
    d = request.get_json(silent=True) or {}
    target = (d.get('path') or '').strip()
    if d.get('what') == 'report' and runner.last.get('report'):
        target = str(Path(runner.last['report']).parent)
    if not target or not Path(target).is_dir():
        return jsonify({'error': 'Folder not found.'}), 404
    try:
        if _SYS == 'Windows':
            os.startfile(target)           # type: ignore[attr-defined]
        elif _SYS == 'Darwin':
            subprocess.Popen(['open', target])
        else:
            subprocess.Popen(['xdg-open', target])
    except OSError as e:
        return jsonify({'error': str(e)}), 500
    return jsonify({'ok': True})


@app.route('/api/open-output', methods=['POST'])
def api_open_output():
    """Back-compat alias."""
    return api_open()


@app.route('/api/stream')
def api_stream():
    """Server-Sent Events: log, progress, stats, scan and done events."""
    q = hub.subscribe()

    def generate():
        try:
            yield f'data: {json.dumps({"type": "hello", **runner.status()})}\n\n'
            while True:
                try:
                    ev = q.get(timeout=20)
                    yield f'data: {json.dumps(ev)}\n\n'
                except queue.Empty:
                    yield ': keepalive\n\n'
        finally:
            hub.unsubscribe(q)

    return Response(stream_with_context(generate()), mimetype='text/event-stream',
                    headers={'Cache-Control': 'no-cache, no-transform',
                             'X-Accel-Buffering': 'no', 'Connection': 'keep-alive'})


# ── Folder picker ─────────────────────────────────────────────────────────────

def _pick_folder(title: str = 'Select folder') -> str:
    """Open the OS native folder-picker dialog and return the chosen path."""
    # tkinter first — ships with Python, works everywhere, appears on top.
    script = ('import sys, tkinter, tkinter.filedialog;'
              'root = tkinter.Tk(); root.withdraw();'
              'root.wm_attributes("-topmost", 1);'
              'p = tkinter.filedialog.askdirectory(title=sys.argv[1], mustexist=False);'
              'sys.stdout.write(p or "")')
    try:
        r = subprocess.run([sys.executable, '-c', script, title], capture_output=True,
                           text=True, encoding='utf-8', timeout=600)
        if r.returncode == 0:
            return os.path.normpath(r.stdout.strip()) if r.stdout.strip() else ''
    except (OSError, subprocess.TimeoutExpired):
        pass

    if _SYS == 'Windows':
        # -Sta is required for Windows Forms dialogs.
        ps = (
            'Add-Type -AssemblyName System.Windows.Forms;'
            '[System.Windows.Forms.Application]::EnableVisualStyles();'
            '$d = New-Object System.Windows.Forms.FolderBrowserDialog;'
            '$d.Description = $env:PHOTOFIX_TITLE;'
            '$d.ShowNewFolderButton = $true;'
            'if ($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {'
            '  [Console]::OutputEncoding = [Text.Encoding]::UTF8; Write-Output $d.SelectedPath }'
        )
        try:
            r = subprocess.run(['powershell', '-Sta', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                                '-Command', ps], capture_output=True, text=True, encoding='utf-8',
                               timeout=600, env={**os.environ, 'PHOTOFIX_TITLE': title})
            return r.stdout.strip()
        except (OSError, subprocess.TimeoutExpired):
            return ''

    for cmd in (['zenity', '--file-selection', '--directory', f'--title={title}'],
                ['kdialog', '--getexistingdirectory', '.', '--title', title]):
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
            if r.returncode == 0:
                return r.stdout.strip()
        except (OSError, subprocess.TimeoutExpired):
            pass
    return ''


# ── Entry point ───────────────────────────────────────────────────────────────

def _free_port(preferred: int) -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        if s.connect_ex(('127.0.0.1', preferred)) != 0:
            return preferred
    with socket.socket() as s2:
        s2.bind(('127.0.0.1', 0))
        return s2.getsockname()[1]


def main(argv=None) -> None:
    ap = argparse.ArgumentParser(description='Photos Backup Fix — web UI')
    ap.add_argument('--port', type=int, default=5000)
    ap.add_argument('--no-browser', action='store_true', help="don't open a browser tab")
    args = ap.parse_args(argv)

    port = _free_port(args.port)
    _allowed_hosts.update({f'127.0.0.1:{port}', f'localhost:{port}'})
    url = f'http://127.0.0.1:{port}'
    print('\n  Photos Backup Fix')
    print('  ─────────────────────────────────────')
    print(f'  Web UI → {url}')
    print('  Press Ctrl+C to quit\n')
    if not args.no_browser:
        threading.Thread(target=lambda: (time.sleep(1.3), webbrowser.open(url)), daemon=True).start()
    app.run(host='127.0.0.1', port=port, debug=False, threaded=True, use_reloader=False)


if __name__ == '__main__':
    main()
