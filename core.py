#!/usr/bin/env python3
"""
Core processing logic for the Photos Backup Fix desktop tool.

No GUI dependencies — importable from the web UI (web_app.py), the CLI
(cli.py), the legacy tkinter UI (app.py) and the test-suite.

Tools (all built on the same `Job` base class):

  Processor        Google Takeout → dated output folders, metadata restored
                   from JSON sidecars / filename dates.  With
                   ProcessOptions(use_sidecars=False) it is the
                   "Sort by filename date" tool.
  DriveFixer       Stamp missing dates in place on an existing
                   year/Month/Month_DD drive (folder name = date).
  FolderRenamer    Rename legacy numeric 2024/01/15 folders to the
                   2024/January/January_15 layout used by the Android app.
  DuplicateFinder  Content-hash duplicate finder (report or move aside).

Folder layout, fallback folders (no-date/, error/) and EXIF tag choices
match the Android app (Übertrag) so both tools can share one drive.
"""

from __future__ import annotations

import csv
import hashlib
import json
import os
import platform
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, Dict, Iterable, List, Optional, Sequence, Tuple

_SYS = platform.system()
APP_ROOT = Path(__file__).resolve().parent

# ── Media extensions ──────────────────────────────────────────────────────────

IMAGE_EXTS = {
    '.jpg', '.jpeg', '.jpe', '.jfif', '.png', '.gif', '.bmp', '.tiff', '.tif',
    '.webp', '.heic', '.heif', '.avif', '.jxl', '.mpo',
    '.raw', '.cr2', '.cr3', '.crw', '.nef', '.nrw', '.arw', '.srf', '.sr2', '.raf',
    '.dng', '.orf', '.rw2', '.rwl', '.srw', '.pef', '.3fr', '.iiq', '.x3f', '.erf',
    '.kdc', '.dcr', '.mrw', '.mef', '.mos',
}
VIDEO_EXTS = {
    '.mp4', '.mov', '.qt', '.avi', '.m4v', '.mkv', '.wmv', '.asf', '.3gp', '.3g2',
    '.mpg', '.mpeg', '.mts', '.m2ts', '.m2t', '.vob', '.mod', '.tod', '.flv', '.f4v',
    '.webm', '.ogv', '.ts', '.divx', '.dv', '.insv',
}
ALL_MEDIA = IMAGE_EXTS | VIDEO_EXTS

# Formats ExifTool cannot write date/GPS metadata into.  They are still
# copied/sorted and get their file-system timestamps set.
NON_WRITABLE_EXTS = {
    '.bmp', '.raw', '.x3f', '.crw', '.kdc', '.dcr', '.mrw', '.mef', '.mos', '.srf', '.sr2',
    '.avi', '.mkv', '.wmv', '.asf', '.mpg', '.mpeg', '.mts', '.m2ts', '.m2t', '.vob',
    '.mod', '.tod', '.flv', '.webm', '.ogv', '.ts', '.divx', '.dv',
}

# Archives a Google Takeout download comes as (see `ArchiveExtractor`).
ARCHIVE_EXTS = ('.zip', '.tgz', '.tar.gz', '.tar')

MONTHS = [
    'January', 'February', 'March', 'April', 'May', 'June',
    'July', 'August', 'September', 'October', 'November', 'December',
]
_MONTH_LOOKUP = {m.lower(): i + 1 for i, m in enumerate(MONTHS)}

OUTPUT_MODES = ('date', 'date_numeric', 'preserve', 'flat')

NO_DATE_DIR = 'no-date'        # same names as the Android app
ERROR_DIR = 'error'
APP_DIR = '_photofix'          # reports + manifest live here
DUPES_DIR = '_duplicates'
_TMP_PREFIX = '.~photofix~'

_SKIP_DIRS = {
    APP_DIR.lower(), '$recycle.bin', 'system volume information', '.trashes', '.trash',
    '@eadir', '.thumbnails', '.spotlight-v100', '.fseventsd', '.temporaryitems', '.git',
}
_SYSTEM_FILES = {'thumbs.db', 'desktop.ini', '.ds_store', 'icon\r', 'picasa.ini', '.picasa.ini',
                 'archive_browser.html', '.nomedia'}
# Not photos even though they may carry a photo extension.
_JUNK_PREFIXES = ('._', _TMP_PREFIX, '.trashed-', '.pending-')

YEAR_MIN, YEAR_MAX = 2000, 2040   # same plausibility window as the Android app


# ── Data classes ──────────────────────────────────────────────────────────────

@dataclass
class Stats:
    total: int = 0
    processed: int = 0       # metadata written (sidecar or filename date)
    from_sidecar: int = 0
    from_filename: int = 0
    kept_existing: int = 0   # file already had a date — sorted by it, not overwritten
    no_json: int = 0         # no date found at all (copied to no-date/)
    unsupported: int = 0     # dated + sorted, but format can't hold metadata
    skipped: int = 0         # already in output / duplicate of another file
    meta_failed: int = 0     # copied to its date folder, but ExifTool failed
    errors: int = 0          # could not be copied (copied to error/ if possible)
    filtered: int = 0        # outside the chosen date range
    bytes_copied: int = 0


@dataclass
class FileRecord:
    file:   str         # relative source path
    status: str         # see Processor docstring
    dest:   str = ''    # relative output path (empty if not written)
    date:   str = ''    # date used for sorting / written
    source: str = ''    # where the date came from: sidecar|filename|embedded|folder
    gps:    str = ''
    note:   str = ''
    error:  str = ''

    def as_dict(self) -> dict:
        return {k: v for k, v in asdict(self).items() if v != ''}


@dataclass
class Meta:
    timestamp: Optional[int] = None
    latitude: Optional[float] = None
    longitude: Optional[float] = None
    altitude: Optional[float] = None
    description: str = ''
    timestamp_source: str = ''           # 'photoTakenTime' | 'creationTime'
    title: str = ''
    people: List[str] = field(default_factory=list)
    favorited: bool = False


@dataclass
class DupMatch:
    file_a:     Path     # kept
    file_b:     Path     # duplicate
    size:       int
    confidence: str      # always 'exact' now (content hash verified)


# ── Dates ─────────────────────────────────────────────────────────────────────

_EXIF_FMT = '%Y:%m:%d %H:%M:%S'


def _fmt_offset(td: Optional[timedelta]) -> str:
    if td is None:
        return '+00:00'
    mins = int(td.total_seconds() // 60)
    sign = '+' if mins >= 0 else '-'
    mins = abs(mins)
    return f'{sign}{mins // 60:02d}:{mins % 60:02d}'


def _parse_offset(s: str) -> Optional[timedelta]:
    m = re.fullmatch(r'([+-])(\d{2}):?(\d{2})', (s or '').strip())
    if not m:
        return None
    td = timedelta(hours=int(m.group(2)), minutes=int(m.group(3)))
    return -td if m.group(1) == '-' else td


@dataclass(frozen=True)
class PhotoDate:
    """A wall-clock time plus the UTC offset it was taken in."""
    dt: datetime          # naive wall-clock
    offset: str           # '+00:00', '+05:30', or '' when unknown
    source: str           # sidecar | filename | embedded | folder

    @staticmethod
    def from_unix(ts: float, source: str) -> 'PhotoDate':
        dt = datetime.fromtimestamp(int(ts), tz=timezone.utc).replace(tzinfo=None)
        return PhotoDate(dt, '+00:00', source)

    @property
    def exif(self) -> str:
        return self.dt.strftime(_EXIF_FMT)

    @property
    def utc(self) -> datetime:
        td = _parse_offset(self.offset)
        if td is None:                     # unknown offset (camera EXIF) → this PC's zone
            td = _parse_offset(_local_offset(self.dt)) or timedelta(0)
        return self.dt - td

    def timestamp(self) -> float:
        return self.utc.replace(tzinfo=timezone.utc).timestamp()

    def display(self) -> str:
        off = self.offset or 'local'
        if off == '+00:00':
            off = 'UTC'
        return f'{self.dt:%Y-%m-%d %H:%M} {off}'


_DATE_IN_NAME = re.compile(
    r'(?<!\d)(\d{4})[_\-.]?(\d{2})[_\-.]?(\d{2})'
    r'(?:[_\-T. ]?(\d{2})[_\-.:]?(\d{2})[_\-.:]?(\d{2})(?:[._\-]?\d{1,3})?)?'
    r'(?!\d)'
)
_EPOCH_NAME = re.compile(r'^(\d{10}|\d{13})$')


def _valid_ymd(y: int, mo: int, d: int) -> bool:
    if not (YEAR_MIN <= y <= YEAR_MAX and 1 <= mo <= 12 and 1 <= d <= 31):
        return False
    try:
        datetime(y, mo, d)
    except ValueError:
        return False
    return True


def _local_offset(naive: datetime) -> str:
    try:
        return _fmt_offset(naive.astimezone().utcoffset())
    except (OverflowError, OSError, ValueError):
        return '+00:00'


def date_from_filename(name: str, tz: str = 'local') -> Optional[PhotoDate]:
    """Extract a date (and time, when present) from a filename.

    IMG_20240315_143022.jpg   → 2024-03-15 14:30:22 (local time of this PC)
    VID-20240315-WA0001.mp4   → 2024-03-15 12:00 UTC (date only → noon UTC,
                                same as the Android app)
    1710513022123.jpg         → Unix-epoch milliseconds

    tz: 'local' — times in filenames are this PC's local time (cameras and
        phones name files in local time); 'utc' — treat them as UTC.
    """
    stem = name.rsplit('.', 1)[0] if '.' in name else name

    for m in _DATE_IN_NAME.finditer(name):
        y, mo, d = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if not _valid_ymd(y, mo, d):
            continue
        if m.group(4):
            hh, mi, ss = int(m.group(4)), int(m.group(5)), int(m.group(6))
            if hh < 24 and mi < 60 and ss < 60:
                dt = datetime(y, mo, d, hh, mi, ss)
                off = _local_offset(dt) if tz == 'local' else '+00:00'
                return PhotoDate(dt, off, 'filename')
        return PhotoDate(datetime(y, mo, d, 12, 0, 0), '+00:00', 'filename')

    m = _EPOCH_NAME.match(stem)
    if m:
        v = int(m.group(1))
        ts = v / 1000 if len(m.group(1)) == 13 else v
        try:
            pd = PhotoDate.from_unix(ts, 'filename')
        except (OverflowError, OSError, ValueError):
            return None
        if YEAR_MIN <= pd.dt.year <= YEAR_MAX:
            return pd
    return None


def _ts_from_filename(name: str) -> Optional[int]:
    """Back-compat: Unix timestamp of the filename's date at 12:00 UTC."""
    pd = date_from_filename(name)
    if pd is None:
        return None
    return int(datetime(pd.dt.year, pd.dt.month, pd.dt.day, 12,
                        tzinfo=timezone.utc).timestamp())


def parse_embedded_date(tags: dict, is_video: bool) -> Optional[PhotoDate]:
    """Turn ExifTool JSON output into a PhotoDate (None if no usable date)."""
    keys = (('CreateDate', 'MediaCreateDate', 'DateTimeOriginal') if is_video
            else ('DateTimeOriginal', 'CreateDate'))
    for k in keys:
        raw = str(tags.get(k) or '').strip()
        m = re.match(r'(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})(.*)$', raw)
        if not m:
            continue
        y, mo, d, hh, mi, ss = (int(x) for x in m.groups()[:6])
        if y < 1971 or not (1 <= mo <= 12 and 1 <= d <= 31):
            continue
        try:
            dt = datetime(y, mo, d, hh, mi, ss)
        except ValueError:
            continue
        rest = m.group(7)
        if is_video and k != 'DateTimeOriginal':
            return PhotoDate(dt, '+00:00', 'embedded')        # QuickTime = UTC
        om = re.search(r'([+-]\d{2}:\d{2})', rest)
        off = om.group(1) if om else str(tags.get('OffsetTimeOriginal') or '').strip()
        return PhotoDate(dt, off if _parse_offset(off) is not None else '', 'embedded')
    return None


# ── Folder layout ─────────────────────────────────────────────────────────────

def date_subdir(dt: datetime, numeric: bool = False) -> Path:
    """2024/January/January_07 (Android layout) or 2024/01/07 (legacy)."""
    if numeric:
        return Path(f'{dt.year:04d}') / f'{dt.month:02d}' / f'{dt.day:02d}'
    month = MONTHS[dt.month - 1]
    return Path(f'{dt.year:04d}') / month / f'{month}_{dt.day:02d}'


def parse_month_folder(name: str) -> Optional[int]:
    n = (name or '').strip()
    if n.lower() in _MONTH_LOOKUP:
        return _MONTH_LOOKUP[n.lower()]
    if n.isdigit() and 1 <= int(n) <= 12:
        return int(n)
    return None


def parse_day_folder(name: str) -> Optional[int]:
    """'January_07' → 7, 'January 7' → 7, '15' → 15 (same as ExifFixer.kt)."""
    n = (name or '').strip()
    if '_' in n:
        n = n.rsplit('_', 1)[1]
    elif ' ' in n:
        n = n.rsplit(' ', 1)[1]
    if n.isdigit() and 1 <= int(n) <= 31:
        return int(n)
    return None


def parse_year_folder(name: str) -> Optional[int]:
    n = (name or '').strip()
    if len(n) == 4 and n.isdigit() and YEAR_MIN <= int(n) <= YEAR_MAX:
        return int(n)
    return None


# ── File-system helpers ───────────────────────────────────────────────────────

def _fs(p: Path) -> str:
    """Path string safe for >260-char paths on Windows."""
    s = str(p)
    if _SYS == 'Windows' and len(s) > 240 and not s.startswith('\\\\?\\'):
        s = os.path.abspath(s)
        s = ('\\\\?\\UNC\\' + s[2:]) if s.startswith('\\\\') else ('\\\\?\\' + s)
    return s


_FATAL_WINERRORS = {112, 39, 21, 15}      # disk full ×2, device not ready, invalid drive
_FATAL_ERRNOS = {28}                       # ENOSPC


def friendly_os_error(e: BaseException) -> str:
    """Plain-English explanation for the OS errors people actually hit."""
    win = getattr(e, 'winerror', None)
    no = getattr(e, 'errno', None)
    if no == 28 or win in (112, 39):
        return 'The drive is full'
    if no == 27 or win == 223:
        return 'File is larger than 4 GB, which this drive (FAT32) cannot store; reformat it as exFAT or NTFS'
    if no in (13, 1) or win in (5, 32, 33):
        return ('The file is open in another program or you don\'t have permission'
                if win in (32, 33) else 'Permission denied')
    if no == 2 or win in (2, 3):
        return 'File or folder not found (was the drive disconnected?)'
    if win in (21, 15) or no == 19:
        return 'The drive is not ready or was disconnected'
    if no == 36 or win == 206:
        return 'The file path is too long'
    if no == 5 or win in (23, 1117):
        return 'Read error (the file or drive may be damaged)'
    return f'{getattr(e, "strerror", None) or e}'


def is_fatal_os_error(e: BaseException) -> bool:
    return getattr(e, 'errno', None) in _FATAL_ERRNOS or getattr(e, 'winerror', None) in _FATAL_WINERRORS


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except (ValueError, OSError):
        return False


def _norm_key(p: Path) -> str:
    return os.path.normcase(os.path.normpath(str(p)))


def is_junk_name(name: str) -> bool:
    """macOS AppleDouble files (._x.jpg), Android trash, our temp files."""
    return name.startswith(_JUNK_PREFIXES) or '_exiftool_tmp' in name


def iter_media(root: Path, exclude: Sequence[Path] = (),
               errors: Optional[List[str]] = None,
               ignored: Optional[Dict[str, int]] = None) -> List[Path]:
    """All media files under root (sorted), skipping our own working dirs,
    trash/thumbnail folders and junk files. Folders that can't be read are
    appended to *errors* (when given) instead of being silently skipped."""
    excl = [_norm_key(e.resolve()) for e in exclude if e]
    out: List[Path] = []

    def onerror(e: OSError) -> None:
        if errors is not None:
            errors.append(f'{getattr(e, "filename", "") or ""}: {e.strerror or e}')

    for dirpath, dirnames, filenames in os.walk(root, onerror=onerror):
        dp = Path(dirpath)
        keep = []
        for d in sorted(dirnames):
            if d.lower() in _SKIP_DIRS or d.lower() == DUPES_DIR:
                continue
            sub = dp / d
            if excl and _norm_key(sub.resolve()) in excl:
                continue
            keep.append(d)
        dirnames[:] = keep
        for f in sorted(filenames):
            if is_junk_name(f):
                continue
            ext = os.path.splitext(f)[1].lower()
            if ext in ALL_MEDIA:
                out.append(dp / f)
            elif ignored is not None and ext != '.json' and f.lower() not in _SYSTEM_FILES:
                ignored[ext or '(no extension)'] = ignored.get(ext or '(no extension)', 0) + 1
    return out


def count_folder(root: Path) -> dict:
    """Fast counts for the folder hints, using exactly the same rules as
    iter_media / analyze_folder (skip-dirs, junk, system files)."""
    out = {'count': 0, 'size': 0, 'sidecars': 0, 'archives': 0, 'other': {}}
    for dirpath, dirnames, filenames in os.walk(root, onerror=lambda e: None):
        dirnames[:] = [d for d in dirnames if d.lower() not in _SKIP_DIRS and d.lower() != DUPES_DIR]
        for f in filenames:
            low = f.lower()
            if is_junk_name(f):
                continue
            ext = os.path.splitext(low)[1]
            if ext in ALL_MEDIA:
                out['count'] += 1
                try:
                    out['size'] += os.path.getsize(os.path.join(dirpath, f))
                except OSError:
                    pass
            elif ext == '.json':
                out['sidecars'] += low not in _NON_SIDECAR_JSON
            elif is_archive(f):
                out['archives'] += 1
            elif low not in _SYSTEM_FILES:
                out['other'][ext or '(no extension)'] = out['other'].get(ext or '(no extension)', 0) + 1
    return out


def describe_ignored(ignored: Dict[str, int], limit: int = 6) -> str:
    """'.pdf ×12, .txt ×3, … ' for log lines."""
    parts = [f'{ext} ×{n:,}' for ext, n in sorted(ignored.items(), key=lambda kv: -kv[1])[:limit]]
    if len(ignored) > limit:
        parts.append('…')
    return ', '.join(parts)


_HASH_CHUNK = 1 << 16


def quick_fingerprint(path: Path) -> str:
    """size + hash of the first and last 64 KB — cheap identity check."""
    size = os.path.getsize(_fs(path))
    h = hashlib.blake2b(digest_size=16)
    with open(_fs(path), 'rb') as f:
        h.update(f.read(_HASH_CHUNK))
        if size > 2 * _HASH_CHUNK:
            f.seek(-_HASH_CHUNK, os.SEEK_END)
            h.update(f.read(_HASH_CHUNK))
    return f'{size}:{h.hexdigest()}'


def full_hash(path: Path) -> str:
    h = hashlib.blake2b(digest_size=20)
    with open(_fs(path), 'rb') as f:
        while True:
            chunk = f.read(1 << 20)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def _file_hash(path: Path) -> str:
    """Back-compat helper; returns '' when the file can't be read."""
    try:
        return full_hash(path)
    except OSError:
        return ''


def _fmt_size(n: int) -> str:
    v = float(n)
    for unit in ('B', 'KB', 'MB', 'GB', 'TB'):
        if v < 1024 or unit == 'TB':
            return f'{n} B' if unit == 'B' else f'{v:.1f} {unit}'
        v /= 1024
    return f'{n} B'


def _brief_date(path: Path) -> str:
    pd = date_from_filename(path.name)
    return pd.dt.strftime('%Y-%m-%d') if pd else ''


def _unique_path(path: Path, taken: Callable[[Path], bool]) -> Path:
    if not taken(path):
        return path
    for i in range(1, 100000):
        cand = path.with_name(f'{path.stem}_{i}{path.suffix}')
        if not taken(cand):
            return cand
    raise OSError(f'No free name for {path}')


_TYPE_EXT = {'JPEG': '.jpg', 'JPG': '.jpg', 'PNG': '.png', 'GIF': '.gif', 'TIFF': '.tif', 'HEIC': '.heic',
             'HEIF': '.heic', 'WEBP': '.webp', 'MP4': '.mp4', 'MOV': '.mov', 'AVIF': '.avif', 'BMP': '.bmp',
             'M4V': '.m4v', '3GP': '.3gp'}


def _real_extension(exiftool_msg: str) -> Optional[str]:
    """'Not a valid PNG (looks more like a JPEG)' → '.jpg' (None if not that error)."""
    m = re.search(r'looks more like an? (\w+)', exiftool_msg or '')
    ext = _TYPE_EXT.get(m.group(1).upper()) if m else None
    return ext if ext and ext not in NON_WRITABLE_EXTS else None


def _set_file_times(path: Path, pd: PhotoDate) -> None:
    ts = pd.timestamp()
    try:
        os.utime(_fs(path), (ts, ts))
    except (OSError, OverflowError, ValueError):
        pass


# ── Google Takeout sidecars ───────────────────────────────────────────────────

_EDITED_SUFFIXES = (
    '-edited', '-bearbeitet', '-modifié', '-modificato', '-editado',
    '-bewerkt', '-redigeret', '-redigert', '-redigerad', '-muokattu',
    '-edytowane', '-upravené', '-编辑', '-編集済み', '-편집됨',
)
_NON_SIDECAR_JSON = {
    'metadata.json', 'print-subscriptions.json', 'shared_album_comments.json',
    'user-generated-memory-titles.json',
}
_SUPPLEMENTAL = 'supplemental-metadata'
_NUM_SUFFIX = re.compile(r'^(.*)\((\d+)\)$')
_TRUNCATE_AT = 46     # Google truncates sidecar names to 46 chars + '.json'


def _split_name(file_name: str) -> Tuple[str, str]:
    dot = file_name.rfind('.')
    return (file_name[:dot], file_name[dot:]) if dot > 0 else (file_name, '')


def json_candidate_names(file_name: str) -> List[str]:
    """Exact sidecar names to try, in priority order (mirrors
    TakeoutProcessor.jsonCandidateNames in the Android app, plus extra
    edited-suffix languages)."""
    stem, ext = _split_name(file_name)
    out = [f'{stem}{ext}.json', f'{stem}.json']
    full = f'{stem}{ext}'
    if len(full) > _TRUNCATE_AT:
        out.append(f'{full[:_TRUNCATE_AT]}.json')
    m = _NUM_SUFFIX.match(stem)
    if m:
        base, num = m.group(1), m.group(2)
        out += [f'{base}{ext}({num}).json', f'{base}({num}).json',
                f'{base}{ext}.{_SUPPLEMENTAL}({num}).json']
    low = stem.lower()
    for suf in _EDITED_SUFFIXES:
        if low.endswith(suf):
            orig = stem[:-len(suf)]
            out += [f'{orig}{ext}.json', f'{orig}.json',
                    f'{orig}{ext}.{_SUPPLEMENTAL}.json']
            break
    out += [f'{stem}{ext}.{_SUPPLEMENTAL}.json', f'{stem}.{_SUPPLEMENTAL}.json']
    return out


class SidecarIndex:
    """Caches each directory's JSON files so sidecar lookup costs one
    directory listing instead of a dozen exists() calls per photo.

    Lookup order: exact candidate names → truncated/abbreviated
    'supplemental-metadata' variants → Live-Photo partner (IMG_1.MP4 uses
    IMG_1.HEIC.json) → the JSON 'title' field.
    """

    def __init__(self):
        self._dirs: Dict[str, Dict[str, Path]] = {}
        self._titles: Dict[str, Dict[str, Path]] = {}
        self._lock = threading.Lock()

    def _listing(self, d: Path) -> Dict[str, Path]:
        key = _norm_key(d)
        with self._lock:
            cached = self._dirs.get(key)
        if cached is not None:
            return cached
        names: Dict[str, Path] = {}
        try:
            with os.scandir(_fs(d)) as it:
                for e in it:
                    n = e.name
                    if n.lower().endswith('.json') and n.lower() not in _NON_SIDECAR_JSON:
                        names[n.lower()] = d / n
        except OSError:
            pass
        with self._lock:
            self._dirs[key] = names
        return names

    def _title_map(self, d: Path) -> Dict[str, Path]:
        key = _norm_key(d)
        with self._lock:
            cached = self._titles.get(key)
        if cached is not None:
            return cached
        titles: Dict[str, Path] = {}
        for p in self._listing(d).values():
            try:
                data = json.loads(Path(_fs(p)).read_text(encoding='utf-8'))
                t = data.get('title') if isinstance(data, dict) else None
                if isinstance(t, str) and t:
                    titles.setdefault(t.lower(), p)
            except (OSError, ValueError):
                continue
        with self._lock:
            self._titles[key] = titles
        return titles

    def find(self, media: Path) -> Optional[Path]:
        names = self._listing(media.parent)
        if not names:
            return None
        for cand in json_candidate_names(media.name):
            p = names.get(cand.lower())
            if p is not None:
                return p

        stem, ext = _split_name(media.name)
        m = _NUM_SUFFIX.match(stem)
        base, num = (m.group(1), m.group(2)) if m else (stem, None)
        target = f'{base}{ext}.{_SUPPLEMENTAL}'.lower()
        min_len = len(base) + len(ext) + 1
        for jname, p in names.items():
            jstem = jname[:-5]
            jm = _NUM_SUFFIX.match(jstem)
            jnum = jm.group(2) if jm else None
            if jm:
                jstem = jm.group(1)
            if jnum != num or len(jstem) < min(min_len, _TRUNCATE_AT):
                continue
            if target.startswith(jstem) and (len(jstem) >= min_len or len(jstem) >= _TRUNCATE_AT):
                return p

        if ext.lower() in VIDEO_EXTS:
            prefix = f'{stem}.'.lower()
            for jname, p in names.items():
                if jname.startswith(prefix):
                    other = '.' + jname[len(prefix):].split('.', 1)[0]
                    if other in IMAGE_EXTS:
                        return p

        titles = self._title_map(media.parent)
        hit = titles.get(media.name.lower())
        if hit is not None:
            return hit
        low = stem.lower()
        for suf in _EDITED_SUFFIXES:
            if low.endswith(suf):
                return titles.get(f'{stem[:-len(suf)]}{ext}'.lower())
        return None


def find_json(media: Path) -> Optional[Path]:
    """Return the Google Takeout JSON sidecar for *media*, or None."""
    return SidecarIndex().find(media)


def parse_meta(json_path: Path) -> Meta:
    """Extract date/GPS/description/people from a Takeout JSON sidecar."""
    meta = Meta()
    try:
        data = json.loads(Path(_fs(json_path)).read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return meta
    if not isinstance(data, dict):
        return meta

    for key in ('photoTakenTime', 'creationTime'):
        t = data.get(key)
        if isinstance(t, dict) and 'timestamp' in t:
            try:
                ts = int(t['timestamp'])
            except (TypeError, ValueError):
                continue
            if 0 < ts < 4102444800:          # before 2100
                meta.timestamp = ts
                meta.timestamp_source = key
                break

    for key in ('geoDataExif', 'geoData'):
        geo = data.get(key)
        if isinstance(geo, dict):
            try:
                lat = float(geo.get('latitude', 0) or 0)
                lon = float(geo.get('longitude', 0) or 0)
                alt = float(geo.get('altitude', 0) or 0)
            except (TypeError, ValueError):
                continue
            if (lat != 0.0 or lon != 0.0) and abs(lat) <= 90 and abs(lon) <= 180:
                meta.latitude, meta.longitude, meta.altitude = lat, lon, alt
                break

    desc = data.get('description')
    meta.description = desc.strip() if isinstance(desc, str) else ''
    title = data.get('title')
    meta.title = title if isinstance(title, str) else ''
    people = data.get('people')
    if isinstance(people, list):
        meta.people = [p['name'].strip() for p in people
                       if isinstance(p, dict) and isinstance(p.get('name'), str) and p['name'].strip()]
    meta.favorited = data.get('favorited') is True
    return meta


# ── ExifTool ──────────────────────────────────────────────────────────────────

def find_exiftool() -> Optional[str]:
    """ExifTool executable: $PHOTOFIX_EXIFTOOL, PATH, then ./tools/."""
    env = os.environ.get('PHOTOFIX_EXIFTOOL')
    if env and Path(env).is_file():
        return env
    found = shutil.which('exiftool')
    if found:
        return found
    for name in ('exiftool.exe', 'exiftool'):
        p = APP_ROOT / 'tools' / name
        if p.is_file():
            return str(p)
    return None


def _popen_kwargs() -> dict:
    kw: dict = {}
    if _SYS == 'Windows':
        kw['creationflags'] = getattr(subprocess, 'CREATE_NO_WINDOW', 0)
    return kw


def _check_exiftool() -> Optional[str]:
    """Return the ExifTool version string, or None if not found."""
    exe = find_exiftool()
    if not exe:
        return None
    try:
        r = subprocess.run([exe, '-ver'], capture_output=True, timeout=20,
                           encoding='utf-8', errors='replace', **_popen_kwargs())
        if r.returncode == 0:
            return r.stdout.strip()
    except (OSError, subprocess.TimeoutExpired):
        pass
    return None


class ExifToolError(Exception):
    pass


def _common_args() -> List[str]:
    args = ['-api', 'LargeFileSupport=1']
    if _SYS == 'Windows':
        args += ['-charset', 'filename=UTF8', '-api', 'WindowsLongPath=1']
    return args


class ExifTool:
    """One long-running `exiftool -stay_open` process.

    Spawning ExifTool (a Perl program) per file costs ~0.2–0.5 s on
    Windows; keeping one process open makes per-file work a few ms.
    Falls back to one process per call if stay_open can't start.
    Not thread-safe — use one instance per worker thread (ExifToolPool).
    """

    def __init__(self, exe: Optional[str] = None, timeout: float = 180.0,
                 batch: bool = True):
        self.exe = exe or find_exiftool()
        if not self.exe:
            raise ExifToolError('ExifTool not found')
        self.timeout = timeout
        self.batch = batch
        self._proc: Optional[subprocess.Popen] = None
        self._n = 0
        self._out: 'queue.Queue[Optional[str]]' = queue.Queue()
        self._err: 'queue.Queue[Optional[str]]' = queue.Queue()
        if batch:
            try:
                self._start()
            except OSError:
                self.batch = False

    # -- process management --------------------------------------------------

    def _start(self) -> None:
        self._out = queue.Queue()
        self._err = queue.Queue()
        self._proc = subprocess.Popen(
            [self.exe, '-stay_open', 'True', '-@', '-', '-common_args', *_common_args()],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            **_popen_kwargs(),
        )
        for stream, q in ((self._proc.stdout, self._out), (self._proc.stderr, self._err)):
            threading.Thread(target=self._pump, args=(stream, q), daemon=True).start()

    @staticmethod
    def _pump(stream, q: 'queue.Queue[Optional[str]]') -> None:
        try:
            for raw in iter(stream.readline, b''):
                q.put(raw.decode('utf-8', 'replace').rstrip('\r\n'))
        except (OSError, ValueError):
            pass
        q.put(None)

    def _kill(self) -> None:
        if self._proc is not None:
            try:
                self._proc.kill()
                self._proc.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                pass
        self._proc = None

    def close(self) -> None:
        if self._proc is None:
            return
        try:
            self._proc.stdin.write(b'-stay_open\nFalse\n')
            self._proc.stdin.flush()
            self._proc.wait(timeout=10)
        except (OSError, ValueError, subprocess.TimeoutExpired):
            self._kill()
        self._proc = None

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    # -- execution -----------------------------------------------------------

    def _collect(self, q: 'queue.Queue[Optional[str]]', marker: str, deadline: float) -> List[str]:
        lines: List[str] = []
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ExifToolError('ExifTool timed out (file may be very large or damaged)')
            try:
                line = q.get(timeout=remaining)
            except queue.Empty:
                continue
            if line is None:
                raise ExifToolError('ExifTool exited unexpectedly')
            if line.strip() == marker:
                return lines
            lines.append(line)

    def execute(self, args: Sequence[str]) -> Tuple[str, str]:
        """Run one ExifTool command; returns (stdout, stderr)."""
        for a in args:
            if '\n' in a or '\r' in a:
                raise ExifToolError('argument contains a line break')
        if not self.batch:
            try:
                r = subprocess.run([self.exe, *_common_args(), *args], capture_output=True,
                                   timeout=self.timeout, **_popen_kwargs())
            except subprocess.TimeoutExpired:
                raise ExifToolError('ExifTool timed out (file may be very large or damaged)')
            return (r.stdout.decode('utf-8', 'replace'), r.stderr.decode('utf-8', 'replace'))

        if self._proc is None or self._proc.poll() is not None:
            self._start()
        self._n += 1
        marker = f'{{ready{self._n}}}'
        payload = '\n'.join([*args, '-echo4', marker, f'-execute{self._n}']) + '\n'
        try:
            self._proc.stdin.write(payload.encode('utf-8'))
            self._proc.stdin.flush()
            deadline = time.monotonic() + self.timeout
            out = self._collect(self._out, marker, deadline)
            err = self._collect(self._err, marker, deadline)
        except (OSError, ValueError, ExifToolError) as e:
            self._kill()      # next call restarts a fresh process
            raise e if isinstance(e, ExifToolError) else ExifToolError(str(e))
        return '\n'.join(out), '\n'.join(err)

    def write(self, path: Path, tag_args: Sequence[str]) -> Tuple[bool, str]:
        """Write tags in place. Returns (ok, message)."""
        out, err = self.execute(['-overwrite_original', '-m', *tag_args, str(path)])
        errors = [ln for ln in err.splitlines() if ln.lower().startswith('error')]
        if errors:
            return False, errors[0][:200]
        if re.search(r'\b[1-9]\d* image files? (updated|unchanged)', out):
            return True, ''
        msg = (err or out).strip().splitlines()
        return False, (msg[0][:200] if msg else 'ExifTool did not update the file')

    def read_tags(self, paths: Sequence[Path], tags: Sequence[str]) -> Dict[str, dict]:
        """Read tags for many files at once; keyed by _norm_key(path)."""
        if not paths:
            return {}
        out, _ = self.execute(['-j', '-q', '-q', *[f'-{t}' for t in tags],
                               *[str(p) for p in paths]])
        result: Dict[str, dict] = {}
        try:
            data = json.loads(out) if out.strip() else []
        except ValueError:
            return result
        for item in data:
            src = item.get('SourceFile')
            if src:
                result[_norm_key(Path(src))] = item
        return result


_DATE_TAGS = ('DateTimeOriginal', 'CreateDate', 'MediaCreateDate', 'OffsetTimeOriginal')


class ExifToolPool:
    """Hands each worker thread its own ExifTool process."""

    def __init__(self, exe: Optional[str] = None):
        self.exe = exe or find_exiftool()
        self._local = threading.local()
        self._all: List[ExifTool] = []
        self._lock = threading.Lock()

    def get(self) -> ExifTool:
        et = getattr(self._local, 'et', None)
        if et is None:
            et = ExifTool(self.exe)
            self._local.et = et
            with self._lock:
                self._all.append(et)
        return et

    def close(self) -> None:
        with self._lock:
            for et in self._all:
                et.close()
            self._all.clear()


def _esc(value: str) -> str:
    """Escape a value for ExifTool's -ec option (keeps newlines intact)."""
    return value.replace('\\', '\\\\').replace('\r\n', '\n').replace('\r', '\n').replace('\n', '\\n')


def build_tag_args(is_video: bool, date: Optional[PhotoDate], meta: Optional[Meta] = None,
                   *, write_gps: bool = True, write_extras: bool = True) -> List[str]:
    """ExifTool tag arguments (no file name) for one file."""
    args: List[str] = ['-ec']
    if date is not None:
        offset = date.offset or '+00:00'
        if is_video:
            utc = date.utc.strftime(_EXIF_FMT)            # QuickTime dates are UTC
            for tag in ('CreateDate', 'ModifyDate', 'TrackCreateDate', 'TrackModifyDate',
                        'MediaCreateDate', 'MediaModifyDate'):
                args.append(f'-{tag}={utc}')
            args.append(f'-Keys:CreationDate={date.exif}{offset}')
        else:
            for tag in ('DateTimeOriginal', 'CreateDate', 'ModifyDate'):
                args.append(f'-{tag}={date.exif}')
            args += [f'-OffsetTimeOriginal={offset}', f'-OffsetTime={offset}',
                     f'-OffsetTimeDigitized={offset}']
        if _SYS == 'Windows':
            args.append(f'-FileCreateDate={date.exif}{offset}')

    if meta is not None and write_gps and meta.latitude is not None and meta.longitude is not None:
        lat, lon, alt = meta.latitude, meta.longitude, meta.altitude
        if is_video:
            coords = f'{lat}, {lon}' + (f', {alt}' if alt is not None else '')
            args += [f'-Keys:GPSCoordinates={coords}', f'-UserData:GPSCoordinates={coords}']
        else:
            args += [
                f'-GPSLatitude={abs(lat)}', f'-GPSLatitudeRef={"N" if lat >= 0 else "S"}',
                f'-GPSLongitude={abs(lon)}', f'-GPSLongitudeRef={"E" if lon >= 0 else "W"}',
            ]
            if alt is not None:
                args += [f'-GPSAltitude={abs(alt)}', f'-GPSAltitudeRef={"0" if alt >= 0 else "1"}']
            if date is not None:
                args += [f'-GPSDateStamp={date.utc:%Y:%m:%d}', f'-GPSTimeStamp={date.utc:%H:%M:%S}']

    if meta is not None and write_extras:
        if meta.description:
            d = _esc(meta.description)
            if is_video:
                args.append(f'-Keys:Description={d}')
            else:
                args += [f'-Description={d}', f'-ImageDescription={d}']
        if not is_video:
            for person in meta.people:
                args.append(f'-XMP-iptcExt:PersonInImage={_esc(person)}')
            if meta.favorited:
                args.append('-XMP:Rating=5')
    return args


def build_exiftool_args(target: Path, meta: Meta) -> List[str]:
    """Back-compat: full one-shot command line for a Takeout file."""
    is_video = target.suffix.lower() in VIDEO_EXTS
    date = PhotoDate.from_unix(meta.timestamp, 'sidecar') if meta.timestamp else None
    return ['exiftool', '-overwrite_original', '-m', *_common_args(),
            *build_tag_args(is_video, date, meta), str(target)]


# ── Reports & manifest ────────────────────────────────────────────────────────

def write_report(folder: Path, prefix: str, summary: dict, records: List[dict],
                 columns: Sequence[str]) -> Optional[Path]:
    """Write <folder>/_photofix/<prefix>-<time>.json and .csv; returns the JSON path."""
    try:
        out = folder / APP_DIR
        out.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime('%Y%m%d-%H%M%S')
        jpath = out / f'{prefix}-{stamp}.json'
        jpath.write_text(json.dumps({
            'generated': datetime.now(tz=timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
            'summary': summary,
            'records': records,
        }, indent=2, ensure_ascii=False), encoding='utf-8')
        with open(jpath.with_suffix('.csv'), 'w', newline='', encoding='utf-8-sig') as f:
            w = csv.DictWriter(f, fieldnames=list(columns), extrasaction='ignore')
            w.writeheader()
            for r in records:
                w.writerow(r)
        return jpath
    except OSError:
        return None


class Manifest:
    """Remembers which source file produced each output file, so re-runs
    skip work that is already done instead of making photo_1.jpg copies
    (ExifTool changes the bytes, so the output can't be compared with
    the source directly)."""

    def __init__(self, root: Path, enabled: bool = True):
        self.path = root / APP_DIR / 'manifest.json'
        self.enabled = enabled
        self._data: Dict[str, str] = {}
        self._dirty = 0
        self._lock = threading.Lock()
        if enabled:
            try:
                raw = json.loads(self.path.read_text(encoding='utf-8'))
                if isinstance(raw, dict) and isinstance(raw.get('files'), dict):
                    self._data = {str(k): str(v) for k, v in raw['files'].items()}
            except (OSError, ValueError):
                pass

    @staticmethod
    def key(rel: Path) -> str:
        k = rel.as_posix()
        return k.lower() if _SYS == 'Windows' else k

    def get(self, rel: Path) -> Optional[str]:
        with self._lock:
            return self._data.get(self.key(rel))

    def put(self, rel: Path, fp: str) -> None:
        if not self.enabled:
            return
        with self._lock:
            self._data[self.key(rel)] = fp
            self._dirty += 1
            flush = self._dirty >= 250
        if flush:
            self.save()

    def save(self) -> None:
        if not self.enabled:
            return
        with self._lock:
            if not self._dirty and self.path.exists():
                return
            snapshot = dict(self._data)
            self._dirty = 0
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix('.tmp')
            tmp.write_text(json.dumps({'version': 1, 'files': snapshot}), encoding='utf-8')
            os.replace(tmp, self.path)
        except OSError:
            pass


# ── Job base ──────────────────────────────────────────────────────────────────

def _noop(*_a, **_k):
    return None


class Job:
    """Common plumbing: callbacks, stop/pause, throttled progress, workers."""

    kind = 'job'

    def __init__(self, on_log=None, on_progress=None, on_stats=None, on_done=None,
                 on_file_result=None, on_event=None, workers: int = 0):
        self.on_log = on_log or _noop
        self.on_progress = on_progress or _noop
        self.on_stats = on_stats or _noop
        self.on_done = on_done or _noop
        self.on_file_result = on_file_result
        self.on_event = on_event or _noop
        self.workers = workers if workers > 0 else max(1, min(4, (os.cpu_count() or 2)))
        self.records: List[FileRecord] = []
        self.report_path: Optional[Path] = None
        self._stop = threading.Event()
        self._running = threading.Event()
        self._running.set()
        self._lock = threading.Lock()
        self._last_emit = 0.0
        self._t0 = time.monotonic()
        self.stats = Stats()

    # control
    def stop(self):
        self._stop.set()
        self._running.set()

    def pause(self):
        self._running.clear()

    def resume(self):
        self._running.set()

    @property
    def paused(self) -> bool:
        return not self._running.is_set()

    @property
    def stopped(self) -> bool:
        return self._stop.is_set()

    def _wait_if_paused(self):
        while not self._running.wait(0.5):
            pass

    # emitters
    def _record(self, rec: FileRecord) -> None:
        with self._lock:
            self.records.append(rec)
        if self.on_file_result:
            self.on_file_result(rec.as_dict())

    def _progress(self, cur: int, total: int, name: str, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self._last_emit < 0.1:
            return
        self._last_emit = now
        elapsed = now - self._t0
        fps = cur / elapsed if elapsed > 0.5 else 0.0
        self.on_progress(cur, total, name, fps)
        self.on_stats(self.stats)

    def run(self) -> bool:
        self._t0 = time.monotonic()
        ok = False
        try:
            ok = bool(self._run())
        except Exception as exc:          # never leave the UI hanging
            self.on_log('error', f'Unexpected error: {type(exc).__name__}: {exc}')
            ok = False
        finally:
            self.on_stats(self.stats)
            self.on_done(ok)
        return ok

    def _run(self) -> bool:
        raise NotImplementedError

    def _run_parallel(self, items: Sequence, fn: Callable, label: Callable[[object], str],
                      weight: Callable[[object], int] = lambda _i: 1) -> int:
        """Run fn(item) on worker threads; returns progress units completed."""
        total = sum(weight(i) for i in items)
        done = 0
        it = iter(items)
        inflight = set()
        with ThreadPoolExecutor(max_workers=self.workers) as pool:
            while True:
                while not self._stop.is_set() and len(inflight) < self.workers * 2:
                    self._wait_if_paused()
                    if self._stop.is_set():
                        break
                    try:
                        item = next(it)
                    except StopIteration:
                        break
                    fut = pool.submit(fn, item)
                    fut.label = label(item)        # type: ignore[attr-defined]
                    fut.weight = weight(item)      # type: ignore[attr-defined]
                    inflight.add(fut)
                if not inflight:
                    break
                finished, inflight = wait(inflight, timeout=0.5, return_when=FIRST_COMPLETED)
                for fut in finished:
                    done += fut.weight             # type: ignore[attr-defined]
                    exc = fut.exception()
                    if exc is not None:
                        self.on_log('error', f'  ✗ {fut.label}: {exc}')   # type: ignore[attr-defined]
                    self._progress(done, total, fut.label)                 # type: ignore[attr-defined]
        self._progress(done, total, '', force=True)
        return done


# ── Processor (Takeout / sort by filename date) ──────────────────────────────

@dataclass
class ProcessOptions:
    output_mode: str = 'date'          # date | date_numeric | preserve | flat
    use_sidecars: bool = True          # False → "sort by filename date" tool
    keep_existing_dates: bool = True   # never overwrite a date already in the file
    use_embedded_dates: bool = True    # no sidecar/filename date → sort by the file's own date
    copy_undated: bool = True          # copy undated files to no-date/
    write_gps: bool = True
    write_extras: bool = True          # description, people, favourite → rating
    set_file_times: bool = True        # file modified time = taken time
    filename_tz: str = 'local'         # 'local' | 'utc' for times found in filenames
    dry_run: bool = False
    skip_existing: bool = True         # skip files already in the output (re-runs)
    kinds: str = 'all'                 # 'all' | 'photos' | 'videos'
    date_from: str = ''                # 'YYYY-MM-DD' — only files taken on/after this day
    date_to: str = ''                  # 'YYYY-MM-DD' — only files taken on/before this day
    rename_to_date: bool = False       # name output files 2024-03-15_14-30-22.jpg


def _parse_day(s: str) -> Optional[datetime]:
    try:
        return datetime.strptime((s or '').strip(), '%Y-%m-%d') if s and s.strip() else None
    except ValueError:
        return None


class Processor(Job):
    """Copies media from src to dst, restoring dates/GPS.

    Record statuses: fixed | from_filename | kept_existing | no_date |
    no_date_skipped | unsupported | exists | duplicate | meta_failed | error |
    filtered.
    """

    kind = 'takeout'

    def __init__(self, src: str, dst: str,
                 copy_unmatched: bool = True,
                 output_mode: str = 'date',
                 on_log=None, on_progress=None, on_stats=None, on_done=None,
                 skip_files: frozenset = frozenset(),
                 on_file_result=None,
                 options: Optional[ProcessOptions] = None,
                 workers: int = 0, on_event=None):
        super().__init__(on_log, on_progress, on_stats, on_done, on_file_result, on_event, workers)
        self.src = Path(os.path.abspath(src))
        self.dst = Path(os.path.abspath(dst))
        self.fatal = ''                          # set when the run must stop (disk full, drive gone)
        self.opts = options or ProcessOptions(output_mode=output_mode, copy_undated=copy_unmatched)
        if self.opts.output_mode not in OUTPUT_MODES:
            self.opts.output_mode = 'date'
        if not self.opts.use_sidecars:
            self.kind = 'filename'
        self.skip_files = frozenset(_norm_key(Path(p)) for p in skip_files)
        self._date_from = _parse_day(self.opts.date_from)
        self._date_to = _parse_day(self.opts.date_to)
        self.sidecars = SidecarIndex()
        self.pool: Optional[ExifToolPool] = None
        self.manifest: Optional[Manifest] = None
        self._claims: Dict[str, Path] = {}       # dest key → source
        self._fp_cache: Dict[str, str] = {}
        self._dir_fps: Dict[Tuple[str, str], Path] = {}   # (dest dir, fingerprint) → source
        self._done_fps: Dict[Tuple[str, str], str] = {}  # (dest dir, fingerprint) → path, earlier runs
        self._nodate_fps: Dict[str, str] = {}               # fingerprint → path under no-date/

    # -- helpers -------------------------------------------------------------

    def _fp(self, p: Path) -> str:
        k = _norm_key(p)
        with self._lock:
            v = self._fp_cache.get(k)
        if v is None:
            v = quick_fingerprint(p)
            with self._lock:
                self._fp_cache[k] = v
        return v

    def _dest_dir(self, src: Path, date: Optional[PhotoDate]) -> Path:
        mode = self.opts.output_mode
        if mode == 'preserve':
            return self.dst / src.parent.relative_to(self.src)
        if mode == 'flat':
            return self.dst
        if date is None:
            # Keep the original sub-folders so undated files keep their context
            # (no-date/Holiday 2015/IMG_123.jpg rather than one giant pile).
            return self.dst / NO_DATE_DIR / src.parent.relative_to(self.src)
        return self.dst / date_subdir(date.dt, numeric=(mode == 'date_numeric'))

    def _claim(self, src: Path, desired: Path) -> Tuple[Optional[Path], str]:
        """Reserve an output path. Returns (path, '') or (None, reason).

        Identical content headed for the same folder is only kept once, even
        under a different name ("copy of IMG_1.jpg"); a different photo that
        happens to share a name gets an _1 suffix instead."""
        fp = self._fp(src)
        dir_key = _norm_key(desired.parent)
        try:
            rel_dir = Manifest.key(desired.parent.relative_to(self.dst))
        except ValueError:
            rel_dir = ''
        if rel_dir == '.':
            rel_dir = ''
        if self.opts.skip_existing:
            done = self._done_fps.get((rel_dir, fp))
            if not done and rel_dir.split('/', 1)[0] == NO_DATE_DIR:
                done = self._nodate_fps.get(fp)      # earlier versions used a flat no-date/
            if done and os.path.lexists(_fs(self.dst / done)):
                return None, 'already in output'
        with self._lock:
            twin = self._dir_fps.get((dir_key, fp))
            if twin is None:
                self._dir_fps[(dir_key, fp)] = src  # first of its content here: claim below
        if twin is not None and _norm_key(twin) != _norm_key(src):
            # Full hashes are read outside the lock so big videos don't stall other workers.
            try:
                if full_hash(twin) == full_hash(src):
                    return None, f'identical to {twin.name}'
            except OSError:
                pass
        with self._lock:
            cand = desired
            for i in range(0, 100000):
                if i:
                    cand = desired.with_name(f'{desired.stem}_{i}{desired.suffix}')
                key = _norm_key(cand)
                owner = self._claims.get(key)
                if owner is not None:
                    if self._fp_cache.get(_norm_key(owner)) == fp:
                        return None, f'duplicate of {owner.name}'
                    continue
                if os.path.lexists(_fs(cand)):
                    if not self.opts.skip_existing:
                        continue
                    rel = cand.relative_to(self.dst)
                    known = self.manifest.get(rel) if self.manifest else None
                    if known == fp:
                        return None, 'already in output'
                    if known is None:
                        try:
                            if os.path.getsize(_fs(cand)) == int(fp.split(':', 1)[0]) \
                                    and quick_fingerprint(cand) == fp:
                                return None, 'already in output'
                        except OSError:
                            pass
                    continue
                self._claims[key] = src
                return cand, ''
        raise OSError(f'No free file name for {desired.name}')

    def _release(self, src: Path, dest: Path) -> None:
        """Undo a claim whose copy failed, so an identical twin can take its place."""
        with self._lock:
            self._claims.pop(_norm_key(dest), None)
            for k, v in list(self._dir_fps.items()):
                if v == src and k[0] == _norm_key(dest.parent):
                    del self._dir_fps[k]

    def _fatal(self, e: BaseException) -> None:
        """Stop the whole run on errors that would fail every remaining file."""
        if not self.fatal:
            self.fatal = friendly_os_error(e)
            if not self.dst.exists():
                self.fatal = 'The output drive or folder is no longer available (was it disconnected?)'
            self.on_log('error', f'Stopping: {self.fatal}. Files done so far are safe; '
                                 'fix the problem and press Start again to continue.')
            self.stop()

    def _copy_to_error(self, src: Path) -> str:
        """Last-resort copy so a problem file still lands in the output."""
        if self.opts.dry_run or self.fatal:
            return ''
        try:
            d = self.dst / ERROR_DIR / src.parent.relative_to(self.src)
            os.makedirs(_fs(d), exist_ok=True)
            with self._lock:
                target = _unique_path(d / src.name,
                                      lambda p: os.path.lexists(_fs(p)) or _norm_key(p) in self._claims)
                self._claims[_norm_key(target)] = src
            shutil.copy2(_fs(src), _fs(target))
            return str(target.relative_to(self.dst))
        except OSError:
            return ''

    def _embedded(self, src: Path, is_video: bool) -> Optional[PhotoDate]:
        try:
            tags = self.pool.get().read_tags([src], _DATE_TAGS)
        except ExifToolError:
            return None
        item = tags.get(_norm_key(src)) or (next(iter(tags.values())) if len(tags) == 1 else None)
        return parse_embedded_date(item, is_video) if item else None

    def _count(self, status: str, nbytes: int = 0) -> None:
        s = self.stats
        with self._lock:
            if status == 'fixed':
                s.processed += 1; s.from_sidecar += 1
            elif status == 'from_filename':
                s.processed += 1; s.from_filename += 1
            elif status == 'kept_existing':
                s.kept_existing += 1
            elif status in ('no_date', 'no_date_skipped'):
                s.no_json += 1
            elif status == 'unsupported':
                s.unsupported += 1
            elif status in ('exists', 'duplicate'):
                s.skipped += 1
            elif status == 'meta_failed':
                s.meta_failed += 1
            elif status == 'error':
                s.errors += 1
            elif status == 'filtered':
                s.filtered += 1
            s.bytes_copied += nbytes

    # -- per file --------------------------------------------------------------

    def _safe_process(self, src: Path) -> None:
        if self._stop.is_set():
            return
        try:
            self._process_one(src)
        except Exception as e:             # one bad file must not stop the run
            if isinstance(e, OSError) and is_fatal_os_error(e):
                self._fatal(e)
            rel = str(src.relative_to(self.src))
            msg = friendly_os_error(e) if isinstance(e, OSError) else f'{type(e).__name__}: {e}'
            err_dest = self._copy_to_error(src)
            self._count('error')
            self._record(FileRecord(rel, 'error', dest=err_dest, error=msg))
            self.on_log('error', f'  ✗ {rel}: {msg}' + (f' (copied to {err_dest})' if err_dest else ''))

    def _fail(self, src: Path, rel_s: str, msg: str) -> None:
        err_dest = self._copy_to_error(src)
        self._count('error')
        self._record(FileRecord(rel_s, 'error', dest=err_dest, error=msg))
        self.on_log('error', f'  ✗ {rel_s}: {msg}' + (f' (copied to {err_dest})' if err_dest else ''))

    def _process_one(self, src: Path) -> None:
        rel = src.relative_to(self.src)
        rel_s = str(rel)
        ext = src.suffix.lower()
        is_video = ext in VIDEO_EXTS
        opts = self.opts

        try:
            size = os.path.getsize(_fs(src))
        except OSError as e:
            self._fail(src, rel_s, f'Cannot read the file: {friendly_os_error(e)}')
            return
        if size == 0:
            self._fail(src, rel_s, 'The file is empty (0 bytes), probably a failed download or copy')
            return

        meta = Meta()
        if opts.use_sidecars:
            jp = self.sidecars.find(src)
            if jp is not None:
                meta = parse_meta(jp)

        # Date priority: photoTakenTime → filename → creationTime (upload date)
        date: Optional[PhotoDate] = None
        if meta.timestamp and meta.timestamp_source == 'photoTakenTime':
            date = PhotoDate.from_unix(meta.timestamp, 'sidecar')
        if date is None:
            date = date_from_filename(src.name, opts.filename_tz)
        if date is None and meta.timestamp:
            date = PhotoDate.from_unix(meta.timestamp, 'sidecar')

        embedded = None
        if opts.keep_existing_dates or (date is None and opts.use_embedded_dates):
            embedded = self._embedded(src, is_video)

        write_mode = 'none'
        if embedded is not None and opts.keep_existing_dates:
            place, write_mode = embedded, 'fill'
        elif date is not None:
            place, write_mode = date, 'full'
        elif embedded is not None:
            place, write_mode = embedded, 'fill'
        else:
            place = None
        if ext in NON_WRITABLE_EXTS:
            write_mode = 'none'

        if self._date_from or self._date_to:
            day = place.dt.replace(hour=0, minute=0, second=0, microsecond=0) if place else None
            if day is None or (self._date_from and day < self._date_from) \
                    or (self._date_to and day > self._date_to):
                self._count('filtered')
                self._record(FileRecord(rel_s, 'filtered', date=place.display() if place else '',
                                        note='outside the chosen date range'))
                self.on_log('file', f'  - {rel_s}: outside date range')
                return

        if place is None and not opts.copy_undated:
            self._count('no_date_skipped')
            self._record(FileRecord(rel_s, 'no_date_skipped', note='no date found; not copied'))
            self.on_log('warn', f'  ! {rel_s}: no date — not copied (copy undated files is off)')
            return

        name = src.name
        if opts.rename_to_date and place is not None:
            name = f'{place.dt:%Y-%m-%d_%H-%M-%S}{ext}'
        dest, reason = self._claim(src, self._dest_dir(src, place) / name)
        if dest is None:
            status = 'duplicate' if reason.startswith(('duplicate', 'identical')) else 'exists'
            self._count(status)
            self._record(FileRecord(rel_s, status, note=reason))
            self.on_log('file', f'  = {rel_s}: skipped ({reason})')
            return
        rel_dst = str(dest.relative_to(self.dst))

        if place is None:
            status = 'no_date'
        elif write_mode == 'full':
            status = 'fixed' if place.source == 'sidecar' else 'from_filename'
        elif ext in NON_WRITABLE_EXTS:
            status = 'unsupported'
        else:
            status = 'kept_existing'
        date_s = place.display() if place else ''
        gps_s = (f'{meta.latitude:.6f}, {meta.longitude:.6f}'
                 if meta.latitude is not None and opts.write_gps else '')

        if opts.dry_run:
            self._count(status)
            self._record(FileRecord(rel_s, status, dest=rel_dst, date=date_s,
                                    source=place.source if place else '', gps=gps_s, note='dry run'))
            self.on_log('file', f'  · {rel_s} → {rel_dst}' + (f'  [{date_s}]' if date_s else '  [no date]'))
            return

        tmp = dest.with_name(_TMP_PREFIX + dest.name)
        try:
            os.makedirs(_fs(dest.parent), exist_ok=True)
            shutil.copy2(_fs(src), _fs(tmp))
        except OSError as e:
            try:
                os.unlink(_fs(tmp))
            except OSError:
                pass
            self._release(src, dest)
            if is_fatal_os_error(e) or not self.dst.exists():
                self._fatal(e)
            self._fail(src, rel_s, f'Copy failed: {friendly_os_error(e)}')
            return

        note = ''
        if write_mode != 'none':
            tag_args = build_tag_args(is_video, place if write_mode == 'full' else None, meta,
                                      write_gps=opts.write_gps, write_extras=opts.write_extras)
            if write_mode == 'fill':
                tag_args = ['-wm', 'cg', *tag_args]      # only add what's missing
            if len(tag_args) > 3 or write_mode == 'full':
                try:
                    ok, msg = self.pool.get().write(tmp, tag_args)
                except ExifToolError as e:
                    ok, msg = False, str(e)
                real_ext = _real_extension(msg) if not ok else None
                if real_ext and real_ext != ext:
                    # e.g. a JPEG saved as .png (common with downloads/screenshots):
                    # give the copy its real extension, then write the date again.
                    new_dest, why = self._claim(src, dest.with_suffix(real_ext))
                    if new_dest is not None:
                        new_tmp = new_dest.with_name(_TMP_PREFIX + new_dest.name)
                        try:
                            os.replace(_fs(tmp), _fs(new_tmp))
                            self._release(src, dest)
                            fp = self._fp(src)             # (takes the lock itself)
                            with self._lock:   # keep the twin index pointing at this file
                                self._dir_fps.setdefault((_norm_key(new_dest.parent), fp), src)
                            tmp, dest = new_tmp, new_dest
                            rel_dst = str(dest.relative_to(self.dst))
                            ok, msg = self.pool.get().write(tmp, tag_args)
                            note = f'file extension corrected from {ext} to {real_ext}'
                        except (OSError, ExifToolError) as e:
                            ok, msg = False, str(e)
                if not ok:
                    status, note = 'meta_failed', f'ExifTool: {msg}'
        if opts.set_file_times and place is not None:
            _set_file_times(tmp, place)
        try:
            os.replace(_fs(tmp), _fs(dest))
        except OSError as e:
            try:
                os.unlink(_fs(tmp))
            except OSError:
                pass
            self._release(src, dest)
            if is_fatal_os_error(e) or not self.dst.exists():
                self._fatal(e)
            self._fail(src, rel_s, f'Could not finish writing the file: {friendly_os_error(e)}')
            return

        if self.manifest:
            self.manifest.put(dest.relative_to(self.dst), self._fp(src))
        try:
            nbytes = os.path.getsize(_fs(dest))
        except OSError:
            nbytes = 0
        self._count(status, nbytes)
        self._record(FileRecord(rel_s, status, dest=rel_dst, date=date_s,
                                source=place.source if place else '', gps=gps_s,
                                note=note, error=note if status == 'meta_failed' else ''))

        icon = {'fixed': '✓', 'from_filename': '✓', 'kept_existing': '✓', 'unsupported': '→',
                'no_date': '!', 'meta_failed': '✗'}.get(status, '·')
        level = {'no_date': 'warn', 'meta_failed': 'error', 'unsupported': 'warn'}.get(status, 'ok')
        detail = {
            'fixed': f'{date_s}' + (f' | GPS {gps_s}' if gps_s else ''),
            'from_filename': f'{date_s} (from filename)',
            'kept_existing': f'kept existing date {date_s}',
            'unsupported': f'{date_s} — format can\'t store metadata, file time set',
            'no_date': 'no date found',
            'meta_failed': note,
        }.get(status, '')
        self.on_log(level, f'  {icon} {rel_s} → {rel_dst}  {detail}')

    # -- main ----------------------------------------------------------------

    def _run(self) -> bool:
        ver = _check_exiftool()
        if ver is None:
            self.on_log('error', 'ExifTool not found. Please install it first:')
            self.on_log('error', '  Windows: run Start.bat (downloads it automatically) or https://exiftool.org')
            self.on_log('error', '  Linux  : sudo apt install libimage-exiftool-perl')
            self.on_log('error', '  macOS  : brew install exiftool')
            return False
        if not self.src.is_dir():
            self.on_log('error', f'Source folder not found: {self.src}')
            return False
        if _norm_key(self.src.resolve()) == _norm_key(self.dst.resolve()):
            self.on_log('error', 'Source and output must be different folders.')
            return False

        if _is_within(self.src, self.dst) and self.opts.output_mode in ('preserve', 'flat'):
            self.on_log('error', 'The source folder is inside the output folder. Choose a different '
                                 'output folder, or use the "By date" layout.')
            return False

        title = 'Organize by date' if not self.opts.use_sidecars else 'Process Google Takeout'
        self.on_log('info', f'{title} — ExifTool v{ver}, {self.workers} worker(s)'
                    + ('  [PREVIEW — nothing will be written]' if self.opts.dry_run else ''))
        self.on_log('info', f'Scanning source: {self.src}')
        self.on_event({'type': 'phase', 'phase': 'scanning'})
        scan_errors: List[str] = []
        ignored: Dict[str, int] = {}
        files = iter_media(self.src, exclude=[self.dst], errors=scan_errors, ignored=ignored)
        for err in scan_errors[:10]:
            self.on_log('warn', f'Could not open folder {err}')
        if len(scan_errors) > 10:
            self.on_log('warn', f'… and {len(scan_errors) - 10} more folders that could not be opened')
        if ignored:
            self.on_log('info', f'Ignoring {sum(ignored.values()):,} file(s) that are not photos or videos: '
                                + describe_ignored(ignored))
        if self.opts.kinds in ('photos', 'videos'):
            keep = IMAGE_EXTS if self.opts.kinds == 'photos' else VIDEO_EXTS
            before = len(files)
            files = [p for p in files if p.suffix.lower() in keep]
            if before - len(files):
                self.on_log('info', f'Only {self.opts.kinds}: ignoring {before - len(files):,} other file(s)')
        if self._date_from or self._date_to:
            self.on_log('info', 'Date range: ' + (f'{self._date_from:%Y-%m-%d}' if self._date_from else '…')
                        + ' → ' + (f'{self._date_to:%Y-%m-%d}' if self._date_to else '…'))
        if self.skip_files:
            before = len(files)
            files = [p for p in files if _norm_key(p) not in self.skip_files]
            if before - len(files):
                self.on_log('warn', f'Excluding {before - len(files)} duplicate file(s) per your review choice.')

        self.stats.total = len(files)
        self.on_log('info', f'Found {len(files):,} media file(s)')
        self.on_stats(self.stats)
        if not files:
            self.on_log('warn', 'No media files found in the source folder.')
            return True

        if not self.opts.dry_run:
            self._check_space(files)
            self.dst.mkdir(parents=True, exist_ok=True)
        self.manifest = Manifest(self.dst, enabled=not self.opts.dry_run)
        if self.opts.skip_existing:
            # Previously written files (any name) by folder + content, so a
            # renamed copy of something already sorted isn't added again.
            m = Manifest(self.dst)
            self._done_fps = {(k.rsplit('/', 1)[0] if '/' in k else '', v): k for k, v in m._data.items()}
            self._nodate_fps = {v: k for k, v in m._data.items() if k.split('/', 1)[0] == NO_DATE_DIR}
        self.pool = ExifToolPool()
        self.on_event({'type': 'phase', 'phase': 'processing'})
        try:
            self._run_parallel(files, self._safe_process, lambda p: str(p.relative_to(self.src)))
        finally:
            self.pool.close()
            self.manifest.save()

        if self.fatal:
            self.on_log('error', f'Run stopped early: {self.fatal}.')
        elif self._stop.is_set():
            self.on_log('warn', 'Stopped by user — files done so far are complete; '
                                'run again to continue (finished files are skipped).')
        self._finish()
        return not self._stop.is_set()

    def _check_space(self, files: List[Path]) -> None:
        try:
            need = sum(os.path.getsize(_fs(p)) for p in files)
            probe = self.dst
            while not probe.exists() and probe.parent != probe:
                probe = probe.parent
            free = shutil.disk_usage(str(probe)).free
            self.on_log('info', f'Source size {_fmt_size(need)}, free space at output {_fmt_size(free)}')
            if need > free:
                self.on_log('warn', '⚠ The output drive may not have enough free space '
                                    '(files already in the output are skipped, so it may still fit).')
        except OSError:
            pass

    def _finish(self) -> None:
        s = self.stats
        summary = {'source': str(self.src), 'output': str(self.dst),
                   'tool': self.kind, 'dry_run': self.opts.dry_run,
                   'options': asdict(self.opts), **asdict(s)}
        self.report_path = write_report(
            self.dst, 'dry-run' if self.opts.dry_run else 'report', summary,
            [r.as_dict() for r in self.records],
            ('file', 'status', 'dest', 'date', 'source', 'gps', 'note', 'error'))
        self.on_log('info', '─' * 55)
        self.on_log('info',
                    f'Finished. Metadata written: {s.processed:,} (sidecar {s.from_sidecar:,}, '
                    f'filename {s.from_filename:,}) | Kept existing: {s.kept_existing:,} | '
                    f'No date: {s.no_json:,} | Skipped: {s.skipped:,} | '
                    f'Unsupported: {s.unsupported:,} | Meta failed: {s.meta_failed:,} | '
                    f'Errors: {s.errors:,}' + (f' | Outside date range: {s.filtered:,}' if s.filtered else ''))
        if self.report_path:
            self.on_log('info', f'Report: {self.report_path}  (+ .csv)')


# ── DriveFixer (fix missing dates in place) ───────────────────────────────────

@dataclass
class DriveFixStats:
    total: int = 0
    fixed: int = 0
    already_dated: int = 0
    mismatched: int = 0      # has a date, but on a different day than its folder
    corrected: int = 0       # mismatched files whose day was rewritten (fix_mismatched)
    unsupported: int = 0
    errors: int = 0


def _list_dir(d: Path, notes: Optional[List[str]] = None) -> Tuple[List[Path], List[Path]]:
    """(sub-folders, files) of *d*, sorted; unreadable folders become a note."""
    dirs: List[Path] = []
    files: List[Path] = []
    try:
        with os.scandir(_fs(d)) as it:
            for e in it:
                try:
                    if e.is_dir(follow_symlinks=False):
                        dirs.append(d / e.name)
                    elif e.is_file():
                        files.append(d / e.name)
                except OSError:
                    continue
    except OSError as e:
        if notes is not None:
            notes.append(f'could not open {d}: {friendly_os_error(e)}')
    return sorted(dirs), sorted(files)


_OWN_DIRS = {APP_DIR.lower(), DUPES_DIR, NO_DATE_DIR, ERROR_DIR}


def _media_in(d: Path, notes: List[str]) -> List[Path]:
    """Media files in *d* and any sub-folders below it (bursts, edits…)."""
    out: List[Path] = []
    dirs, files = _list_dir(d, notes)
    out += [f for f in files if f.suffix.lower() in ALL_MEDIA and not is_junk_name(f.name)]
    for sub in dirs:
        if sub.name.lower() not in _SKIP_DIRS and sub.name.lower() not in _OWN_DIRS:
            out += _media_in(sub, notes)
    return out


def collect_dated_files(root: Path) -> Tuple[List[Tuple[Path, datetime]], List[str]]:
    """Media files in year/month/day folders → (file, folder date).

    Accepts January/January_07, January/January 7 and 01/07 layouts, and a
    year folder selected directly as the root (same as ExifFixer.kt).
    Files in sub-folders of a day folder count for that day; files whose
    day can't be known (loose in a year or month folder) are reported."""
    items: List[Tuple[Path, datetime]] = []
    notes: List[str] = []
    root_year = parse_year_folder(root.name)
    years: List[Tuple[Path, int]] = [(root, root_year)] if root_year else []
    if not years:
        dirs, _ = _list_dir(root, notes)
        for d in dirs:
            y = parse_year_folder(d.name)
            if y:
                years.append((d, y))
            elif d.name.lower() not in _SKIP_DIRS and d.name.lower() not in _OWN_DIRS:
                notes.append(f'skipped folder that is not a year: {d.name}')
    for ydir, year in years:
        mdirs, yfiles = _list_dir(ydir, notes)
        loose = [f for f in yfiles if f.suffix.lower() in ALL_MEDIA and not is_junk_name(f.name)]
        if loose:
            notes.append(f'{len(loose)} file(s) directly in {ydir.name}/ have no month/day folder; left as they are')
        for mdir in mdirs:
            month = parse_month_folder(mdir.name)
            if month is None:
                if mdir.name.lower() not in _SKIP_DIRS and mdir.name.lower() not in _OWN_DIRS:
                    notes.append(f'skipped folder that is not a month: {ydir.name}/{mdir.name}')
                continue
            ddirs, mfiles = _list_dir(mdir, notes)
            loose = [f for f in mfiles if f.suffix.lower() in ALL_MEDIA and not is_junk_name(f.name)]
            if loose:
                notes.append(f'{len(loose)} file(s) directly in {ydir.name}/{mdir.name}/ have no day folder; '
                             'left as they are')
            for ddir in ddirs:
                day = parse_day_folder(ddir.name)
                if day is None:
                    notes.append(f'skipped folder that is not a day: {ydir.name}/{mdir.name}/{ddir.name}')
                    continue
                try:
                    folder_date = datetime(year, month, day, 12, 0, 0)
                except ValueError:
                    notes.append(f'not a real date: {ydir.name}/{mdir.name}/{ddir.name}')
                    continue
                items += [(f, folder_date) for f in _media_in(ddir, notes)]
    return items, notes


class DriveFixer(Job):
    """Writes the folder date into files that have no date (in place).

    Unlike the Android version this also handles HEIC, RAW and video,
    uses the filename's time of day when it matches the folder date, and
    reports files whose existing date disagrees with their folder."""

    kind = 'fixdrive'

    def __init__(self, root: str, *, dry_run: bool = False, set_file_times: bool = True,
                 filename_tz: str = 'local', fix_mismatched: bool = False, **kw):
        super().__init__(**kw)
        self.root = Path(os.path.abspath(root))
        self.dry_run = dry_run
        self.fix_mismatched = fix_mismatched
        self.set_file_times = set_file_times
        self.filename_tz = filename_tz
        self.stats = DriveFixStats()   # type: ignore[assignment]
        self.pool: Optional[ExifToolPool] = None

    def _inc(self, name: str) -> None:
        with self._lock:
            setattr(self.stats, name, getattr(self.stats, name) + 1)

    def _fix_dir(self, batch: Tuple[Path, List[Tuple[Path, datetime]]]) -> None:
        _, items = batch
        et = self.pool.get()
        try:
            tags = et.read_tags([p for p, _ in items], _DATE_TAGS)
        except ExifToolError as e:
            tags = {}
            self.on_log('warn', f'  ! could not read dates in {items[0][0].parent}: {e}')
        for path, folder_dt in items:
            if self._stop.is_set():
                return
            self._wait_if_paused()
            self._fix_one(et, path, folder_dt, tags.get(_norm_key(path)))

    def _fix_one(self, et: ExifTool, path: Path, folder_dt: datetime, tag_item: Optional[dict]) -> None:
        rel = str(path.relative_to(self.root))
        ext = path.suffix.lower()
        is_video = ext in VIDEO_EXTS
        existing = parse_embedded_date(tag_item, is_video) if tag_item else None

        ok_status = 'fixed'
        if existing is not None:
            self._inc('already_dated')
            delta = abs((existing.dt.date() - folder_dt.date()).days)
            if delta <= 1:
                return
            self._inc('mismatched')
            if not self.fix_mismatched:
                self._record(FileRecord(rel, 'mismatched', date=existing.display(),
                                        note=f'folder says {folder_dt:%Y-%m-%d}'))
                self.on_log('warn', f'  ≠ {rel}: has date {existing.dt:%Y-%m-%d}, '
                                    f'folder says {folder_dt:%Y-%m-%d} (left unchanged)')
                return
            # Keep the time of day, move it to the folder's day.
            date = PhotoDate(datetime.combine(folder_dt.date(), existing.dt.time()),
                             existing.offset or '+00:00', 'folder')
            ok_status = 'corrected'
        else:
            date = PhotoDate(folder_dt, '+00:00', 'folder')
            fn = date_from_filename(path.name, self.filename_tz)
            if fn is not None and fn.dt.date() == folder_dt.date():
                date = fn

        if ext in NON_WRITABLE_EXTS:
            self._inc('unsupported')
            if self.set_file_times and not self.dry_run:
                _set_file_times(path, date)
            self._record(FileRecord(rel, 'unsupported', date=date.display(),
                                    note='format cannot store metadata; file time set'))
            self.on_log('warn', f'  → {rel}: format can\'t store a date — file time set')
            return

        note = f'was {existing.dt:%Y-%m-%d}' if existing is not None else ''
        if self.dry_run:
            self._inc(ok_status)
            self._record(FileRecord(rel, ok_status, date=date.display(), source=date.source,
                                    note=(note + '; ' if note else '') + 'dry run'))
            self.on_log('file', f'  · {rel}: would write {date.display()}' + (f' ({note})' if note else ''))
            return
        try:
            ok, msg = et.write(path, build_tag_args(is_video, date))
        except ExifToolError as e:
            ok, msg = False, str(e)
        if ok:
            if self.set_file_times:
                _set_file_times(path, date)
            self._inc(ok_status)
            self._record(FileRecord(rel, ok_status, date=date.display(), source=date.source, note=note))
            self.on_log('ok', f'  ✓ {rel}: {date.display()}' + (f' (corrected, {note})' if note else ''))
        else:
            self._inc('errors')
            self._record(FileRecord(rel, 'error', error=msg))
            self.on_log('error', f'  ✗ {rel}: {msg}')

    def _run(self) -> bool:
        ver = _check_exiftool()
        if ver is None:
            self.on_log('error', 'ExifTool not found — install it first (run Start.bat on Windows).')
            return False
        if not self.root.is_dir():
            self.on_log('error', f'Folder not found: {self.root}')
            return False
        self.on_log('info', f'Fix missing dates in {self.root} — ExifTool v{ver}'
                    + ('  [DRY RUN]' if self.dry_run else ''))
        items, notes = collect_dated_files(self.root)
        for n in notes[:20]:
            self.on_log('file', f'  {n}')
        if len(notes) > 20:
            self.on_log('file', f'  … and {len(notes) - 20} more skipped folders')
        self.stats.total = len(items)
        self.on_log('info', f'Found {len(items):,} media file(s) in dated folders')
        self.on_stats(self.stats)
        if not items:
            self.on_log('warn', 'Nothing to do. Expected year/month/day folders, e.g. '
                                '2024/January/January_07 or 2024/01/07.')
            return True

        by_dir: Dict[str, Tuple[Path, List[Tuple[Path, datetime]]]] = {}
        for p, d in items:
            by_dir.setdefault(_norm_key(p.parent), (p.parent, []))[1].append((p, d))
        batches: List[Tuple[Path, List[Tuple[Path, datetime]]]] = []
        for parent, lst in by_dir.values():
            for i in range(0, len(lst), 100):
                batches.append((parent, lst[i:i + 100]))

        self.pool = ExifToolPool()
        try:
            self._run_parallel(batches, self._fix_dir, lambda b: str(b[0].relative_to(self.root)),
                               weight=lambda b: len(b[1]))
        finally:
            self.pool.close()

        s = self.stats
        self.report_path = write_report(
            self.root, 'fixdrive-dry-run' if self.dry_run else 'fixdrive',
            {'root': str(self.root), 'dry_run': self.dry_run, **asdict(s)},
            [r.as_dict() for r in self.records], ('file', 'status', 'date', 'source', 'note', 'error'))
        self.on_log('info', '─' * 55)
        self.on_log('info', f'Finished. Fixed: {s.fixed:,} | Already dated: {s.already_dated:,} '
                            f'(of which {s.mismatched:,} disagree with their folder'
                            + (f', {s.corrected:,} corrected' if self.fix_mismatched else '') + ') | '
                            f'Unsupported: {s.unsupported:,} | Errors: {s.errors:,}')
        if self.report_path:
            self.on_log('info', f'Report: {self.report_path}  (+ .csv)')
        return not self._stop.is_set()


# ── FolderRenamer (legacy numeric → Month_DD) ─────────────────────────────────

@dataclass
class RenameStats:
    total: int = 0          # folders that need renaming
    renamed: int = 0
    merged: int = 0         # target already existed — contents moved in
    conflicts: int = 0      # identical file already at target; extra moved to _duplicates/
    errors: int = 0


def plan_folder_renames(root: Path) -> List[Tuple[Path, str]]:
    """(folder, new name) pairs, day folders before their month folder.

    2024/01/15 → 2024/January/January_15, 2024/January/January 7 →
    2024/January/January_07 (fixes the old un-padded format too)."""
    plan: List[Tuple[Path, str]] = []
    years = [root] if parse_year_folder(root.name) else \
        [d for d in _list_dir(root)[0] if parse_year_folder(d.name)]
    for ydir in years:
        for mdir in _list_dir(ydir)[0]:
            month = parse_month_folder(mdir.name)
            if month is None:
                continue
            mname = MONTHS[month - 1]
            for ddir in _list_dir(mdir)[0]:
                day = parse_day_folder(ddir.name)
                if day is None:
                    continue
                prefix = re.split(r'[_ ]', ddir.name, 1)[0] if re.search(r'[_ ]', ddir.name) else ''
                if prefix and parse_month_folder(prefix) not in (None, month):
                    continue        # e.g. February_07 inside January/ — leave for a human
                want = f'{mname}_{day:02d}'
                if ddir.name != want:
                    plan.append((ddir, want))
            if mdir.name != mname:
                plan.append((mdir, mname))
    return plan


class FolderRenamer(Job):
    kind = 'rename'

    def __init__(self, root: str, *, dry_run: bool = False, **kw):
        super().__init__(**kw)
        self.root = Path(os.path.abspath(root))
        self.dry_run = dry_run
        self.stats = RenameStats()     # type: ignore[assignment]

    def _move_into(self, src_dir: Path, dst_dir: Path) -> None:
        """Merge src_dir into existing dst_dir, never overwriting files."""
        for child in sorted(src_dir.iterdir()):
            target = dst_dir / child.name
            if child.is_dir():
                if target.is_dir():
                    self._move_into(child, target)
                    continue
                if not target.exists():
                    os.rename(_fs(child), _fs(target))
                    continue
            if target.exists():
                try:
                    same = child.is_file() and target.is_file() and full_hash(child) == full_hash(target)
                except OSError:
                    same = False
                if same:
                    # Byte-identical copy already at the target: park it in
                    # _duplicates/ (never delete) so the old folder can go away.
                    park = _unique_path(self.root / DUPES_DIR / child.relative_to(self.root),
                                        lambda p: p.exists())
                    park.parent.mkdir(parents=True, exist_ok=True)
                    os.rename(_fs(child), _fs(park))
                    self.stats.conflicts += 1
                    self.on_log('warn', f'    = identical {child.name} already in {dst_dir.name} — '
                                        f'extra copy moved to {park.relative_to(self.root)}')
                    continue
                target = _unique_path(target, lambda p: p.exists())
            os.rename(_fs(child), _fs(target))
        try:
            src_dir.rmdir()
        except OSError:
            pass

    def _apply(self, folder: Path, new_name: str) -> None:
        target = folder.with_name(new_name)
        rel = str(folder.relative_to(self.root))
        new_rel = str(target.relative_to(self.root))
        if self.dry_run:
            exists = target.exists() and not _same_dir(folder, target)
            self.stats.merged += exists
            self.stats.renamed += not exists
            self._record(FileRecord(rel, 'merge' if exists else 'rename', dest=new_rel, note='dry run'))
            self.on_log('file', f'  · {rel} → {new_rel}' + ('  (merge into existing)' if exists else ''))
            return
        try:
            if target.exists() and not _same_dir(folder, target):
                self._move_into(folder, target)
                self.stats.merged += 1
                self._record(FileRecord(rel, 'merge', dest=new_rel))
                self.on_log('ok', f'  ✓ {rel} merged into {new_rel}')
            else:
                if target.exists():           # case-only rename on Windows/macOS
                    tmp = folder.with_name(folder.name + '.~renaming')
                    os.rename(_fs(folder), _fs(tmp))
                    folder = tmp
                os.rename(_fs(folder), _fs(target))
                self.stats.renamed += 1
                self._record(FileRecord(rel, 'rename', dest=new_rel))
                self.on_log('ok', f'  ✓ {rel} → {new_rel}')
        except OSError as e:
            self.stats.errors += 1
            self._record(FileRecord(rel, 'error', dest=new_rel, error=str(e)))
            self.on_log('error', f'  ✗ {rel}: {e}')

    def _run(self) -> bool:
        if not self.root.is_dir():
            self.on_log('error', f'Folder not found: {self.root}')
            return False
        self.on_log('info', f'Rename legacy folders in {self.root}' + ('  [DRY RUN]' if self.dry_run else ''))
        plan = plan_folder_renames(self.root)
        self.stats.total = len(plan)
        self.on_stats(self.stats)
        if not plan:
            self.on_log('ok', 'All folders already use the Month/Month_DD layout — nothing to do.')
            return True
        for i, (folder, new_name) in enumerate(plan, 1):
            if self._stop.is_set():
                break
            self._wait_if_paused()
            self._apply(folder, new_name)
            self._progress(i, len(plan), str(folder.relative_to(self.root)))
        self._progress(len(self.records), len(plan), '', force=True)
        s = self.stats
        self.report_path = write_report(
            self.root, 'rename-dry-run' if self.dry_run else 'rename',
            {'root': str(self.root), 'dry_run': self.dry_run, **asdict(s)},
            [r.as_dict() for r in self.records], ('file', 'status', 'dest', 'note', 'error'))
        self.on_log('info', '─' * 55)
        self.on_log('info', f'Finished. Renamed: {s.renamed:,} | Merged: {s.merged:,} | '
                            f'Identical copies moved to {DUPES_DIR}/: {s.conflicts:,} | Errors: {s.errors:,}')
        return not self._stop.is_set()


def _same_dir(a: Path, b: Path) -> bool:
    try:
        return os.path.samefile(_fs(a), _fs(b))
    except OSError:
        return False


# ── Duplicates ────────────────────────────────────────────────────────────────

def _keeper_key(p: Path, index: SidecarIndex) -> tuple:
    """Prefer: has a Takeout sidecar → lives in 'Photos from YYYY' → shorter path."""
    has_json = index.find(p) is not None if p.suffix.lower() in ALL_MEDIA else False
    in_year = 'photos from ' in str(p).lower()
    return (not has_json, not in_year, len(str(p)), str(p).lower())


def find_duplicate_groups(roots: Sequence[Path], on_progress=None,
                          stop: Optional[threading.Event] = None,
                          counts: Optional[dict] = None) -> List[List[Path]]:
    """Groups of byte-identical media files; best copy to keep first.
    If *counts* is given, counts['files'] is set to the number scanned."""
    files: List[Path] = []
    seen = set()
    for r in roots:
        for p in iter_media(Path(r)):
            k = _norm_key(p)
            if k not in seen:
                seen.add(k)
                files.append(p)
    if counts is not None:
        counts['files'] = len(files)

    by_size: Dict[int, List[Path]] = {}
    for p in files:
        try:
            sz = os.path.getsize(_fs(p))
        except OSError:
            continue
        if sz > 0:
            by_size.setdefault(sz, []).append(p)
    candidates = [g for g in by_size.values() if len(g) > 1]
    total = sum(len(g) for g in candidates)
    done = 0
    groups: List[List[Path]] = []
    index = SidecarIndex()
    for group in candidates:
        if stop is not None and stop.is_set():
            break
        by_quick: Dict[str, List[Path]] = {}
        for p in group:
            done += 1
            if on_progress:
                on_progress(done, total)
            try:
                by_quick.setdefault(quick_fingerprint(p), []).append(p)
            except OSError:
                continue
        for qgroup in by_quick.values():
            if len(qgroup) < 2:
                continue
            if os.path.getsize(_fs(qgroup[0])) <= 2 * _HASH_CHUNK:
                final = {'all': qgroup}          # quick fingerprint covered the whole file
            else:
                final = {}
                for p in qgroup:
                    try:
                        final.setdefault(full_hash(p), []).append(p)
                    except OSError:
                        continue
            for g in final.values():
                if len(g) > 1:
                    groups.append(sorted(g, key=lambda p: _keeper_key(p, index)))
    groups.sort(key=lambda g: str(g[0]).lower())
    return groups


def find_duplicates(src_a: Path, src_b: Path, on_progress=None) -> List[DupMatch]:
    """Back-compat: (kept, duplicate) pairs; every match is content-verified."""
    roots = [Path(src_a)] if _norm_key(Path(src_a)) == _norm_key(Path(src_b)) else [Path(src_a), Path(src_b)]
    out: List[DupMatch] = []
    for g in find_duplicate_groups(roots, on_progress):
        size = os.path.getsize(_fs(g[0]))
        out += [DupMatch(g[0], dup, size, 'exact') for dup in g[1:]]
    return out


@dataclass
class DupStats:
    total: int = 0          # files scanned
    groups: int = 0
    duplicates: int = 0     # extra copies (not counting the one kept)
    wasted_bytes: int = 0
    moved: int = 0
    errors: int = 0


class DuplicateFinder(Job):
    """Finds byte-identical media; optionally moves extra copies (and their
    sidecars) into <root>/_duplicates/, keeping the folder structure.
    Never deletes anything."""

    kind = 'dupes'

    def __init__(self, roots: Sequence[str], *, move: bool = False, **kw):
        super().__init__(**kw)
        self.roots = [Path(os.path.abspath(r)) for r in roots]
        self.move = move
        self.stats = DupStats()        # type: ignore[assignment]
        self.groups: List[List[Path]] = []

    def _root_of(self, p: Path) -> Path:
        for r in self.roots:
            if _is_within(p, r):
                return r
        return p.parent

    def _run(self) -> bool:
        for r in self.roots:
            if not r.is_dir():
                self.on_log('error', f'Folder not found: {r}')
                return False
        self.on_log('info', 'Scanning for byte-identical duplicates in: ' + ', '.join(map(str, self.roots)))
        counts: dict = {}
        self.groups = find_duplicate_groups(
            self.roots, on_progress=lambda c, t: self._progress(c, t, 'hashing'),
            stop=self._stop, counts=counts)
        s = self.stats
        s.total = counts.get('files', 0)
        s.groups = len(self.groups)
        index = SidecarIndex()
        for g in self.groups:
            size = os.path.getsize(_fs(g[0]))
            s.duplicates += len(g) - 1
            s.wasted_bytes += size * (len(g) - 1)
            keep_root = self._root_of(g[0])
            self._record(FileRecord(str(g[0].relative_to(keep_root)), 'keep', note=_fmt_size(size)))
            for dup in g[1:]:
                root = self._root_of(dup)
                rel = dup.relative_to(root)
                rec = FileRecord(str(rel), 'duplicate', note=f'same as {g[0].relative_to(keep_root)}')
                if self.move and not self._stop.is_set():
                    try:
                        target = root / DUPES_DIR / rel
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target = _unique_path(target, lambda p: p.exists())
                        sidecar = index.find(dup)
                        os.rename(_fs(dup), _fs(target))
                        if sidecar is not None and sidecar.exists():
                            # Copy, never move: the kept file may share this sidecar.
                            shutil.copy2(_fs(sidecar), _fs(target.parent / sidecar.name))
                        rec.status, rec.dest = 'moved', str(target.relative_to(root))
                        s.moved += 1
                    except OSError as e:
                        rec.status, rec.error = 'error', str(e)
                        s.errors += 1
                self._record(rec)
        self.on_stats(s)
        for g in self.groups[:200]:
            self.on_log('ok', f'  keep {g[0]}')
            for dup in g[1:]:
                self.on_log('warn', f'    {"moved" if self.move else "dup "} {dup}')
        if len(self.groups) > 200:
            self.on_log('info', f'  … {len(self.groups) - 200} more groups in the report')
        self.report_path = write_report(
            self.roots[0], 'duplicates', {'roots': [str(r) for r in self.roots], 'move': self.move, **asdict(s)},
            [r.as_dict() for r in self.records], ('file', 'status', 'dest', 'note', 'error'))
        self.on_log('info', '─' * 55)
        self.on_log('info', f'Finished. {s.groups:,} group(s), {s.duplicates:,} extra copies, '
                            f'{_fmt_size(s.wasted_bytes)} reclaimable'
                            + (f' | moved to {DUPES_DIR}/: {s.moved:,}' if self.move else ''))
        if self.report_path:
            self.on_log('info', f'Report: {self.report_path}  (+ .csv)')
        return not self._stop.is_set()



# ── Archives (Google Takeout downloads) ───────────────────────────────────────

def is_archive(name: str) -> bool:
    n = name.lower()
    return n.endswith(ARCHIVE_EXTS) and not n.startswith('._')


def find_archives(root: Path) -> List[Path]:
    """Archives anywhere under *root* (same rules as analyze_folder)."""
    out: List[Path] = []
    for dirpath, dirnames, filenames in os.walk(root, onerror=lambda e: None):
        dirnames[:] = [d for d in dirnames if d.lower() not in _SKIP_DIRS and d.lower() != DUPES_DIR]
        out += [Path(dirpath) / f for f in sorted(filenames) if is_archive(f)]
    return sorted(out)


_BAD_CHARS = re.compile(r'[<>:"|?*\x00-\x1f]')


def safe_member_path(name: str) -> Optional[Path]:
    """Archive member name → safe relative path (None = skip it).

    Blocks absolute paths and '..' (zip-slip) and cleans characters
    Windows can't store, so every entry lands inside the target folder."""
    parts = []
    for part in name.replace('\\', '/').split('/'):
        if part in ('', '.'):
            continue
        if part == '..':
            return None
        part = _BAD_CHARS.sub('_', part).rstrip(' .')
        if not part:
            continue
        parts.append(part)
    if not parts or re.fullmatch(r'[A-Za-z]_?', parts[0]) and len(parts) > 1 and ':' in name[:3]:
        parts = parts[1:]                      # drop a 'C:' drive prefix
    return Path(*parts) if parts else None


@dataclass
class ExtractStats:
    total: int = 0          # archives
    archives_ok: int = 0
    archives_bad: int = 0
    files: int = 0          # entries seen
    extracted: int = 0
    skipped: int = 0        # already unpacked earlier
    errors: int = 0
    bytes_written: int = 0


class ArchiveExtractor(Job):
    """Unpacks Takeout .zip / .tgz files into one folder. Multi-part
    exports (takeout-…-001.zip, -002.zip…) merge naturally because every
    part contains the same Takeout/ top folder. Re-running skips files
    that are already there, so an interrupted unpack can be resumed."""

    kind = 'unpack'

    def __init__(self, archives: Sequence[str], dest: str, **kw):
        super().__init__(**kw)
        self.archives = [Path(os.path.abspath(a)) for a in archives]
        self.dest = Path(os.path.abspath(dest))
        self.stats = ExtractStats()          # type: ignore[assignment]
        self.fatal = ''

    # Each reader yields (name, size, mtime, opener) for regular files only.
    @staticmethod
    def _zip_entries(path: Path):
        import zipfile
        zf = zipfile.ZipFile(_fs(path))
        try:
            for info in zf.infolist():
                if info.is_dir():
                    continue
                try:
                    mtime = time.mktime(info.date_time + (0, 0, -1))
                except (OverflowError, ValueError):
                    mtime = None
                yield info.filename, info.file_size, mtime, (lambda i=info: zf.open(i))
        finally:
            zf.close()

    @staticmethod
    def _tar_entries(path: Path):
        import tarfile
        tf = tarfile.open(_fs(path), 'r:*')
        try:
            for m in tf:
                if not m.isreg():
                    continue
                yield m.name, m.size, m.mtime, (lambda mm=m: tf.extractfile(mm))
        finally:
            tf.close()

    def _entries(self, path: Path):
        return self._zip_entries(path) if path.name.lower().endswith('.zip') else self._tar_entries(path)

    def _remaining_size(self, path: Path) -> int:
        """Bytes still to write for *path* (entries already unpacked don't count)."""
        try:
            if path.name.lower().endswith('.zip'):
                import zipfile
                need = 0
                with zipfile.ZipFile(_fs(path)) as zf:
                    for i in zf.infolist():
                        rel = safe_member_path(i.filename)
                        if i.is_dir() or rel is None:
                            continue
                        t = self.dest / rel
                        try:
                            if os.path.getsize(_fs(t)) == i.file_size:
                                continue
                        except OSError:
                            pass
                        need += i.file_size
                return need
            return os.path.getsize(_fs(path)) * 2      # tar.gz: rough guess
        except Exception:
            return 0

    def _extract_one(self, path: Path) -> None:
        import tarfile
        import zipfile
        name = path.name
        self.on_log('info', f'Unpacking {name} …')
        s = self.stats
        try:
            for member, size, mtime, opener in self._entries(path):
                if self._stop.is_set():
                    return
                self._wait_if_paused()
                s.files += 1
                rel = safe_member_path(member)
                if rel is None:
                    s.errors += 1
                    self._record(FileRecord(member, 'error', note=name, error='Unsafe path in archive; skipped'))
                    continue
                target = self.dest / rel
                tmp = target.with_name(_TMP_PREFIX + target.name)
                try:
                    if target.is_file() and os.path.getsize(_fs(target)) == size:
                        s.skipped += 1
                        continue
                    os.makedirs(_fs(target.parent), exist_ok=True)
                    with opener() as src, open(_fs(tmp), 'wb') as out:
                        shutil.copyfileobj(src, out, 1 << 20)
                    if mtime:
                        try:
                            os.utime(_fs(tmp), (mtime, mtime))
                        except (OSError, OverflowError, ValueError):
                            pass
                    os.replace(_fs(tmp), _fs(target))
                    s.extracted += 1
                    s.bytes_written += size
                except OSError as e:
                    s.errors += 1
                    self._record(FileRecord(str(rel), 'error', note=name, error=friendly_os_error(e)))
                    self.on_log('error', f'  ✗ {rel}: {friendly_os_error(e)}')
                    if is_fatal_os_error(e):
                        self.fatal = friendly_os_error(e)
                        self.stop()
                        return
                except Exception as e:              # zlib.error, Deflate64, encrypted…
                    s.errors += 1
                    if isinstance(e, NotImplementedError):
                        why = 'Compressed with a method Python can\'t read (e.g. Deflate64); unzip it with Windows Explorer'
                    elif isinstance(e, RuntimeError) and 'encrypt' in str(e).lower():
                        why = 'Password-protected entry; unzip it manually'
                    else:
                        why = f'Damaged entry in the archive ({e})'
                    self._record(FileRecord(str(rel), 'error', note=name, error=why))
                    self.on_log('error', f'  ✗ {rel}: {why}')
                finally:
                    if os.path.lexists(_fs(tmp)):
                        try:
                            os.unlink(_fs(tmp))
                        except OSError:
                            pass
                self._total_entries = max(self._total_entries, s.files)
                self._progress(s.files, self._total_entries, str(rel))
            s.archives_ok += 1
            self._record(FileRecord(name, 'unpacked', dest=str(self.dest)))
            self.on_log('ok', f'  ✓ {name}')
        except Exception as e:                      # unreadable / truncated archive
            s.archives_bad += 1
            msg = ('This archive looks incomplete or damaged; download it again from Google Takeout'
                   if not isinstance(e, OSError) or not is_fatal_os_error(e) else friendly_os_error(e))
            self._record(FileRecord(name, 'error', error=msg))
            self.on_log('error', f'  ✗ {name}: {msg}')

    def _count_entries(self, path: Path) -> int:
        """Entries in the archive; compressed tars are estimated from their size."""
        try:
            if path.name.lower().endswith('.zip'):
                import zipfile
                with zipfile.ZipFile(_fs(path)) as zf:
                    return sum(1 for i in zf.infolist() if not i.is_dir())
            return max(1, os.path.getsize(_fs(path)) // 1_500_000)
        except Exception:
            return 1

    def _run(self) -> bool:
        if not self.archives:
            self.on_log('error', 'No archives to unpack.')
            return False
        missing = [a for a in self.archives if not a.is_file()]
        if missing:
            self.on_log('error', f'Archive not found: {missing[0]}')
            return False
        self.stats.total = len(self.archives)
        self._total_entries = sum(self._count_entries(a) for a in self.archives) or 1
        need = sum(self._remaining_size(a) for a in self.archives)
        try:
            self.dest.mkdir(parents=True, exist_ok=True)
            free = shutil.disk_usage(str(self.dest)).free
            self.on_log('info', f'Unpacking {len(self.archives)} archive(s) (about {_fmt_size(need)} still to '
                                f'write) into {self.dest}; {_fmt_size(free)} free')
            if need > free:
                self.on_log('error', 'Not enough free space to unpack everything. Free up space or '
                                     'choose a folder on a bigger drive.')
                return False
        except OSError as e:
            self.on_log('error', f'Cannot use {self.dest}: {friendly_os_error(e)}')
            return False
        self.on_stats(self.stats)
        for a in self.archives:
            if self._stop.is_set():
                break
            self._extract_one(a)
            self.on_stats(self.stats)
        self._progress(self.stats.files, max(self.stats.files, self._total_entries), '', force=True)
        s = self.stats
        self.report_path = write_report(
            self.dest, 'unpack', {'dest': str(self.dest), 'archives': [str(a) for a in self.archives], **asdict(s)},
            [r.as_dict() for r in self.records], ('file', 'status', 'dest', 'note', 'error'))
        self.on_log('info', '─' * 55)
        self.on_log('info', f'Finished. Archives unpacked: {s.archives_ok}/{s.total} | Files written: '
                            f'{s.extracted:,} | Already there: {s.skipped:,} | Problems: {s.errors + s.archives_bad:,}')
        if self.fatal:
            self.on_log('error', f'Stopped early: {self.fatal}.')
            return False
        return not self._stop.is_set() and s.archives_bad == 0


# ── Folder analysis (the "Start here" guide) ──────────────────────────────────

def _dated_parts(parts: Sequence[str]) -> Optional[Tuple[bool]]:
    """If the path ends with (or contains) year/month/day folders, return
    (is_legacy,) — legacy = numeric month or non-canonical day name."""
    for i in range(len(parts) - 2):
        y, m, d = parts[i], parts[i + 1], parts[i + 2]
        if parse_year_folder(y) and parse_month_folder(m) and parse_day_folder(d):
            month = parse_month_folder(m)
            want = f'{MONTHS[month - 1]}_{parse_day_folder(d):02d}'
            return (m != MONTHS[month - 1] or d != want,)
    return None


def analyze_folder(root: Path, time_limit: float = 45.0, sample: int = 40) -> dict:
    """Look at any folder and describe what's in it, plus what to do next."""
    root = Path(os.path.abspath(root))
    t0 = time.monotonic()
    info = {
        'root': str(root), 'media': 0, 'photos': 0, 'videos': 0, 'size': 0,
        'sidecars': 0, 'archives': [], 'archive_size': 0, 'other': {}, 'junk': 0, 'empty': 0,
        'name_dated': 0, 'dated_media': 0, 'legacy_media': 0, 'folders': 0,
        'years': {}, 'unreadable': [], 'takeout_marker': False, 'partial': False,
        'possible_dupes': 0, 'sample_checked': 0, 'sample_dated': 0, 'top_exts': {},
    }
    seen_name_size: Dict[Tuple[str, int], int] = {}
    undated_sample: List[Path] = []
    root_parts = (root.name,)

    def onerror(e: OSError) -> None:
        info['unreadable'].append(f'{getattr(e, "filename", "")}: {friendly_os_error(e)}')

    for dirpath, dirnames, filenames in os.walk(root, onerror=onerror):
        if time.monotonic() - t0 > time_limit:
            info['partial'] = True
            break
        dp = Path(dirpath)
        dirnames[:] = sorted(d for d in dirnames if d.lower() not in _SKIP_DIRS and d.lower() != DUPES_DIR)
        info['folders'] += 1
        rel_parts = root_parts + dp.relative_to(root).parts
        low = [p.lower() for p in rel_parts]
        if 'takeout' in low or 'google photos' in low or any(p.startswith('photos from ') for p in low):
            info['takeout_marker'] = True
        dated = _dated_parts(rel_parts)
        for f in filenames:
            lf = f.lower()
            if is_junk_name(f):
                info['junk'] += 1
                continue
            if is_archive(f):
                try:
                    sz = os.path.getsize(_fs(dp / f))
                except OSError:
                    sz = 0
                info['archives'].append({'path': str(dp / f), 'name': f, 'size': sz})
                info['archive_size'] += sz
                continue
            ext = os.path.splitext(lf)[1]
            if ext == '.json':
                if lf not in _NON_SIDECAR_JSON:
                    info['sidecars'] += 1
                continue
            if ext not in ALL_MEDIA:
                if lf not in _SYSTEM_FILES:
                    key = ext or '(no extension)'
                    info['other'][key] = info['other'].get(key, 0) + 1
                continue
            try:
                sz = os.path.getsize(_fs(dp / f))
            except OSError:
                continue
            if sz == 0:
                info['empty'] += 1
            info['media'] += 1
            info['size'] += sz
            info['top_exts'][ext] = info['top_exts'].get(ext, 0) + 1
            if ext in VIDEO_EXTS:
                info['videos'] += 1
            else:
                info['photos'] += 1
            k = (lf, sz)
            seen_name_size[k] = seen_name_size.get(k, 0) + 1
            year = None
            if dated:
                info['dated_media'] += 1
                if dated[0]:
                    info['legacy_media'] += 1
            pd = date_from_filename(f)
            if pd:
                info['name_dated'] += 1
                year = pd.dt.year
            elif not dated and len(undated_sample) < sample:
                undated_sample.append(dp / f)
            if year is None and dated:
                for part in rel_parts:
                    if parse_year_folder(part):
                        year = int(part)
            if year:
                info['years'][year] = info['years'].get(year, 0) + 1

    info['possible_dupes'] = sum(n - 1 for n in seen_name_size.values() if n > 1)
    if undated_sample and find_exiftool():
        try:
            with ExifTool(batch=False) as et:
                tags = et.read_tags(undated_sample, _DATE_TAGS)
            info['sample_checked'] = len(undated_sample)
            for p in undated_sample:
                pd = parse_embedded_date(tags.get(_norm_key(p)) or {}, p.suffix.lower() in VIDEO_EXTS)
                if pd:
                    info['sample_dated'] += 1
                    info['years'][pd.dt.year] = info['years'].get(pd.dt.year, 0) + 1
        except ExifToolError:
            pass
    info['years'] = dict(sorted(info['years'].items()))
    info['top_exts'] = dict(sorted(info['top_exts'].items(), key=lambda kv: -kv[1])[:8])
    info['other_total'] = sum(info['other'].values())
    info['other_desc'] = describe_ignored(info['other'])
    info['unreadable'] = info['unreadable'][:20]
    info['kind'], info['recommendations'] = _recommend(info)
    return info


def _recommend(i: dict) -> Tuple[str, List[dict]]:
    """Pick what this folder most likely is, and the steps to take."""
    recs: List[dict] = []
    media = i['media']
    root = i['root']

    def add(tool, title, why, primary=False, **prefill):
        recs.append({'tool': tool, 'title': title, 'why': why, 'primary': primary, 'prefill': prefill})

    if i['archives'] and (media < 50 or i['archive_size'] > i['size']):
        n = len(i['archives'])
        add('unpack', f'Unpack {n} Takeout archive{"s" if n != 1 else ""} first',
            'Google Takeout downloads come as zip files. Unpack them into one folder, then process that folder.',
            primary=True, src=root)
        return 'archives', recs
    if media == 0:
        return 'empty', recs

    takeoutish = i['sidecars'] >= max(5, media * 0.2) or (i['takeout_marker'] and i['sidecars'] > 0)
    dated_share = i['dated_media'] / media
    if takeoutish:
        add('takeout', 'Process this Google Takeout export',
            f'Found {i["sidecars"]:,} Google JSON files with the real dates and locations of your photos.',
            primary=True, src=root)
        kind = 'takeout'
    elif dated_share >= 0.5:
        kind = 'drive'
        if i['legacy_media']:
            add('rename', 'Rename the old-style folders',
                'Some folders use the old 2024/01/15 layout. Renaming first gives one consistent layout.',
                primary=True, root=root)
        add('fixdrive', 'Fix missing dates on this drive',
            f'{i["dated_media"]:,} of {media:,} files are already in year/month/day folders; '
            'files with no date inside get their folder\'s date.',
            primary=not i['legacy_media'], root=root)
    else:
        kind = 'unorganized'
        share = (i['sample_dated'] / i['sample_checked']) if i['sample_checked'] else None
        why = f'{i["name_dated"]:,} of {media:,} files have a date in their name'
        if share is not None:
            why += f', and about {round(share * 100)}% of the others have a date stored inside'
        why += '. Each file is copied into a year/month/day folder; anything without a date goes to no-date.'
        add('filename', 'Organize these photos into dated folders', why, primary=True, src=root)
    if i['possible_dupes'] >= 5:
        add('dupes', 'Check for duplicates',
            f'About {i["possible_dupes"]:,} files have the same name and size as another file.', a=root)
    return kind, recs

__all__ = [
    'ALL_MEDIA', 'IMAGE_EXTS', 'VIDEO_EXTS', 'MONTHS', 'OUTPUT_MODES',
    'Stats', 'FileRecord', 'Meta', 'DupMatch', 'PhotoDate', 'ProcessOptions',
    'Processor', 'DriveFixer', 'FolderRenamer', 'DuplicateFinder',
    'ExifTool', 'ExifToolPool', 'ExifToolError', 'SidecarIndex', 'Manifest',
    'find_json', 'json_candidate_names', 'parse_meta', 'date_from_filename',
    'date_subdir', 'parse_day_folder', 'parse_month_folder', 'parse_year_folder',
    'build_tag_args', 'build_exiftool_args', 'find_exiftool', 'find_duplicates',
    'find_duplicate_groups', 'collect_dated_files', 'plan_folder_renames', 'iter_media',
    'ArchiveExtractor', 'find_archives', 'safe_member_path', 'analyze_folder', 'friendly_os_error',
]
