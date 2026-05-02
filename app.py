#!/usr/bin/env python3
"""
Google Takeout EXIF Restoration Tool
Restores original dates, times, and GPS coordinates from Google Takeout
JSON sidecar files into photos and videos so they upload correctly.

Requires ExifTool: https://exiftool.org
  Linux:   sudo apt install libimage-exiftool-perl
  macOS:   brew install exiftool
  Windows: download installer from https://exiftool.org
"""

import os
import re
import json
import shutil
import subprocess
import threading
import platform
import time
from hashlib import md5
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from datetime import datetime, timezone
from pathlib import Path
from dataclasses import dataclass, field
from typing import Optional, List


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
class Meta:
    timestamp: Optional[int] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    altitude: Optional[float] = None
    description: str = ''


@dataclass
class DupMatch:
    """One duplicate pair discovered between two source folders."""
    file_a:     Path   # file from Source A  (labelled "Old")
    file_b:     Path   # file from Source B  (labelled "New")
    size:       int    # shared file size in bytes
    confidence: str    # 'exact' (same MD5) | 'strong' (same name+size)
    action:     str = 'keep_a'  # 'keep_a' | 'keep_b' | 'keep_both'


# ── Core helpers ──────────────────────────────────────────────────────────────

def find_json(media: Path) -> Optional[Path]:
    """Return the Google Takeout JSON sidecar for *media*, or None."""
    name = media.stem
    ext  = media.suffix

    candidates: List[Path] = [
        # Most common: photo.jpg.json
        media.parent / f"{name}{ext}.json",
        # Older format: photo.json
        media.parent / f"{name}.json",
    ]

    # Google truncates the full filename (base+ext) to 46 characters
    full = f"{name}{ext}"
    if len(full) > 46:
        trunc = full[:46]
        candidates += [
            media.parent / f"{trunc}.json",
            media.parent / f"{name[:46]}.json",
        ]

    # Numbered duplicates: foo(1).jpg → foo.jpg(1).json  or  foo(1).json
    m = re.match(r'^(.+)\((\d+)\)$', name)
    if m:
        base, num = m.group(1), m.group(2)
        candidates += [
            media.parent / f"{base}{ext}({num}).json",
            media.parent / f"{base}({num}).json",
        ]

    # Edited variants: foo-edited.jpg → foo.jpg.json
    if name.endswith('-edited'):
        orig = name[:-7]
        candidates += [
            media.parent / f"{orig}{ext}.json",
            media.parent / f"{orig}.json",
        ]

    # Supplemental-metadata variant (newer Takeout exports)
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

        # Timestamp: prefer photoTakenTime, fall back to creationTime
        for key in ('photoTakenTime', 'creationTime'):
            t = data.get(key)
            if t and 'timestamp' in t:
                ts = int(t['timestamp'])
                if ts > 0:           # guard against Unix epoch 0 (missing/null)
                    meta.timestamp = ts
                break

        # GPS: prefer geoDataExif (actual EXIF values), fall back to geoData
        for key in ('geoDataExif', 'geoData'):
            geo = data.get(key)
            if geo:
                lat = float(geo.get('latitude', 0))
                lon = float(geo.get('longitude', 0))
                # (0, 0) is Null Island — treat as no GPS data
                if lat != 0.0 or lon != 0.0:
                    meta.latitude  = lat
                    meta.longitude = lon
                    meta.altitude  = float(geo.get('altitude', 0))
                    break

        meta.description = data.get('description', '')

    except Exception:
        pass  # return empty meta on any parse error

    return meta


# Matches common camera filename date patterns: PXL_20210115_, IMG-20210115-, etc.
_DATE_IN_NAME = re.compile(r'(?<!\d)(\d{4})[_\-]?(\d{2})[_\-]?(\d{2})(?!\d)')


def _ts_from_filename(name: str) -> Optional[int]:
    """Return a UTC noon timestamp inferred from a date embedded in *name*.
    Returns None if no recognisable date pattern is found.  Uses noon so that
    UTC-offset conversions don't silently roll the date back by one day."""
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
    """Build the exiftool argument list to stamp *meta* onto *target*."""
    is_video = target.suffix.lower() in VIDEO_EXTS

    args = ['exiftool', '-overwrite_original', '-m', '-q']

    # Windows: UTF-8 charset prevents filename mangling for paths with
    # non-ASCII characters (accented letters, CJK, etc.).
    if _SYS == 'Windows':
        args += ['-charset', 'filename=UTF8']

    # Required for QuickTime/MOV/MP4 files larger than 4 GB
    if is_video:
        args += ['-api', 'LargeFileSupport=1']

    if meta.timestamp and meta.timestamp > 0:
        dt  = datetime.fromtimestamp(meta.timestamp, tz=timezone.utc)
        s   = dt.strftime('%Y:%m:%d %H:%M:%S')      # plain UTC string
        s_z = f'{s}+00:00'                            # with explicit UTC offset

        if is_video:
            # QuickTime integer-format date fields.  We write the UTC time
            # string directly and do NOT use -api QuickTimeUTC: without that
            # flag ExifTool stores the value verbatim (no local-time shift),
            # which is exactly what we want since our input is already UTC.
            for tag in ('CreateDate', 'ModifyDate',
                        'TrackCreateDate', 'TrackModifyDate',
                        'MediaCreateDate', 'MediaModifyDate'):
                args.append(f'-{tag}={s}')
            # Keys:CreationDate is the string-format tag read by Apple Photos
            # and QuickTime Player.  It supports an explicit timezone suffix.
            args.append(f'-Keys:CreationDate={s_z}')
        else:
            # EXIF image date fields
            for tag in ('DateTimeOriginal', 'CreateDate', 'ModifyDate'):
                args.append(f'-{tag}={s}')
            # OffsetTimeOriginal (EXIF 2.31+) tells timezone-aware apps that
            # the stored time is UTC.  Without it, apps that respect offsets
            # (Google Photos, Windows Photos, Lightroom) may shift the displayed
            # time to the user's local timezone using an assumed offset.
            args += [
                '-OffsetTimeOriginal=+00:00',
                '-OffsetTime=+00:00',
                '-OffsetTimeDigitized=+00:00',
            ]

    if meta.latitude is not None and meta.longitude is not None:
        if is_video:
            # QuickTime GPS: ExifTool infers N/S/E/W from the sign of the
            # value. Passing abs+Ref is ignored — Ref must come from the sign.
            args += [
                f'-GPSLatitude={meta.latitude}',
                f'-GPSLongitude={meta.longitude}',
            ]
        else:
            # EXIF GPS: absolute value + explicit Ref tag (standard EXIF format)
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
        # GPS timestamps are always UTC per the NMEA spec
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


# ── Processor ─────────────────────────────────────────────────────────────────

# ── Duplicate detection helpers ───────────────────────────────────────────────

_HASH_CHUNK = 65536   # 64 KB read buffer


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
    for unit in ('B', 'KB', 'MB', 'GB', 'TB'):
        if n < 1024 or unit == 'TB':
            return f'{n:.1f} {unit}' if unit != 'B' else f'{n} B'
        n //= 1024
    return f'{n} B'


def _brief_date(path: Path) -> str:
    """Return a YYYY-MM-DD string extracted from the filename, or ''."""
    ts = _ts_from_filename(path.name)
    if ts:
        return datetime.fromtimestamp(ts, tz=timezone.utc).strftime('%Y-%m-%d')
    return ''


def find_duplicates(
    src_a: Path,
    src_b: Path,
    on_progress=None,
) -> List['DupMatch']:
    """Find duplicate media files between (or within) two source folders.

    Matching tiers, in order of priority:
      'strong' — same filename (case-insensitive) and same file size
      'exact'  — same MD5 hash (checked when sizes match but names differ)

    When *src_a* == *src_b*, duplicates are found within the single folder
    and each pair is reported only once.
    """
    same_folder = src_a.resolve() == src_b.resolve()

    files_a = sorted(
        p for p in src_a.rglob('*')
        if p.is_file() and p.suffix.lower() in ALL_MEDIA
    )
    files_b = files_a if same_folder else sorted(
        p for p in src_b.rglob('*')
        if p.is_file() and p.suffix.lower() in ALL_MEDIA
    )

    # Build fast lookup indices over src_a
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
    # Track matched B paths so we never report the same B file twice
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

        # ── Tier 1: same name (case-insensitive) + same size ─────────────
        key = (p_b.name.lower(), sz)
        if key in name_size_a:
            p_a = name_size_a[key]
            if p_a != p_b:          # skip self (within-folder scan)
                matched_b.add(p_b)
                results.append(DupMatch(p_a, p_b, sz, 'strong'))
                continue

        # ── Tier 2: same size → compare MD5 ─────────────────────────────
        candidates = [p for p in size_a.get(sz, []) if p != p_b]
        if candidates and sz > 1024:   # skip tiny (<1 KB) files
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


class Processor:
    def __init__(self, src: str, dst: str,
                 copy_unmatched: bool,
                 output_mode: str,       # 'date' | 'preserve' | 'flat'
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
        self._stop          = threading.Event()

    def stop(self):
        self._stop.set()

    # ── helpers ──

    def _check_exiftool(self) -> Optional[str]:
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

    def _unique(self, path: Path) -> Path:
        """Return *path* unchanged if it doesn't exist, else append _1, _2 …"""
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
        # flat
        return self._unique(self.dst / src_file.name)

    # ── main entry ──

    def run(self):
        ver = self._check_exiftool()
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
            # skip anything already inside the output folder
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

            # Resolve metadata first so the date is available for path routing
            json_path = find_json(src_file)
            meta      = parse_meta(json_path) if json_path else Meta()
            eff_ts    = meta.timestamp or _ts_from_filename(src_file.name)

            dst_file = self._target(src_file, eff_ts)
            dst_file.parent.mkdir(parents=True, exist_ok=True)

            if json_path:
                try:
                    shutil.copy2(src_file, dst_file)
                except Exception as e:
                    dst_file.unlink(missing_ok=True)
                    self.on_log('error', f'  ✗ Copy failed: {e}')
                    self.stats.errors += 1
                    self.on_stats(self.stats)
                    continue
                args = build_exiftool_args(dst_file, meta)
                try:
                    kw: dict = dict(
                        capture_output=True,
                        text=True,
                        encoding='utf-8',
                        errors='replace',
                        timeout=120,
                    )
                    # Prevent a console window from flashing on Windows
                    if _SYS == 'Windows':
                        kw['creationflags'] = subprocess.CREATE_NO_WINDOW
                    r = subprocess.run(args, **kw)
                    if r.returncode == 0:
                        parts = []
                        if meta.timestamp:
                            dt = datetime.fromtimestamp(meta.timestamp,
                                                        tz=timezone.utc)
                            parts.append(dt.strftime('%Y-%m-%d %H:%M UTC'))
                        if meta.latitude is not None:
                            parts.append(
                                f'GPS {meta.latitude:.4f}, {meta.longitude:.4f}'
                            )
                        suffix = ('  ' + '  |  '.join(parts)) if parts else ''
                        self.on_log('ok', f'  ✓{suffix}')
                        self.stats.processed += 1
                    else:
                        err = (r.stderr or r.stdout).strip()[:140]
                        self.on_log('error', f'  ✗ ExifTool: {err}')
                        self.stats.errors += 1
                except subprocess.TimeoutExpired:
                    self.on_log('error', '  ✗ ExifTool timed out (file may be very large)')
                    self.stats.errors += 1

            else:
                self.stats.no_json += 1
                if self.copy_unmatched:
                    shutil.copy2(src_file, dst_file)
                    hint = f' → {dst_file.relative_to(self.dst)}' \
                           if self.output_mode == 'date' else ''
                    self.on_log('warn', f'  ! No JSON sidecar — copied as-is{hint}')
                else:
                    self.on_log('warn', '  ! No JSON sidecar — skipped')
                    self.stats.skipped += 1

            self.on_stats(self.stats)

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


# ── GUI helpers ───────────────────────────────────────────────────────────────

_SYS = platform.system()

# Cross-platform font stacks
_UI   = 'Segoe UI'   if _SYS == 'Windows' else ('SF Pro Text'    if _SYS == 'Darwin' else 'Ubuntu')
_MONO = 'Consolas'   if _SYS == 'Windows' else ('SF Mono'        if _SYS == 'Darwin' else 'DejaVu Sans Mono')

C = {
    'bg':      '#F1F3F4',
    'card':    '#FFFFFF',
    'border':  '#DADCE0',
    'primary': '#1A73E8',   # Google blue
    'success': '#188038',   # Google green
    'warn':    '#E37400',   # Google amber
    'error':   '#C5221F',   # Google red
    'text':    '#202124',
    'muted':   '#5F6368',
    'hdr_bg':  '#1A73E8',
    'hdr_fg':  '#FFFFFF',
    'hdr_sub': '#BDD7FF',
    'log_bg':  '#1E1E2E',
    'log_fg':  '#CDD6F4',
}


class FolderRow(tk.Frame):
    """Label + Entry + Browse button for folder selection."""

    def __init__(self, parent, label: str, **kw):
        super().__init__(parent, bg=C['card'], **kw)
        tk.Label(self, text=label, width=9, anchor='w',
                 bg=C['card'], fg=C['muted'],
                 font=(_UI, 9, 'bold')).pack(side='left', padx=(0, 8))

        self._var = tk.StringVar()
        self._entry = tk.Entry(
            self, textvariable=self._var,
            font=(_UI, 10), bg=C['bg'], fg=C['text'],
            relief='solid', bd=1,
            highlightthickness=1,
            highlightbackground=C['border'],
            highlightcolor=C['primary'],
        )
        self._entry.pack(side='left', fill='x', expand=True, ipady=5)

        tk.Button(
            self, text='Browse…', command=self._browse,
            bg=C['primary'], fg='white', relief='flat',
            font=(_UI, 9), padx=14, pady=6,
            activebackground='#1557B0', activeforeground='white',
            cursor='hand2',
        ).pack(side='left', padx=(8, 0))

    def _browse(self):
        d = filedialog.askdirectory(title=f'Select folder')
        if d:
            self._var.set(d)

    def get(self) -> str:
        return self._var.get().strip()


class StatCard(tk.Frame):
    """Small card displaying a numeric stat with a coloured label."""

    def __init__(self, parent, label: str, color: str, **kw):
        super().__init__(parent, bg=C['card'],
                         highlightbackground=C['border'],
                         highlightthickness=1, **kw)
        self._var = tk.StringVar(value='0')
        tk.Label(self, textvariable=self._var,
                 font=(_UI, 26, 'bold'), fg=color, bg=C['card']
                 ).pack(pady=(12, 2))
        tk.Label(self, text=label,
                 font=(_UI, 8), fg=C['muted'], bg=C['card']
                 ).pack(pady=(0, 12))

    def set(self, v: int):
        self._var.set(str(v))


# ── Duplicate review window ───────────────────────────────────────────────────

class DuplicateReviewWindow(tk.Toplevel):
    """Modal window for reviewing duplicate pairs before processing."""

    _ACTION_LABELS = {
        'keep_a':    'Keep A',
        'keep_b':    'Keep B',
        'keep_both': 'Keep Both',
    }

    def __init__(self, parent, matches: List[DupMatch]):
        super().__init__(parent)
        n = len(matches)
        self.title(f'Duplicate Review — {n} pair{"s" if n != 1 else ""} found')
        self.geometry('940x660')
        self.minsize(720, 500)
        self.configure(bg=C['bg'])
        self.transient(parent)
        self.grab_set()
        self.resizable(True, True)

        self._matches  = matches
        self._confirmed = False

        self._build_ui()
        self._populate()

        children = self._tree.get_children()
        if children:
            self._tree.selection_set(children[0])
            self._on_row_select()

        self.protocol('WM_DELETE_WINDOW', self._on_cancel)
        self.update_idletasks()
        px, py = parent.winfo_x(), parent.winfo_y()
        pw, ph = parent.winfo_width(), parent.winfo_height()
        w,  h  = self.winfo_width(), self.winfo_height()
        self.geometry(f'+{px + (pw - w) // 2}+{py + (ph - h) // 2}')
        self.wait_window()

    @property
    def confirmed(self) -> bool:
        return self._confirmed

    # ── Layout ──

    def _build_ui(self):
        hdr = tk.Frame(self, bg='#BF360C')
        hdr.pack(fill='x')
        tk.Label(hdr, text='\U0001f50d  Duplicate File Review',
                 font=(_UI, 12, 'bold'), fg='white', bg='#BF360C'
                 ).pack(side='left', padx=18, pady=12)
        tk.Label(hdr,
                 text='Files with the same name+size (strong) or same MD5 (exact) '
                      'were found in both source folders.',
                 font=(_UI, 9), fg='#FFCCBC', bg='#BF360C'
                 ).pack(side='left', pady=12)

        # Bulk-action bar
        bulk_card = tk.Frame(self, bg=C['card'],
                             highlightbackground=C['border'], highlightthickness=1)
        bulk_card.pack(fill='x', padx=14, pady=(12, 0))
        bulk = tk.Frame(bulk_card, bg=C['card'])
        bulk.pack(fill='x', padx=14, pady=8)
        tk.Label(bulk, text='Set all pairs to:',
                 font=(_UI, 9, 'bold'), fg=C['text'], bg=C['card']
                 ).pack(side='left', padx=(0, 12))
        for action, label in self._ACTION_LABELS.items():
            a = action
            tk.Button(
                bulk, text=label,
                command=lambda a=a: self._set_all(a),
                bg=C['bg'], fg=C['text'], relief='solid', bd=1,
                font=(_UI, 9), padx=14, pady=5, cursor='hand2',
            ).pack(side='left', padx=(0, 6))
        tk.Label(bulk, text='  ·  Click any row to set its action individually.',
                 font=(_UI, 8), fg=C['muted'], bg=C['card']
                 ).pack(side='left')

        # Treeview
        tree_wrap = tk.Frame(self, bg=C['bg'])
        tree_wrap.pack(fill='both', expand=True, padx=14, pady=(10, 0))

        cols = ('file_a', 'file_b', 'size', 'match', 'action')
        self._tree = ttk.Treeview(tree_wrap, columns=cols,
                                  show='headings', selectmode='browse')
        col_specs = [
            ('file_a',  'File in Source A',  230, 'w'),
            ('file_b',  'File in Source B',  230, 'w'),
            ('size',    'Size',               80, 'e'),
            ('match',   'Match',              80, 'center'),
            ('action',  'Action',            110, 'center'),
        ]
        for cid, head, w, anc in col_specs:
            self._tree.heading(cid, text=head)
            self._tree.column(cid, width=w, minwidth=60, anchor=anc)

        vsb = ttk.Scrollbar(tree_wrap, orient='vertical', command=self._tree.yview)
        self._tree.configure(yscrollcommand=vsb.set)
        vsb.pack(side='right', fill='y')
        self._tree.pack(fill='both', expand=True)

        self._tree.tag_configure('keep_a',    background='#FFF3E0')
        self._tree.tag_configure('keep_b',    background='#E8F5E9')
        self._tree.tag_configure('keep_both', background='#E3F2FD')
        self._tree.bind('<<TreeviewSelect>>', lambda _e: self._on_row_select())

        # Detail / action panel
        det_card = tk.Frame(self, bg=C['card'],
                            highlightbackground=C['border'], highlightthickness=1)
        det_card.pack(fill='x', padx=14, pady=(8, 0))
        dp = tk.Frame(det_card, bg=C['card'])
        dp.pack(fill='x', padx=14, pady=10)

        self._lbl_a = tk.Label(dp, text='', font=(_MONO, 8), fg=C['muted'],
                               bg=C['card'], anchor='w', wraplength=880)
        self._lbl_a.pack(fill='x')
        self._lbl_b = tk.Label(dp, text='', font=(_MONO, 8), fg=C['muted'],
                               bg=C['card'], anchor='w', wraplength=880)
        self._lbl_b.pack(fill='x')

        radio_row = tk.Frame(dp, bg=C['card'])
        radio_row.pack(anchor='w', pady=(8, 0))
        tk.Label(radio_row, text='Action for selected pair:',
                 font=(_UI, 9, 'bold'), fg=C['text'], bg=C['card']
                 ).pack(side='left', padx=(0, 14))
        self._action_var = tk.StringVar(value='keep_a')
        for action, label in self._ACTION_LABELS.items():
            tk.Radiobutton(
                radio_row, text=label, value=action,
                variable=self._action_var,
                command=self._on_action_change,
                font=(_UI, 9), fg=C['text'], bg=C['card'],
                activebackground=C['card'], selectcolor=C['bg'],
            ).pack(side='left', padx=(0, 22))

        # Bottom buttons
        btn_bar = tk.Frame(self, bg=C['bg'])
        btn_bar.pack(fill='x', padx=14, pady=12)
        tk.Button(
            btn_bar, text='✓  Save Choices & Continue Processing',
            command=self._on_confirm,
            bg=C['success'], fg='white', relief='flat',
            font=(_UI, 10, 'bold'), padx=22, pady=9,
            activebackground='#145C2C', activeforeground='white',
            cursor='hand2',
        ).pack(side='left', padx=(0, 10))
        tk.Button(
            btn_bar, text='✕  Cancel',
            command=self._on_cancel,
            bg=C['error'], fg='white', relief='flat',
            font=(_UI, 10, 'bold'), padx=18, pady=9,
            activebackground='#922018', activeforeground='white',
            cursor='hand2',
        ).pack(side='left')
        tk.Label(
            btn_bar,
            text=f'{len(self._matches)} duplicate pair(s).  '
                 '"Keep A" = skip Source B copy.  '
                 '"Keep B" = skip Source A copy.  '
                 '"Keep Both" = process both.',
            font=(_UI, 8), fg=C['muted'], bg=C['bg'],
        ).pack(side='left', padx=16)

    # ── Tree helpers ──

    def _populate(self):
        for i, m in enumerate(self._matches):
            conf = '✓✓ exact' if m.confidence == 'exact' else '✓ strong'
            self._tree.insert('', 'end', iid=str(i),
                              values=(m.file_a.name, m.file_b.name,
                                      _fmt_size(m.size), conf,
                                      self._ACTION_LABELS[m.action]),
                              tags=(m.action,))

    def _sel_index(self) -> Optional[int]:
        sel = self._tree.selection()
        return int(sel[0]) if sel else None

    def _refresh_row(self, idx: int):
        m    = self._matches[idx]
        conf = '✓✓ exact' if m.confidence == 'exact' else '✓ strong'
        self._tree.item(str(idx),
                        values=(m.file_a.name, m.file_b.name,
                                _fmt_size(m.size), conf,
                                self._ACTION_LABELS[m.action]),
                        tags=(m.action,))

    # ── Event handlers ──

    def _on_row_select(self):
        idx = self._sel_index()
        if idx is None:
            return
        m = self._matches[idx]
        self._lbl_a.config(text=f'A: {m.file_a}')
        self._lbl_b.config(text=f'B: {m.file_b}')
        self._action_var.set(m.action)

    def _on_action_change(self):
        idx = self._sel_index()
        if idx is None:
            return
        self._matches[idx].action = self._action_var.get()
        self._refresh_row(idx)

    def _set_all(self, action: str):
        for i, m in enumerate(self._matches):
            m.action = action
            self._refresh_row(i)
        self._on_row_select()

    def _on_confirm(self):
        self._confirmed = True
        self.destroy()

    def _on_cancel(self):
        self._confirmed = False
        self.destroy()


# ── Main window ───────────────────────────────────────────────────────────────

class App(tk.Tk):

    def __init__(self):
        super().__init__()
        self.title('Google Takeout — EXIF Restoration Tool')
        self.geometry('960x740')
        self.minsize(740, 580)
        self.configure(bg=C['bg'])
        self._processor: Optional[Processor] = None
        self._thread:    Optional[threading.Thread] = None
        self._build_ui()

    # ── Layout builders ───────────────────────────────────────────────────

    def _card_frame(self, parent, pady=(0, 10)):
        """Return an inner Frame wrapped in a white card with a border."""
        outer = tk.Frame(parent, bg=C['card'],
                         highlightbackground=C['border'],
                         highlightthickness=1)
        outer.pack(fill='x', padx=20, pady=pady)
        inner = tk.Frame(outer, bg=C['card'])
        inner.pack(fill='x', padx=18, pady=14)
        return inner

    def _section_label(self, parent, text: str):
        tk.Label(parent, text=text, font=(_UI, 10, 'bold'),
                 fg=C['text'], bg=C['card']).pack(anchor='w', pady=(0, 10))

    def _build_ui(self):
        self._build_header()
        self._build_folders()
        self._build_options()
        self._build_controls()
        self._build_stats()
        self._build_progress()
        self._build_log()

    def _build_header(self):
        hdr = tk.Frame(self, bg=C['hdr_bg'])
        hdr.pack(fill='x')
        tk.Label(hdr, text='\U0001f4f7  Google Takeout EXIF Restoration',
                 font=(_UI, 14, 'bold'), fg=C['hdr_fg'], bg=C['hdr_bg']
                 ).pack(side='left', padx=20, pady=16)
        tk.Label(hdr,
                 text='Restores original dates, times & GPS locations '
                      'from Google Takeout JSON sidecars',
                 font=(_UI, 9), fg=C['hdr_sub'], bg=C['hdr_bg']
                 ).pack(side='left', pady=16)

    def _build_folders(self):
        inner = self._card_frame(self, pady=(16, 10))
        self._section_label(inner, 'Folders')

        self._src_row = FolderRow(inner, 'Source A')
        self._src_row.pack(fill='x', pady=(0, 8))

        self._dst_row = FolderRow(inner, 'Output')
        self._dst_row.pack(fill='x', pady=(0, 8))

        self._has_src2 = tk.BooleanVar(value=False)
        self._dup_chk = tk.Checkbutton(
            inner,
            text='Compare against a second Takeout export for duplicate detection (optional)',
            variable=self._has_src2,
            command=self._on_src2_toggle,
            font=(_UI, 9, 'bold'), fg=C['text'], bg=C['card'],
            activebackground=C['card'], selectcolor=C['bg'],
        )
        self._dup_chk.pack(anchor='w', pady=(0, 4))

        # Source B row — not packed until the checkbox is ticked
        self._src2_row = FolderRow(inner, 'Source B')

        self._src_hint = tk.Label(
            inner,
            text='Source A: your unzipped Google Takeout folder.  '
                 'Output: where fixed copies will be saved.',
            font=(_UI, 8), fg=C['muted'], bg=C['card'],
        )
        self._src_hint.pack(anchor='w')

    def _build_options(self):
        inner = self._card_frame(self)
        self._section_label(inner, 'Options')

        # ── unmatched files ──
        self._copy_unmatched = tk.BooleanVar(value=True)
        tk.Checkbutton(
            inner,
            text='Copy files that have no matching JSON sidecar (unchanged)',
            variable=self._copy_unmatched,
            font=(_UI, 9), fg=C['muted'], bg=C['card'],
            activebackground=C['card'], selectcolor=C['bg'],
        ).pack(anchor='w', pady=(0, 10))

        # ── output organisation ──
        tk.Label(inner, text='Output folder organisation:',
                 font=(_UI, 9, 'bold'), fg=C['text'],
                 bg=C['card']).pack(anchor='w', pady=(0, 4))

        self._output_mode = tk.StringVar(value='date')
        modes = [
            ('date',
             'Organise by date  —  output / 2021 / 01 / 15 / photo.jpg  '
             '(recommended)'),
            ('preserve',
             'Preserve original folder structure'),
            ('flat',
             'Flat  —  all files in one folder'),
        ]
        for value, label in modes:
            tk.Radiobutton(
                inner, text=label, value=value,
                variable=self._output_mode,
                font=(_UI, 9), fg=C['muted'], bg=C['card'],
                activebackground=C['card'], selectcolor=C['bg'],
            ).pack(anchor='w')

    def _build_controls(self):
        inner = self._card_frame(self, pady=(0, 10))
        row = tk.Frame(inner, bg=C['card'])
        row.pack(fill='x')

        self._btn_start = tk.Button(
            row, text='▶  Start Processing', command=self._on_start,
            bg=C['success'], fg='white', relief='flat',
            font=(_UI, 10, 'bold'), padx=20, pady=9,
            activebackground='#145C2C', activeforeground='white',
            cursor='hand2',
        )
        self._btn_start.pack(side='left', padx=(0, 8))

        self._btn_stop = tk.Button(
            row, text='■  Stop', command=self._on_stop,
            bg=C['error'], fg='white', relief='flat',
            font=(_UI, 10, 'bold'), padx=20, pady=9,
            activebackground='#922018', activeforeground='white',
            cursor='hand2', state='disabled',
        )
        self._btn_stop.pack(side='left', padx=(0, 8))

        self._btn_open = tk.Button(
            row, text='\U0001f4c2  Open Output Folder',
            command=self._on_open_output,
            bg=C['bg'], fg=C['primary'], relief='solid', bd=1,
            font=(_UI, 10), padx=16, pady=9, cursor='hand2',
        )
        self._btn_open.pack(side='left')

    def _build_stats(self):
        inner = self._card_frame(self)
        self._stat_total  = StatCard(inner, 'TOTAL',   C['primary'])
        self._stat_fixed  = StatCard(inner, 'FIXED',   C['success'])
        self._stat_nojson = StatCard(inner, 'NO JSON', C['warn'])
        self._stat_errors = StatCard(inner, 'ERRORS',  C['error'])
        for i, card in enumerate((self._stat_total, self._stat_fixed,
                                   self._stat_nojson, self._stat_errors)):
            card.pack(side='left', fill='x', expand=True,
                      padx=(0, 0 if i == 3 else 8))

    def _build_progress(self):
        inner = self._card_frame(self, pady=(0, 10))

        row_top = tk.Frame(inner, bg=C['card'])
        row_top.pack(fill='x', pady=(0, 4))
        self._prog_label = tk.Label(
            row_top, text='Ready — select folders and press Start',
            font=(_UI, 9), fg=C['muted'], bg=C['card'],
            anchor='w',
        )
        self._prog_label.pack(side='left', fill='x', expand=True)

        style = ttk.Style()
        style.theme_use('clam')
        style.configure('G.Horizontal.TProgressbar',
                        troughcolor=C['border'],
                        background=C['primary'],
                        borderwidth=0, thickness=12)
        self._progress = ttk.Progressbar(
            inner, style='G.Horizontal.TProgressbar',
            mode='determinate',
        )
        self._progress.pack(fill='x')

        self._prog_file = tk.Label(
            inner, text='',
            font=(_MONO, 8), fg=C['muted'], bg=C['card'],
            anchor='w',
        )
        self._prog_file.pack(anchor='w', pady=(4, 0))

    def _build_log(self):
        wrapper = tk.Frame(self, bg=C['bg'])
        wrapper.pack(fill='both', expand=True, padx=20, pady=(0, 18))

        tk.Label(wrapper, text='Log', font=(_UI, 10, 'bold'),
                 fg=C['text'], bg=C['bg']).pack(anchor='w', pady=(0, 4))

        border = tk.Frame(wrapper, bg=C['log_bg'],
                          highlightbackground=C['border'],
                          highlightthickness=1)
        border.pack(fill='both', expand=True)

        self._log = tk.Text(
            border, bg=C['log_bg'], fg=C['log_fg'],
            font=(_MONO, 9), relief='flat', bd=0,
            state='disabled', wrap='none',
            insertbackground=C['log_fg'],
        )
        vsb = ttk.Scrollbar(border, orient='vertical',
                            command=self._log.yview)
        self._log.configure(yscrollcommand=vsb.set)
        vsb.pack(side='right', fill='y')
        self._log.pack(side='left', fill='both', expand=True, padx=6, pady=6)

        # Colour tags for log levels
        self._log.tag_config('info',  foreground='#89B4FA')  # blue
        self._log.tag_config('ok',    foreground='#A6E3A1')  # green
        self._log.tag_config('warn',  foreground='#FAB387')  # peach/orange
        self._log.tag_config('error', foreground='#F38BA8')  # red
        self._log.tag_config('file',  foreground='#CDD6F4')  # default

    # ── Callbacks from Processor (run on worker thread → schedule on main) ──

    def _log_msg(self, level: str, text: str):
        def _do():
            self._log.configure(state='normal')
            self._log.insert('end', text + '\n', level)
            self._log.see('end')
            self._log.configure(state='disabled')
        self.after(0, _do)

    def _update_progress(self, current: int, total: int,
                         current_file: str = '', fps: float = 0.0):
        def _do():
            pct = int(current / total * 100) if total else 0
            self._progress['value'] = pct
            speed = f'  ·  {fps:.1f} files/s' if fps > 0.1 else ''
            self._prog_label.config(
                text=f'Processing {current} of {total}  ({pct}%){speed}'
            )
            name = Path(current_file).name if current_file else ''
            self._prog_file.config(text=name if name else '')
        self.after(0, _do)

    def _update_stats(self, stats: Stats):
        def _do():
            self._stat_total.set(stats.total)
            self._stat_fixed.set(stats.processed)
            self._stat_nojson.set(stats.no_json)
            self._stat_errors.set(stats.errors)
        self.after(0, _do)

    def _on_done(self, ok: bool):
        def _do():
            self._btn_start.config(state='normal')
            self._btn_stop.config(state='disabled')
            if ok:
                self._prog_label.config(text='Done ✔')
                self._prog_file.config(text='')
        self.after(0, _do)

    # ── Button handlers ───────────────────────────────────────────────────

    def _on_start(self):
        src_a = self._src_row.get()
        dst   = self._dst_row.get()
        src_b = self._src2_row.get() if self._has_src2.get() else ''

        if not src_a:
            messagebox.showerror('No Source Folder',
                                 'Please select a Source A folder.')
            return
        if not dst:
            messagebox.showerror('No Output Folder',
                                 'Please select an output folder.')
            return
        if self._has_src2.get() and not src_b:
            messagebox.showerror('No Source B',
                                 'Please select Source B or uncheck duplicate detection.')
            return
        if not Path(src_a).is_dir():
            messagebox.showerror('Invalid Source A', f'Folder not found:\n{src_a}')
            return
        if src_b and not Path(src_b).is_dir():
            messagebox.showerror('Invalid Source B', f'Folder not found:\n{src_b}')
            return
        if Path(src_a).resolve() == Path(dst).resolve():
            messagebox.showerror('Same Folder',
                                 'Source A and output folders must be different.')
            return
        if src_b and Path(src_b).resolve() == Path(dst).resolve():
            messagebox.showerror('Same Folder',
                                 'Source B and output folders must be different.')
            return

        # Disable Stop during the dedup-scan phase; it re-enables when processing starts
        self._reset_ui(enable_stop=not bool(src_b))

        if src_b:
            self._run_dedup_then_process(src_a, src_b, dst)
        else:
            self._start_single_processing(src_a, dst, frozenset())

    # ── UI helpers ────────────────────────────────────────────────────────

    def _reset_ui(self, enable_stop: bool = True):
        self._log.configure(state='normal')
        self._log.delete('1.0', 'end')
        self._log.configure(state='disabled')
        self._progress['value'] = 0
        self._stat_total.set(0)
        self._stat_fixed.set(0)
        self._stat_nojson.set(0)
        self._stat_errors.set(0)
        self._prog_label.config(text='Starting…')
        self._prog_file.config(text='')
        self._btn_start.config(state='disabled')
        self._btn_stop.config(state='normal' if enable_stop else 'disabled')

    def _on_src2_toggle(self):
        if self._has_src2.get():
            self._src2_row.pack(fill='x', pady=(0, 4), after=self._dup_chk)
            self._src_hint.config(
                text='Source A & B: your two Takeout export folders.  '
                     'Output: where fixed copies will be saved.',
            )
        else:
            self._src2_row.pack_forget()
            self._src_hint.config(
                text='Source A: your unzipped Google Takeout folder.  '
                     'Output: where fixed copies will be saved.',
            )

    # ── Processing helpers ────────────────────────────────────────────────

    def _start_single_processing(self, src: str, dst: str, skip: frozenset,
                                  on_done=None, phase_label: str = ''):
        if phase_label:
            self._log_msg('info', f'── {phase_label} ──')
        self._processor = Processor(
            src=src, dst=dst,
            copy_unmatched=self._copy_unmatched.get(),
            output_mode=self._output_mode.get(),
            on_log=self._log_msg,
            on_progress=self._update_progress,
            on_stats=self._update_stats,
            on_done=on_done or self._on_done,
            skip_files=skip,
        )
        self._thread = threading.Thread(target=self._processor.run, daemon=True)
        self._thread.start()

    def _run_dedup_then_process(self, src_a: str, src_b: str, dst: str):
        self._progress.configure(mode='indeterminate')
        self._progress.start(15)
        self._prog_label.config(text='Building file index for duplicate detection…')

        def _on_scan_progress(current: int, total: int):
            def _do():
                try:
                    self._progress.stop()
                except Exception:
                    pass
                self._progress.configure(mode='determinate')
                pct = int(current / total * 100) if total else 0
                self._progress['value'] = pct
                self._prog_label.config(
                    text=f'Scanning for duplicates: {current} / {total}  ({pct} %)'
                )
            self.after(0, _do)

        def _worker():
            matches = find_duplicates(Path(src_a), Path(src_b), _on_scan_progress)
            self.after(0, lambda: self._show_dedup_results(matches, src_a, src_b, dst))

        self._thread = threading.Thread(target=_worker, daemon=True)
        self._thread.start()

    def _show_dedup_results(self, matches: List[DupMatch],
                            src_a: str, src_b: str, dst: str):
        try:
            self._progress.stop()
        except Exception:
            pass
        self._progress.configure(mode='determinate')
        self._progress['value'] = 0

        if matches:
            self._prog_label.config(
                text=f'Scan complete — {len(matches)} duplicate pair(s) found.')
            self._log_msg('info',
                f'Found {len(matches)} duplicate pair(s) between the two sources. '
                'Opening review window…')
            win = DuplicateReviewWindow(self, matches)
            if not win.confirmed:
                self._log_msg('warn', 'Duplicate review cancelled — processing aborted.')
                self._btn_start.config(state='normal')
                self._btn_stop.config(state='disabled')
                self._prog_label.config(text='Cancelled.')
                return
        else:
            self._prog_label.config(text='No duplicates found — starting processing…')
            self._log_msg('info', 'No duplicates found between the two source folders.')

        self._start_two_source_processing(matches, src_a, src_b, dst)

    def _start_two_source_processing(self, matches: List[DupMatch],
                                      src_a: str, src_b: str, dst: str):
        skip_a = frozenset(m.file_a for m in matches if m.action == 'keep_b')
        skip_b = frozenset(m.file_b for m in matches if m.action == 'keep_a')

        if skip_a:
            self._log_msg('info',
                f'Source A: skipping {len(skip_a)} dup(s) (user chose Keep B).')
        if skip_b:
            self._log_msg('info',
                f'Source B: skipping {len(skip_b)} dup(s) (user chose Keep A).')

        def _phase1_done(ok: bool):
            def _on_main():
                if ok and not (self._processor and self._processor._stop.is_set()):
                    self._btn_stop.config(state='normal')
                    self._start_single_processing(
                        src_b, dst, skip_b,
                        phase_label='Processing Source B',
                    )
                else:
                    self._on_done(ok)
            self.after(0, _on_main)

        self._btn_stop.config(state='normal')
        self._start_single_processing(
            src_a, dst, skip_a,
            on_done=_phase1_done,
            phase_label='Processing Source A',
        )

    def _on_stop(self):
        if self._processor:
            self._processor.stop()
        self._btn_stop.config(state='disabled')

    def _on_open_output(self):
        dst = self._dst_row.get()
        if dst and Path(dst).is_dir():
            if _SYS == 'Windows':
                os.startfile(dst)  # type: ignore[attr-defined]
            elif _SYS == 'Darwin':
                subprocess.Popen(['open', dst])
            else:
                subprocess.Popen(['xdg-open', dst])
        else:
            messagebox.showinfo('No Output Folder',
                                'Please select an output folder first.')


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == '__main__':
    app = App()
    app.mainloop()
