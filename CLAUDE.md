# Photos Backup Fix — Codebase Guide

This project has two independent tools in the same repo. Read this before making changes.

---

## Repository layout

```
Photos-backup-fix/
├── web_app.py              ← Flask entry point (Windows tool)
├── core.py                 ← Processing logic shared by all Python frontends
├── templates/index.html    ← Browser UI (SSE, no build step)
├── Start.bat / setup.ps1   ← Windows launchers
├── android-app/            ← Android app (Übertrag)
└── CLAUDE.md               ← This file
```

---

## Tool 1 — Python / Flask (Windows)

### Key files

| File | Purpose |
|---|---|
| `core.py` | All media processing — `find_json()`, `parse_meta()`, `Processor`, `find_duplicates()` |
| `web_app.py` | Flask routes, SSE event queue, folder picker, worker thread |
| `templates/index.html` | Browser UI; listens to `/api/stream` SSE events |

### Architecture

- `Processor.run()` iterates source files, finds JSON sidecars, calls ExifTool via subprocess
- SSE events (`log`, `progress`, `stats`, `scan_start`, `scan_done`, `duplicates_found`, `done`) flow from worker thread → `_event_q` → browser
- Folder picker uses tkinter subprocess first (works cross-platform), falls back to PowerShell `-Sta` on Windows, then zenity/kdialog on Linux

### Key invariants

- `_ts_from_filename()` is used when there is no JSON sidecar — it extracts a date and both picks the output folder AND writes EXIF (as of recent changes)
- `Stats.no_json` counts files with no JSON AND no filename date (truly undateable); filename-dated files count toward `processed`
- ExifTool subprocess gets `-charset filename=UTF8` on Windows to handle non-ASCII paths
- The `OffsetTimeOriginal=+00:00` tag is always set alongside DateTimeOriginal — critical for Google Photos

---

## Tool 2 — Android (Übertrag)

Package: `com.firebolt141.ubertrag`  
Min SDK: 26 (Android 8)  
Build: Kotlin + Jetpack Compose + Room + DataStore + WorkManager

### Key files

| File | Purpose |
|---|---|
| `util/TakeoutProcessor.kt` | Port of core.py logic; pure functions are unit-testable |
| `util/StorageHelper.kt` | SAF folder creation (`January/January 15` naming), `renameLegacyFolders` |
| `util/ExifFixer.kt` | Stamps EXIF dates onto existing drive files from folder-name-derived date |
| `util/DateExtractor.kt` | Reads EXIF/video metadata date from phone media |
| `repository/SyncRepository.kt` | All business logic wired together; one class, no DI framework |
| `data/AppDatabase.kt` + `QueueDao.kt` | Room DB for the copy queue |
| `data/Prefs.kt` | DataStore for drive URI and date range |
| `service/CopyService.kt` | Foreground service running `SyncRepository.copyPending()` |
| `ui/MainViewModel.kt` | State for Home + Queue screens; uses `combine()` of multiple flows |
| `ui/TakeoutViewModel.kt` | State for the Process Takeout screen |
| `ui/HomeScreen.kt` | Main screen with stats, drive picker, actions, maintenance buttons |
| `ui/QueueScreen.kt` | Queue browser; filter chips (All/Pending/Copied/Skipped/Failed) |
| `ui/TakeoutScreen.kt` | Process Takeout flow — source picker, options, progress, results |

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

New copies go into `2024/January/January 15/`. Legacy numeric folders (`01/15`) can be renamed using `StorageHelper.renameLegacyFolders()`. Both styles are supported in `TakeoutProcessor.collectItems()`.

### State management

`MainViewModel` uses nested `combine()` to merge 5 base flows + `_renameUi` + `_exifFixUi` into a single `UiState`. Add new async operations following the same `MutableStateFlow(false to "")` pattern.

`TakeoutViewModel` is a separate ViewModel used only by `TakeoutScreen` — keeps Takeout state isolated from the main screen.

### Unit tests

Tests live in `app/src/test/` and cover `TakeoutProcessor`'s pure functions:
- `jsonCandidateNames()` — sidecar filename generation
- `parseMeta()` — JSON parsing
- `tsFromFilename()` — filename date extraction

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
- The `Stats.no_json` Python field now means "no date at all" not "no sidecar" — filename-dated files are counted in `processed`
- SAF `openOutputStream(uri, "wt")` requires API 26+ — matches our `minSdk`
