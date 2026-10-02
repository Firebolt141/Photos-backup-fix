#!/usr/bin/env python3
"""
Photos Backup Fix — command-line interface (same engine as the web UI).

  python cli.py takeout   SRC DST [--dry-run] [--layout date|date_numeric|preserve|flat] ...
  python cli.py sort      SRC DST [--dry-run] ...          (sort by filename date)
  python cli.py fix-dates DRIVE   [--dry-run]              (stamp missing dates in place)
  python cli.py rename    DRIVE   [--dry-run]              (01/15 → January/January_15)
  python cli.py dupes     FOLDER [FOLDER2] [--move]        (find identical files)

Exit codes: 0 = success, 1 = could not run / stopped, 2 = finished with errors.
"""

from __future__ import annotations

import argparse
import signal
import sys
import time

from core import (OUTPUT_MODES, DriveFixer, DuplicateFinder, FolderRenamer, ProcessOptions,
                  Processor)


def _printer(verbose: bool):
    def log(level: str, text: str) -> None:
        if level == 'file' and not verbose:
            return
        stream = sys.stderr if level == 'error' else sys.stdout
        try:
            print(text, file=stream, flush=True)
        except UnicodeEncodeError:
            print(text.encode('ascii', 'replace').decode(), file=stream, flush=True)
    return log


def _progress():
    last = [0.0]

    def on_progress(cur, total, _name, fps):
        now = time.monotonic()
        if sys.stderr.isatty() and (now - last[0] > 1 or cur == total):
            last[0] = now
            eta = f'  ETA {int((total - cur) / fps)}s' if fps > 0 and total > cur else ''
            print(f'\r  {cur:,}/{total:,} ({cur * 100 // max(total, 1)}%) {fps:.1f} files/s{eta}   ',
                  end='' if cur < total else '\n', file=sys.stderr, flush=True)
    return on_progress


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog='cli.py', description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('-v', '--verbose', action='store_true', help='log every file')
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument('-v', '--verbose', action='store_true', default=argparse.SUPPRESS,
                        help='log every file')
    sub = ap.add_subparsers(dest='cmd', required=True)

    def copy_opts(p):
        p.add_argument('src')
        p.add_argument('dst')
        p.add_argument('--layout', choices=OUTPUT_MODES, default='date',
                       help='date = 2024/January/January_07 (default), date_numeric = 2024/01/07')
        p.add_argument('--overwrite-dates', action='store_true',
                       help='replace dates already embedded in files (default: keep them)')
        p.add_argument('--no-file-times', action='store_true', help="don't set file modified times")
        p.add_argument('--skip-undated', action='store_true', help="don't copy files with no date")
        p.add_argument('--filename-tz', choices=('local', 'utc'), default='local')
        p.add_argument('--workers', type=int, default=0, help='parallel ExifTool workers (0 = auto)')
        p.add_argument('--dry-run', action='store_true')

    p = sub.add_parser('takeout', parents=[common], help='process a Google Takeout export')
    copy_opts(p)
    p.add_argument('--no-gps', action='store_true')
    p.add_argument('--no-extras', action='store_true', help='skip caption/people/favourite tags')
    p = sub.add_parser('sort', parents=[common], help='sort any folder by the dates in filenames')
    copy_opts(p)
    p = sub.add_parser('fix-dates', parents=[common], help='write folder dates into undated files on a drive (in place)')
    p.add_argument('drive')
    p.add_argument('--no-file-times', action='store_true')
    p.add_argument('--filename-tz', choices=('local', 'utc'), default='local')
    p.add_argument('--workers', type=int, default=0)
    p.add_argument('--dry-run', action='store_true')
    p = sub.add_parser('rename', parents=[common], help='rename numeric month/day folders to January/January_07')
    p.add_argument('drive')
    p.add_argument('--dry-run', action='store_true')
    p = sub.add_parser('dupes', parents=[common], help='find byte-identical photos/videos')
    p.add_argument('folders', nargs='+')
    p.add_argument('--move', action='store_true', help='move extra copies to _duplicates/')

    a = ap.parse_args(argv)
    cb = dict(on_log=_printer(a.verbose), on_progress=_progress())

    if a.cmd in ('takeout', 'sort'):
        opts = ProcessOptions(
            output_mode=a.layout, use_sidecars=(a.cmd == 'takeout'),
            keep_existing_dates=not a.overwrite_dates, copy_undated=not a.skip_undated,
            write_gps=not getattr(a, 'no_gps', False), write_extras=not getattr(a, 'no_extras', False),
            set_file_times=not a.no_file_times, filename_tz=a.filename_tz, dry_run=a.dry_run)
        job = Processor(a.src, a.dst, options=opts, workers=a.workers, **cb)
    elif a.cmd == 'fix-dates':
        job = DriveFixer(a.drive, dry_run=a.dry_run, set_file_times=not a.no_file_times,
                         filename_tz=a.filename_tz, workers=a.workers, **cb)
    elif a.cmd == 'rename':
        job = FolderRenamer(a.drive, dry_run=a.dry_run, **cb)
    else:
        job = DuplicateFinder(a.folders, move=a.move, **cb)

    def on_sigint(_sig, _frm):
        print('\nStopping after files in progress…', file=sys.stderr, flush=True)
        job.stop()
    signal.signal(signal.SIGINT, on_sigint)

    ok = job.run()
    if not ok:
        return 1
    errors = getattr(job.stats, 'errors', 0) + getattr(job.stats, 'meta_failed', 0)
    return 2 if errors else 0


if __name__ == '__main__':
    sys.exit(main())
