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
├── android-app/            ← Android app (**Übertrag**)
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

- `_ts_from_filename()` is used when there is no JSON sidecar — it extracts a date and both picks the output folder AND writes EXIF
- `Stats.no_json` counts files with no JSON AND no filename date (truly undateable); filename-dated files count toward `processed`
- ExifTool subprocess gets `-charset filename=UTF8` on Windows to handle non-ASCII paths
- The `OffsetTimeOriginal=+00:00` tag is always set alongside DateTimeOriginal — critical for Google Photos

---

## Tool 2 — Android (**Übertrag**)

Package: `com.firebolt141.ubertrag`  
Min SDK: 26 (Android 8)  
Build: Kotlin + Jetpack Compose + Room + DataStore

### Navigation

**Übertrag** uses a `ModalNavigationDrawer` (hamburger sidebar) with three sections:

| Section | Route | Screen |
|---|---|---|
| Backup | `home` | `HomeScreen` — scan, copy, queue stats, drive picker |
| Backup | `queue` | `QueueScreen` — queue browser with All/Pending/Copied/Skipped/Failed filter chips |
| Drive Utilities | `fix-exif` | `FixExifScreen` — fix missing EXIF (two modes) |
| Drive Utilities | `rename-folders` | `RenameFoldersScreen` — rename numeric → spelled-out folders |
| Import | `takeout` | `TakeoutScreen` — process Google Takeout on-device |

### Key files

| File | Purpose |
|---|---|
| `ui/AppDrawer.kt` | `ModalDrawerSheet` content — all nav items, section labels |
| `ui/SharedComponents.kt` | `DriveStatusCard`, `FolderPickerCard` — shared between screens |
| `ui/HomeScreen.kt` | Scan/copy/retry stats + drive picker; hamburger opens drawer |
| `ui/QueueScreen.kt` | Queue browser; filter chips (All/Pending/Copied/Skipped/Failed) |
| `ui/MainViewModel.kt` | State for Home + Queue + Rename screens via `combine()` flows |
| `ui/FixExifScreen.kt` | Fix EXIF screen — mode toggle, pickers, progress, live log |
| `ui/FixExifViewModel.kt` | State for `FixExifScreen`; isolated from `MainViewModel` |
| `ui/RenameFoldersScreen.kt` | Rename numeric legacy folders; reads rename state from `UiState` |
| `ui/TakeoutScreen.kt` | Process Takeout — source + output pickers, options, progress, result |
| `ui/TakeoutViewModel.kt` | State for `TakeoutScreen`; completely isolated |
| `util/TakeoutProcessor.kt` | Port of core.py logic; pure functions are unit-testable |
| `util/StorageHelper.kt` | SAF folder creation (`January/January_07` naming), `renameLegacyFolders` |
| `util/ExifFixer.kt` | `fixMissingExif()` (drive-structure mode) + `fixByFilename()` (filename-date mode) |
| `util/DateExtractor.kt` | Reads EXIF/video metadata date from phone media |
| `repository/SyncRepository.kt` | All business logic wired together; one class, no DI framework |
| `data/AppDatabase.kt` + `QueueDao.kt` | Room DB for the copy queue |
| `data/Prefs.kt` | DataStore for drive URI and date range |
| `service/CopyService.kt` | Foreground service running `SyncRepository.copyPending()` |

### Fix EXIF — two modes

`FixExifScreen` has a toggle ("Filename Date Mode"):

| Mode | Toggle | How it works |
|---|---|---|
| **Drive-structure mode** (OFF) | Drive must be connected | Reads `year/month/day` folder names, stamps EXIF on files missing it |
| **Filename Date mode** (ON) | Source + output folder pickers | Extracts date from filename (`IMG_20240315_…`), copies to `year/month/day/`, writes EXIF |

`ExifFixer.fixByFilename()` calls `TakeoutProcessor.tsFromFilename()` for date extraction and `StorageHelper.resolveDestDir()` for folder creation. It emits `onLog()` messages (`✓`, `→`, `⚠`, `✗` prefixed) that feed the live log panel in the UI.

The log panel (`LogPanel` composable in `FixExifScreen.kt`) is a 220dp-tall monospace `LazyColumn` that auto-scrolls on each new line via `LaunchedEffect(lines.size)`.

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

New copies go into `2024/January/January_07/` (zero-padded day). Legacy numeric folders (`01/15`) can be renamed using `StorageHelper.renameLegacyFolders()`.

`ExifFixer.parseDayFolderNum()` accepts all three formats:
- `January_07` — current format
- `January 7` — old format (still accepted for existing drives)
- `15` — legacy numeric

### State management

`MainViewModel` uses nested `combine()` to merge 5 base flows + `_renameUi` into a single `UiState`. The `_renameUi` flow is a `MutableStateFlow(false to "")` pair (running flag + status message). Add new async operations following the same pattern.

`FixExifViewModel` and `TakeoutViewModel` are separate ViewModels — they do NOT share state with `MainViewModel`. Keep them isolated.

### Shared composables

`SharedComponents.kt` contains composables used by more than one screen:
- `DriveStatusCard(state: UiState, onDriveSelected?)` — drive connection card
- `FolderPickerCard(title, subtitle, icon, name, enabled, onPick)` — folder picker card

**Do not** add `FolderPickerCard` as a `private` function inside any single screen — it is shared between `FixExifScreen` and `TakeoutScreen`.

### App icon

The launcher icon is a cartoon **German Shepherd** face (front-facing, tan/black coloring, pink ears, tongue out) on a warm amber background (`#C8831A`).

Icon assets live in `app/src/main/res/mipmap-*/`:
- `ic_launcher.png` — legacy launcher icon at each density (48 / 72 / 96 / 144 / 192 px)
- `ic_launcher_round.png` — same image, round crop applied by launcher
- `ic_launcher_foreground.png` — adaptive icon foreground at 108dp per density (108 / 162 / 216 / 324 / 432 px)
- `mipmap-anydpi-v26/ic_launcher.xml` — adaptive icon XML referencing `@mipmap/ic_launcher_foreground` + `@color/ic_launcher_background`

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
- **Do not** add `FolderPickerCard` as `private` to a single screen file — it lives in `SharedComponents.kt`
- The `Stats.no_json` Python field now means "no date at all" not "no sidecar" — filename-dated files are counted in `processed`
- SAF `openOutputStream(uri, "wt")` requires API 26+ — matches our `minSdk`
- Day folders use `Month_DD` format (`January_07`), not `Month D` (`January 7`) — parsers accept both
