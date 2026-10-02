# Photos Backup Fix — Codebase Guide

This project has two independent tools in the same repo. Read this before making changes.

---

## Repository layout

```
Photos-backup-fix/
├── web_app.py              ← Flask entry point (Windows tool)
├── core.py                 ← Processing logic shared by all Python frontends
├── cli.py                  ← Command-line interface
├── tests/                  ← pytest suite for core.py / web_app.py
├── templates/index.html    ← Browser UI (SSE, no build step)
├── Start.bat / setup.ps1   ← Windows launchers
├── android-app/            ← Android app (**Übertrag**)
└── CLAUDE.md               ← This file
```

---

## Tool 1 — Python / Flask (Windows)

### Key files

| File | Purpose |
|---|---|
| `core.py` | All processing logic, stdlib only. `Job` base class + `Processor` (Takeout / sort-by-filename), `DriveFixer`, `FolderRenamer`, `DuplicateFinder`, `ArchiveExtractor`, `analyze_folder()` (Start-here guide); `ExifTool` (stay_open wrapper), `SidecarIndex`, `Manifest`, date helpers |
| `web_app.py` | Flask routes, `Hub` (SSE fan-out + replay), `Runner` (one job at a time), security guard, folder picker |
| `cli.py` | argparse CLI over the same jobs (`takeout`, `sort`, `fix-dates`, `rename`, `dupes`, `unpack`, `analyze`) |
| `templates/index.html` | Browser UI, no build step. Light theme = ivory/slate/clay, dark = `#151515` + dot grid; colours are CSS tokens on `:root[data-theme]`. Overview page + one `section.view` per tool; the shared `#activity` panel is moved into the active tool's `.activity-slot`. The JS `TOOLS` table drives request bodies, validation, stat tiles and result summaries |
| `tests/` | pytest suite (`python -m pytest tests/`); ExifTool/ffmpeg tests auto-skip when missing |
| `app.py` | Legacy tkinter UI (Takeout only) — must keep importing from `core.py` |

### Architecture

- Every tool is a `core.Job`: callbacks `on_log/on_progress/on_stats/on_done/on_file_result/on_event`, `stop()/pause()/resume()`, `_run_parallel()` worker pool, and `self.records` (`FileRecord`) + `self.report_path`
- Each worker thread gets its own `exiftool -stay_open` process via `ExifToolPool`. Commands are fenced with `-echo4 {readyN}` / `-execute{N}`; a timeout kills and restarts the process
- Web flow: `POST /api/run {tool, …}` → `Runner` thread → job callbacks → `hub.publish()` → `/api/stream` SSE to every tab. New tabs get the recent log + latest progress/stats replayed. Records are fetched on demand via `/api/records` (not streamed)
- Other endpoints: `/api/preflight` (count/size/sidecars/free space), `/api/inspect` (dated files + old-style folders on a drive), `/api/records` (paged results), `/api/report?fmt=csv|json`, `/api/status` (includes `history` of recent runs)
- Takeout + "Review duplicates" pauses in `_dup_review()` until `/api/decide` (`skip_dupes`, `keep_all`, or anything else = cancel)
- Security: 127.0.0.1 only, `Host` header must be localhost:<port>, every `/api/*` needs the per-launch `TOKEN` (`X-Token` header, or `?t=` for SSE/downloads), POSTs must be JSON

### Key invariants

- Every input file must end up somewhere: dated folder, `no-date/<rel dir>/`, `error/<rel dir>/` (empty, unreadable, failed copy, any per-file exception via `_safe_process`/`_fail`), or an explicit skip record (duplicate, already in output, filtered). Never drop a file silently
- Fatal OS errors (`is_fatal_os_error`: disk full, device gone) stop the run via `Processor._fatal`; everything else is per-file. Use `friendly_os_error()` for user-facing messages
- `iter_media`/`count_folder`/`analyze_folder` share the same skip rules (`_SKIP_DIRS`, `DUPES_DIR`, `is_junk_name`, `_SYSTEM_FILES`); change them together
- Identical content headed for the same output folder is kept once (`_claim` → `_dir_fps`, full-hash verified outside the lock); a failed copy must `_release()` its claim
- `Job._lock` is NOT re-entrant: never call `_fp`, `_count`, `_record`, `_release` while holding it
- Files whose extension lies about their type (ExifTool "looks more like a JPEG") get their real extension in the output (`_real_extension`)
- `ArchiveExtractor` sanitises member paths with `safe_member_path` (zip-slip), writes via temp files, and skips entries already unpacked

- Output layout matches Android: `YYYY/Month/Month_DD` (`date_subdir()`), plus `no-date/`, `error/`; reports + `manifest.json` live in `_photofix/`
- Date priority in `Processor`: embedded date (if `keep_existing_dates`) → sidecar `photoTakenTime` → filename → sidecar `creationTime` → embedded (if not kept) → `no-date/`
- Sidecar timestamps are written as UTC with `OffsetTimeOriginal=+00:00` (critical for Google Photos). Filename dates without a time → 12:00 `+00:00` (Android parity); with a time → local wall time + this PC's offset (`filename_tz='utc'` to opt out)
- QuickTime video date tags are always written in **UTC**; `Keys:CreationDate` carries the local time + offset
- Copies go to `.~photofix~<name>` first and are `os.replace()`d into place only when complete; `_claim()` reserves output names under a lock and decides skip (same content / manifest hit) vs `_1` suffix (different photo, same name)
- `Stats.no_json` means "no date at all"; `Stats.processed` = sidecar + filename dates written; `kept_existing` / `unsupported` / `meta_failed` are separate counters
- Formats in `NON_WRITABLE_EXTS` (AVI, MKV, BMP, …) are sorted and get file times, but ExifTool is never asked to write them
- ExifTool args are passed with `-ec`; user text (captions, names) must go through `_esc()` so newlines/backslashes survive the line-based argfile
- `-charset filename=UTF8` and `-api WindowsLongPath=1` are added on Windows (`_common_args()`)
- Never delete user files: duplicate handling *moves* to `_duplicates/`
- `core.py` stays stdlib-only and Python 3.7-compatible (no 3.9+ APIs like `Path.is_relative_to`)

---

## Tool 2 — Android (**Übertrag**)

Package: `com.firebolt141.ubertrag`  
Min SDK: 26 (Android 8)  
Build: Kotlin + Jetpack Compose + Room + DataStore

### Navigation

`ModalNavigationDrawer`; start destination `start` (`StartScreen`, a "what do you have?" guide).

| Route | Screen |
|---|---|
| `start` | `StartScreen` — task cards → other routes |
| `home` | `HomeScreen` — 3 steps: drive → scan → copy; permission card, Stop, last summary |
| `queue` | `QueueScreen` — scrollable filter chips, "copy everything again" |
| `takeout` | `TakeoutScreen` — Takeout import (`TakeoutProcessor`) |
| `organize` | `FixExifScreen(filenameMode = true)` — sort any folder by date |
| `fix-exif` | `FixExifScreen(filenameMode = false)` — fix dates on drive in place |
| `rename-folders` | `RenameFoldersScreen` — Check (dry run) then rename/merge |

### Key files

| File | Purpose |
|---|---|
| `util/PhotoLogic.kt` | Pure Kotlin (unit-tested): filename dates, folder names/parsing, sidecar matching, `PhotoDate` |
| `util/SafTree.kt` | Cached SAF listings; `copyInto()` = temp name → size check → rename; same name+size = `AlreadyThere`, else `_N` |
| `util/TakeoutProcessor.kt` | Shared engine for Takeout + "sort a folder"; every file → dated dir, `no-date/<rel>`, or `error/<rel>`; fatal errors stop the run |
| `util/ExifFixer.kt` | `fixMissingExif()` (in place, via sibling temp file) + `fixByFilename()` (delegates to TakeoutProcessor, `useSidecars=false`) |
| `util/StorageHelper.kt` | `isDriveMounted`, `folderLabel`, `renameLegacyFolders(context, root, dryRun)` with merge |
| `repository/SyncRepository.kt` | Scan (wall-clock dates), copyPending (SafTree), wrappers for all tools |
| `service/CopyService.kt` | Foreground copy; Stop action, wake lock, `lastSummary` |
| `service/KeepAliveService.kt` | `KeepAlive.begin/update/end` keeps ViewModel jobs alive with the screen off |
| `ui/SharedComponents.kt` | `DriveStatusCard`, `FolderPickerCard`, `JobProgressCard`, `LogPanel`, `ResultCard`, `StepLabel`, `InfoCard` |

### Invariants

- Queue/folder dates are **wall-clock time encoded as UTC ms** (`PhotoLogic.wallMs`, `PhotoDate.wallMs`); format them in UTC
- EXIF/GPS for copies is written into a cache copy **before** copying (deterministic bytes → re-runs detect "already there")
- Compare SAF folders by document id, not URI string
- Long ViewModel jobs must call `KeepAlive.begin/end` and honour an `isCancelled` flag

### EXIF writing pattern

Always use the **temp-file pattern** for SAF writes — do NOT use `ExifInterface(FileDescriptor)` directly (known EBADF crash on some devices):

```kotlin
val tmp = File(context.cacheDir, "exif_${System.nanoTime()}.tmp")
try {
    // 1. Copy SAF file → temp
    context.contentResolver.openInputStream(file.uri)?.use { inp ->
        tmp.outputStream().use { inp.copyTo(it) }
    }
    // 2. Write EXIF on temp file path
    ExifInterface(tmp.absolutePath).apply {
        setAttribute(TAG_DATETIME_ORIGINAL, dateStr)
        // ... other tags ...
        saveAttributes()
    }
    // 3. Write back with "wt" (truncate) mode
    context.contentResolver.openOutputStream(file.uri, "wt")?.use { out ->
        tmp.inputStream().use { it.copyTo(out) }
    }
} finally { tmp.delete() }
```

### EXIF tags always written together

```kotlin
TAG_DATETIME_ORIGINAL       // primary date
TAG_DATETIME                // secondary
TAG_DATETIME_DIGITIZED      // tertiary
TAG_OFFSET_TIME_ORIGINAL    // "+00:00" — critical for Google Photos
TAG_OFFSET_TIME             // "+00:00"
TAG_OFFSET_TIME_DIGITIZED   // "+00:00"
```

### Format support matrix

| Format | EXIF write | Notes |
|---|---|---|
| JPEG / PNG / WebP | ✓ | ExifInterface 1.3.7 |
| HEIC / HEIF | ✗ | Framework read-only |
| RAW (CR2, NEF…) | ✗ | Framework read-only |
| Video | ✗ | No native Android API |

### Folder naming

New copies go into `2024/January/January_07/`. `PhotoLogic.parseDayFolder` accepts `January_07`, `January 7`, `15`; `renameLegacyFolders` converts old names and merges into existing folders.

### State management

`MainViewModel` merges flows into `UiState` (rename state is `RenameUi`). `FixExifViewModel` and `TakeoutViewModel` are isolated; they remember folder picks in `Prefs.folder(slot)`.

### App icon

The launcher icon is a cartoon **German Shepherd** face (front-facing, tan/black coloring, pink ears, tongue out) on a warm amber background (`#C8831A`).

Icon assets live in `app/src/main/res/mipmap-*/`:
- `ic_launcher.png` — legacy launcher icon at each density (48 / 72 / 96 / 144 / 192 px)
- `ic_launcher_round.png` — same image, round crop applied by launcher
- `ic_launcher_foreground.png` — adaptive icon foreground at 108dp per density (108 / 162 / 216 / 324 / 432 px)
- `mipmap-anydpi-v26/ic_launcher.xml` — adaptive icon XML referencing `@mipmap/ic_launcher_foreground` + `@color/ic_launcher_background`

### Unit tests

Tests live in `app/src/test/` (`PhotoLogicTest`) and cover `PhotoLogic`.

Run with `./gradlew test` from the `android-app/` directory.

---

## Development branch

Active development: `claude/fix-start-script-NK4P4`  
Merge target: `main`

---

## Common pitfalls

- **Do not** call `file.parentFile` on a DocumentFile obtained via `listFiles()` — prefer passing the parent dir explicitly
- **Do not** use `ExifInterface(FileDescriptor)` for writes — use the temp-file pattern
- **Do not** rename month/day folders before renaming the day folders inside them — `renameLegacyFolders` handles ordering correctly (day → month)
- **Do not** add `FolderPickerCard` as `private` to a single screen file — it lives in `SharedComponents.kt`
- The `Stats.no_json` Python field now means "no date at all" not "no sidecar" — filename-dated files are counted in `processed`
- **Do not** spawn one ExifTool process per file in Python — use `ExifToolPool().get()` (stay_open)
- **Do not** stream per-file records over SSE — the UI pages them from `/api/records`
- SAF `openOutputStream(uri, "wt")` requires API 26+ — matches our `minSdk`
- Day folders use `Month_DD` format (`January_07`), not `Month D` (`January 7`) — parsers accept both
