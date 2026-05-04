#!/usr/bin/env python3
"""
Google Takeout EXIF Restoration Tool — Web UI
Run via Start.bat, or manually:  python web_app.py
Then open:  http://127.0.0.1:5000
"""

import json
import os
import platform
import queue
import socket
import subprocess
import threading
import time
import webbrowser
from pathlib import Path
from typing import Optional

from flask import Flask, Response, jsonify, render_template, request, stream_with_context

from core import (
    DupMatch, Processor, Stats,
    _SYS, _check_exiftool, _fmt_size, find_duplicates,
)

app = Flask(__name__)

# ── Global state ──────────────────────────────────────────────────────────────

_lock           = threading.Lock()
_running        = False
_processor: Optional[Processor] = None
_event_q: queue.Queue = queue.Queue(maxsize=2000)

# Used to pause the worker while the user reviews duplicates in the browser
_decision_event = threading.Event()
_decision_value: Optional[str] = None   # 'skip_dupes' | 'keep_all' | None


def _push(event: dict) -> None:
    try:
        _event_q.put_nowait(event)
    except queue.Full:
        pass


# ── Routes ────────────────────────────────────────────────────────────────────

@app.route('/')
def index():
    return render_template('index.html')


@app.route('/api/exiftool')
def api_exiftool():
    ver = _check_exiftool()
    return jsonify({'ok': bool(ver), 'version': ver or ''})


@app.route('/api/browse', methods=['POST'])
def api_browse():
    path = _pick_folder()
    return jsonify({'path': path})


@app.route('/api/start', methods=['POST'])
def api_start():
    global _running, _processor, _decision_value
    data = request.get_json(force=True)

    src           = (data.get('src') or '').strip()
    dst           = (data.get('dst') or '').strip()
    copy_unmatched = bool(data.get('copy_unmatched', True))
    output_mode   = data.get('output_mode', 'date')
    check_dupes   = bool(data.get('check_dupes', False))
    skip_raw      = data.get('skip_files', [])

    if not src or not Path(src).is_dir():
        return jsonify({'error': 'Source folder not found.'}), 400
    if not dst:
        return jsonify({'error': 'No output folder specified.'}), 400
    if Path(src).resolve() == Path(dst).resolve():
        return jsonify({'error': 'Source and output must be different.'}), 400

    with _lock:
        if _running:
            return jsonify({'error': 'Already running.'}), 409
        _running = True

    skip_files = frozenset(Path(p) for p in skip_raw)
    _decision_event.clear()
    _decision_value = None

    def worker():
        global _running, _processor, _decision_value
        try:
            eff_skip = skip_files

            if check_dupes and not skip_raw:
                _push({'type': 'scan_start'})
                matches = find_duplicates(
                    Path(src), Path(src),
                    on_progress=lambda cur, tot: _push(
                        {'type': 'scan_progress', 'current': cur, 'total': tot}
                    ),
                )
                if matches:
                    _push({
                        'type': 'duplicates_found',
                        'count': len(matches),
                        'matches': [_dup_dict(m, Path(src)) for m in matches],
                    })
                    _decision_event.wait(timeout=600)   # up to 10 min to review
                    if _decision_value == 'skip_dupes':
                        eff_skip = frozenset(m.file_b for m in matches)
                        _push({'type': 'log', 'level': 'info',
                               'text': f'Skipping {len(eff_skip)} duplicate(s).'})
                    else:
                        _push({'type': 'log', 'level': 'info',
                               'text': 'Keeping all copies.'})
                else:
                    _push({'type': 'log', 'level': 'info',
                           'text': 'No duplicates found — proceeding.'})

            proc = Processor(
                src=src, dst=dst,
                copy_unmatched=copy_unmatched,
                output_mode=output_mode,
                on_log=lambda lvl, txt: _push({'type': 'log', 'level': lvl, 'text': txt}),
                on_progress=lambda cur, tot, f, fps: _push(
                    {'type': 'progress', 'current': cur, 'total': tot,
                     'file': f, 'fps': round(fps, 1)}
                ),
                on_stats=lambda s: _push(
                    {'type': 'stats', 'total': s.total, 'processed': s.processed,
                     'no_json': s.no_json, 'errors': s.errors, 'skipped': s.skipped}
                ),
                on_done=lambda ok: _push({'type': 'done', 'ok': ok}),
                skip_files=eff_skip,
            )
            _processor = proc
            proc.run()
        except Exception as exc:
            _push({'type': 'log', 'level': 'error', 'text': f'Unexpected error: {exc}'})
            _push({'type': 'done', 'ok': False})
        finally:
            with _lock:
                _running = False
            _processor = None

    threading.Thread(target=worker, daemon=True).start()
    return jsonify({'ok': True})


@app.route('/api/decide', methods=['POST'])
def api_decide():
    global _decision_value
    _decision_value = request.get_json(force=True).get('action')
    _decision_event.set()
    return jsonify({'ok': True})


@app.route('/api/stop', methods=['POST'])
def api_stop():
    if _processor:
        _processor.stop()
    return jsonify({'ok': True})


@app.route('/api/status')
def api_status():
    return jsonify({'running': _running})


@app.route('/api/open-output', methods=['POST'])
def api_open_output():
    dst = (request.get_json(force=True).get('path') or '').strip()
    if dst and Path(dst).is_dir():
        if _SYS == 'Windows':
            os.startfile(dst)           # type: ignore[attr-defined]
        elif _SYS == 'Darwin':
            subprocess.Popen(['open', dst])
        else:
            subprocess.Popen(['xdg-open', dst])
    return jsonify({'ok': True})


@app.route('/api/stream')
def api_stream():
    """Server-Sent Events endpoint — streams log, progress, and stat events."""
    def generate():
        # Drain any stale events from a previous run before the client connected
        while not _event_q.empty():
            try:
                _event_q.get_nowait()
            except queue.Empty:
                break

        while True:
            try:
                event = _event_q.get(timeout=25)
                yield f'data: {json.dumps(event)}\n\n'
            except queue.Empty:
                yield ': keepalive\n\n'   # prevent proxy timeouts

    return Response(
        stream_with_context(generate()),
        mimetype='text/event-stream',
        headers={
            'Cache-Control':    'no-cache, no-transform',
            'X-Accel-Buffering': 'no',
            'Connection':       'keep-alive',
        },
    )


# ── Helpers ───────────────────────────────────────────────────────────────────

def _dup_dict(m: DupMatch, root: Path) -> dict:
    def rel(p: Path) -> str:
        try:
            return str(p.relative_to(root))
        except ValueError:
            return p.name

    return {
        'a':          rel(m.file_a),
        'b':          rel(m.file_b),
        'size':       _fmt_size(m.size),
        'confidence': m.confidence,
    }


def _pick_folder() -> str:
    """Open the OS native folder-picker dialog and return the chosen path."""
    if _SYS == 'Windows':
        ps = (
            'Add-Type -AssemblyName System.Windows.Forms;'
            '$d = New-Object System.Windows.Forms.FolderBrowserDialog;'
            '$d.Description = "Select folder";'
            '$d.ShowNewFolderButton = $true;'
            '[void]$d.ShowDialog();'
            'Write-Output $d.SelectedPath'
        )
        try:
            r = subprocess.run(
                ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', ps],
                capture_output=True, text=True, timeout=120,
            )
            return r.stdout.strip()
        except Exception:
            return ''
    else:
        for cmd in [
            ['zenity', '--file-selection', '--directory', '--title=Select folder'],
            ['kdialog', '--getexistingdirectory'],
        ]:
            try:
                r = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
                if r.returncode == 0:
                    return r.stdout.strip()
            except FileNotFoundError:
                pass
        return ''


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == '__main__':
    # Pick port 5000, or the next free one
    port = 5000
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        if s.connect_ex(('127.0.0.1', port)) == 0:
            with socket.socket() as s2:
                s2.bind(('127.0.0.1', 0))
                port = s2.getsockname()[1]

    url = f'http://127.0.0.1:{port}'
    print(f'\n  Google Takeout EXIF Restoration Tool')
    print(f'  ─────────────────────────────────────')
    print(f'  Web UI → {url}')
    print(f'  Press Ctrl+C to quit\n')

    threading.Thread(
        target=lambda: (time.sleep(1.3), webbrowser.open(url)),
        daemon=True,
    ).start()

    app.run(host='127.0.0.1', port=port, debug=False, threaded=True)
