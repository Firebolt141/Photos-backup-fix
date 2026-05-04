#!/usr/bin/env python3
"""
Core processing logic for the Google Takeout EXIF Restoration Tool.
No GUI dependencies — importable from both the tkinter and web front-ends.
"""

import re
import json
import shutil
import subprocess
import threading
import platform
import time
from hashlib import md5
from datetime import datetime, timezone
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional, List

_SYS = platform.system()

# ── Media extensions ──────────────────────────────────────────────────────────

IMAGE_EXTS = {
    '.jpg', '.jpeg', '.png', '.gif', '.bmp', '.tiff', '.tif',
    '.webp', '.heic', '.heif', '.raw', '.cr2', '.nef', '.arw',
    '.dng', '.orf', '.rw2', '.srw', '.pef', '.3fr', '.iiq', '.x3f',
}
VIDEO_EXTS = {
    '.mp4', '.mov', '.avi', '.m4v', '.mkv', '.wmv', '.3gp',
    '.mpg', '.mpeg', '.mts', '.m2ts', '.flv', '.webm', '.ts',
}
ALL_MEDIA = IMAGE_EXTS | VIDEO_EXTS


# ── Data classes ──────────────────────────────────────────────────────────────

@dataclass
class Stats:
    total: int = 0
    processed: int = 0
    no_json: int = 0
    skipped: int = 0
    errors: int = 0


@dataclass
class FileRecord:
    file:   str         # relative source path
    status: str         # 'fixed' | 'no_json_copied' | 'no_json_skipped' | 'error'
    dest:   str = ''    # relative output path (empty if not written)
    date:   str = ''    # date written (fixed files only)
    gps:    str = ''    # GPS coords (fixed files only)
    error:  str = ''    # error description (error files only)


@dataclass
class Meta:
    timestamp: Optional[int] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    altitude: Optional[float] = None
    description: str = ''


@dataclass
class DupMatch:
    file_a:     Path
    file_b:     Path
    size:       int
    confidence: str   # 'exact' | 'strong'


# ── Core helpers ──────────────────────────────────────────────────────────────

def find_json(media: Path) -> Optional[Path]:
    """Return the Google Takeout JSON sidecar for *media*, or None."""
    name = media.stem
    ext  = media.suffix

    candidates: List[Path] = [
        media.parent / f"{name}{ext}.json",
        media.parent / f"{name}.json",
    ]

    full = f"{name}{ext}"
    if len(full) > 46:
        candidates.append(media.parent / f"{full[:46]}.json")

    m = re.match(r'^(.+)\((\d+)\)$', name)
    if m:
        base, num = m.group(1), m.group(2)
        candidates += [
            media.parent / f"{base}{ext}({num}).json",
            media.parent / f"{base}({num}).json",
        ]

    if name.endswith('-edited'):
        orig = name[:-7]
        candidates += [
            media.parent / f"{orig}{ext}.json",
            media.parent / f"{orig}.json",
        ]

    candidates += [
        media.parent / f"{name}{ext}.supplemental-metadata.json",
        media.parent / f"{name}.supplemental-metadata.json",
    ]

    for p in candidates:
        if p.exists():
            return p
    return None


def parse_meta(json_path: Path) -> Meta:
    """Extract date/GPS/description from a Google Takeout JSON sidecar."""
    meta = Meta()
    try:
        data = json.loads(json_path.read_text(encoding='utf-8'))

        for key in ('photoTakenTime', 'creationTime'):
            t = data.get(key)
            if t and 'timestamp' in t:
                ts = int(t['timestamp'])
                if ts > 0:
                    meta.timestamp = ts
                break

        for key in ('geoDataExif', 'geoData'):
            geo = data.get(key)
            if geo:
                lat = float(geo.get('latitude', 0))
                lon = float(geo.get('longitude', 0))
                if lat != 0.0 or lon != 0.0:
                    meta.latitude  = lat
                    meta.longitude = lon
                    meta.altitude  = float(geo.get('altitude', 0))
                    break

        meta.description = data.get('description', '')

    except Exception:
        pass

    return meta


_DATE_IN_NAME = re.compile(r'(?<!\d)(\d{4})[_\-]?(\d{2})[_\-]?(\d{2})(?!\d)')


def _ts_from_filename(name: str) -> Optional[int]:
    m = _DATE_IN_NAME.search(name)
    if m:
        try:
            y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
            if 2000 <= y <= 2040 and 1 <= mo <= 12 and 1 <= d <= 31:
                return int(datetime(y, mo, d, 12, 0, 0,
                                    tzinfo=timezone.utc).timestamp())
        except ValueError:
            pass
    return None


def build_exiftool_args(target: Path, meta: Meta) -> List[str]:
    is_video = target.suffix.lower() in VIDEO_EXTS
    args = ['exiftool', '-overwrite_original', '-m', '-q']

    if _SYS == 'Windows':
        args += ['-charset', 'filename=UTF8']
    if is_video:
        args += ['-api', 'LargeFileSupport=1']

    if meta.timestamp and meta.timestamp > 0:
        dt  = datetime.fromtimestamp(meta.timestamp, tz=timezone.utc)
        s   = dt.strftime('%Y:%m:%d %H:%M:%S')
        s_z = f'{s}+00:00'

        if is_video:
            for tag in ('CreateDate', 'ModifyDate',
                        'TrackCreateDate', 'TrackModifyDate',
                        'MediaCreateDate', 'MediaModifyDate'):
                args.append(f'-{tag}={s}')
            args.append(f'-Keys:CreationDate={s_z}')
        else:
            for tag in ('DateTimeOriginal', 'CreateDate', 'ModifyDate'):
                args.append(f'-{tag}={s}')
            args += [
                '-OffsetTimeOriginal=+00:00',
                '-OffsetTime=+00:00',
                '-OffsetTimeDigitized=+00:00',
            ]

    if meta.latitude is not None and meta.longitude is not None:
        if is_video:
            args += [
                f'-GPSLatitude={meta.latitude}',
                f'-GPSLongitude={meta.longitude}',
            ]
        else:
            lat_ref = 'N' if meta.latitude  >= 0 else 'S'
            lon_ref = 'E' if meta.longitude >= 0 else 'W'
            args += [
                f'-GPSLatitude={abs(meta.latitude)}',
                f'-GPSLatitudeRef={lat_ref}',
                f'-GPSLongitude={abs(meta.longitude)}',
                f'-GPSLongitudeRef={lon_ref}',
            ]

        if meta.altitude is not None:
            alt_ref = '0' if meta.altitude >= 0 else '1'
            args += [
                f'-GPSAltitude={abs(meta.altitude)}',
                f'-GPSAltitudeRef={alt_ref}',
            ]
        if meta.timestamp and meta.timestamp > 0:
            dt = datetime.fromtimestamp(meta.timestamp, tz=timezone.utc)
            args += [
                f'-GPSDateStamp={dt.strftime("%Y:%m:%d")}',
                f'-GPSTimeStamp={dt.strftime("%H:%M:%S")}',
            ]

    if meta.description and not is_video:
        args += [
            f'-Description={meta.description}',
            f'-ImageDescription={meta.description}',
        ]

    args.append(str(target))
    return args


def _check_exiftool() -> Optional[str]:
    """Return the ExifTool version string, or None if not found."""
    try:
        r = subprocess.run(
            ['exiftool', '-ver'],
            capture_output=True, timeout=10,
            encoding='utf-8', errors='replace',
        )
        if r.returncode == 0:
            return r.stdout.strip()
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass
    return None


# ── Duplicate detection ───────────────────────────────────────────────────────

_HASH_CHUNK = 65536


def _file_hash(path: Path) -> str:
    h = md5()
    try:
        with path.open('rb') as f:
            while chunk := f.read(_HASH_CHUNK):
                h.update(chunk)
    except OSError:
        pass
    return h.hexdigest()


def _fmt_size(n: int) -> str:
    v = float(n)
    for unit in ('B', 'KB', 'MB', 'GB', 'TB'):
        if v < 1024 or unit == 'TB':
            return f'{n} B' if unit == 'B' else f'{v:.1f} {unit}'
        v /= 1024
    return f'{n} B'


def _brief_date(path: Path) -> str:
    ts = _ts_from_filename(path.name)
    if ts:
        return datetime.fromtimestamp(ts, tz=timezone.utc).strftime('%Y-%m-%d')
    return ''


def find_duplicates(
    src_a: Path,
    src_b: Path,
    on_progress=None,
) -> List[DupMatch]:
    same_folder = src_a.resolve() == src_b.resolve()

    files_a = sorted(
        p for p in src_a.rglob('*')
        if p.is_file() and p.suffix.lower() in ALL_MEDIA
    )
    files_b = files_a if same_folder else sorted(
        p for p in src_b.rglob('*')
        if p.is_file() and p.suffix.lower() in ALL_MEDIA
    )

    name_size_a: dict[tuple[str, int], Path] = {}
    size_a: dict[int, List[Path]] = {}
    hash_cache: dict[Path, str] = {}

    for p in files_a:
        try:
            sz = p.stat().st_size
        except OSError:
            continue
        name_size_a[(p.name.lower(), sz)] = p
        size_a.setdefault(sz, []).append(p)

    results: List[DupMatch] = []
    matched_b: set[Path] = set()
    total_b = len(files_b)

    for idx, p_b in enumerate(files_b):
        if on_progress:
            on_progress(idx + 1, total_b)
        if p_b in matched_b:
            continue
        try:
            sz = p_b.stat().st_size
        except OSError:
            continue

        key = (p_b.name.lower(), sz)
        if key in name_size_a:
            p_a = name_size_a[key]
            if p_a != p_b:
                matched_b.add(p_b)
                results.append(DupMatch(p_a, p_b, sz, 'strong'))
                continue

        candidates = [p for p in size_a.get(sz, []) if p != p_b]
        if candidates and sz > 1024:
            h_b = _file_hash(p_b)
            for p_a in candidates:
                if p_a in matched_b:
                    continue
                if p_a not in hash_cache:
                    hash_cache[p_a] = _file_hash(p_a)
                if hash_cache[p_a] == h_b:
                    matched_b.add(p_b)
                    results.append(DupMatch(p_a, p_b, sz, 'exact'))
                    break

    return results


# ── Processor ─────────────────────────────────────────────────────────────────

class Processor:
    def __init__(self, src: str, dst: str,
                 copy_unmatched: bool,
                 output_mode: str,
                 on_log, on_progress, on_stats, on_done,
                 skip_files: frozenset = frozenset()):
        self.src = Path(src)
        self.dst = Path(dst)
        self.copy_unmatched = copy_unmatched
        self.output_mode    = output_mode
        self.on_log         = on_log
        self.on_progress    = on_progress
        self.on_stats       = on_stats
        self.on_done        = on_done
        self.skip_files     = skip_files
        self.stats          = Stats()
        self.records:  List[FileRecord] = []
        self._stop          = threading.Event()

    def stop(self):
        self._stop.set()

    def _unique(self, path: Path) -> Path:
        if not path.exists():
            return path
        for i in range(1, 9999):
            candidate = path.parent / f'{path.stem}_{i}{path.suffix}'
            if not candidate.exists():
                return candidate
        return path

    def _target(self, src_file: Path, ts: Optional[int] = None) -> Path:
        if self.output_mode == 'date':
            if ts and ts > 0:
                dt  = datetime.fromtimestamp(ts, tz=timezone.utc)
                sub = Path(dt.strftime('%Y')) / dt.strftime('%m') / dt.strftime('%d')
            else:
                sub = Path('no-date')
            return self._unique(self.dst / sub / src_file.name)
        if self.output_mode == 'preserve':
            return self.dst / src_file.relative_to(self.src)
        return self._unique(self.dst / src_file.name)

    def run(self):
        ver = _check_exiftool()
        if ver is None:
            self.on_log('error', 'ExifTool not found. Please install it first:')
            self.on_log('error', '  Linux : sudo apt install libimage-exiftool-perl')
            self.on_log('error', '  macOS : brew install exiftool')
            self.on_log('error', '  Windows: https://exiftool.org  (download the installer)')
            self.on_done(False)
            return

        self.on_log('info', f'ExifTool v{ver}')
        self.on_log('info', f'Scanning source: {self.src}')
        files = sorted(
            p for p in self.src.rglob('*')
            if p.is_file() and p.suffix.lower() in ALL_MEDIA
            and not str(p).startswith(str(self.dst))
        )

        if self.skip_files:
            n_before = len(files)
            files = [p for p in files if p not in self.skip_files]
            n_skipped = n_before - len(files)
            if n_skipped:
                self.on_log('warn',
                    f'Excluding {n_skipped} file(s) skipped per duplicate-review choices.')

        self.stats.total = len(files)
        self.on_log('info', f'Found {self.stats.total} media file(s)')
        self.on_stats(self.stats)

        if not files:
            self.on_log('warn', 'No media files found in the source folder.')
            self.on_done(True)
            return

        self.dst.mkdir(parents=True, exist_ok=True)
        t_start = time.monotonic()

        for i, src_file in enumerate(files):
            if self._stop.is_set():
                self.on_log('warn', 'Processing stopped by user.')
                break

            elapsed = time.monotonic() - t_start
            fps = (i + 1) / elapsed if elapsed > 0.5 else 0.0
            rel = src_file.relative_to(self.src)
            self.on_progress(i + 1, self.stats.total, str(rel), fps)
            self.on_log('file', f'[{i+1}/{self.stats.total}]  {rel}')

            json_path = find_json(src_file)
            meta      = parse_meta(json_path) if json_path else Meta()
            eff_ts    = meta.timestamp or _ts_from_filename(src_file.name)

            dst_file = self._target(src_file, eff_ts)
            dst_file.parent.mkdir(parents=True, exist_ok=True)

            rel_src = str(rel)
            rel_dst = ''
            try:
                rel_dst = str(dst_file.relative_to(self.dst))
            except ValueError:
                rel_dst = dst_file.name

            if json_path:
                try:
                    shutil.copy2(src_file, dst_file)
                except Exception as e:
                    dst_file.unlink(missing_ok=True)
                    msg = f'Copy failed: {e}'
                    self.on_log('error', f'  ✗ {msg}')
                    self.stats.errors += 1
                    self.records.append(FileRecord(rel_src, 'error', error=msg))
                    self.on_stats(self.stats)
                    continue

                args = build_exiftool_args(dst_file, meta)
                try:
                    kw: dict = dict(
                        capture_output=True, text=True,
                        encoding='utf-8', errors='replace', timeout=120,
                    )
                    if _SYS == 'Windows':
                        kw['creationflags'] = subprocess.CREATE_NO_WINDOW
                    r = subprocess.run(args, **kw)
                    if r.returncode == 0:
                        parts = []
                        date_str = ''
                        gps_str  = ''
                        if meta.timestamp:
                            dt = datetime.fromtimestamp(meta.timestamp, tz=timezone.utc)
                            date_str = dt.strftime('%Y-%m-%d %H:%M UTC')
                            parts.append(date_str)
                        if meta.latitude is not None:
                            gps_str = f'{meta.latitude:.6f}, {meta.longitude:.6f}'
                            parts.append(f'GPS {meta.latitude:.4f}, {meta.longitude:.4f}')
                        suffix = ('  ' + '  |  '.join(parts)) if parts else ''
                        self.on_log('ok', f'  ✓{suffix}')
                        self.stats.processed += 1
                        self.records.append(FileRecord(
                            rel_src, 'fixed', dest=rel_dst,
                            date=date_str, gps=gps_str,
                        ))
                    else:
                        err = (r.stderr or r.stdout).strip()[:140]
                        self.on_log('error', f'  ✗ ExifTool: {err}')
                        self.stats.errors += 1
                        self.records.append(FileRecord(
                            rel_src, 'error', dest=rel_dst,
                            error=f'ExifTool: {err}',
                        ))
                except subprocess.TimeoutExpired:
                    msg = 'ExifTool timed out (file may be very large)'
                    self.on_log('error', f'  ✗ {msg}')
                    self.stats.errors += 1
                    self.records.append(FileRecord(
                        rel_src, 'error', dest=rel_dst, error=msg,
                    ))
            else:
                self.stats.no_json += 1
                if self.copy_unmatched:
                    try:
                        shutil.copy2(src_file, dst_file)
                    except Exception as e:
                        dst_file.unlink(missing_ok=True)
                        msg = f'Copy failed: {e}'
                        self.on_log('error', f'  ✗ {msg}')
                        self.stats.errors += 1
                        self.records.append(FileRecord(rel_src, 'error', error=msg))
                        self.on_stats(self.stats)
                        continue
                    hint = (f' → {dst_file.relative_to(self.dst)}'
                            if self.output_mode == 'date' else '')
                    self.on_log('warn', f'  ! No JSON sidecar — copied as-is{hint}')
                    self.records.append(FileRecord(rel_src, 'no_json_copied', dest=rel_dst))
                else:
                    self.on_log('warn', '  ! No JSON sidecar — skipped')
                    self.stats.skipped += 1
                    self.records.append(FileRecord(rel_src, 'no_json_skipped'))

            self.on_stats(self.stats)

        self._write_report()

        sep = '─' * 55
        self.on_log('info', sep)
        self.on_log('info',
            f'Finished.  '
            f'Fixed: {self.stats.processed}  |  '
            f'No JSON: {self.stats.no_json}  |  '
            f'Errors: {self.stats.errors}  |  '
            f'Skipped: {self.stats.skipped}'
        )
        self.on_done(True)

    def _write_report(self):
        report = {
            'generated': datetime.now(tz=timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
            'source':  str(self.src),
            'output':  str(self.dst),
            'summary': {
                'total':     self.stats.total,
                'fixed':     self.stats.processed,
                'no_json':   self.stats.no_json,
                'errors':    self.stats.errors,
                'skipped':   self.stats.skipped,
            },
            'records': [
                {k: v for k, v in r.__dict__.items() if v != ''}
                for r in self.records
            ],
        }
        try:
            report_path = self.dst / '_processing_report.json'
            report_path.write_text(
                json.dumps(report, indent=2, ensure_ascii=False),
                encoding='utf-8',
            )
        except Exception:
            pass
