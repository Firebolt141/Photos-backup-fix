# Photos Backup Fix

Two tools for rescuing Google Photos metadata and backing up your phone photos — one for Windows (desktop), one for Android.

---

## Tool 1 — Windows: Photos Backup Fix (desktop)

A local browser app (Flask + ExifTool) with five tools. It shares the Android app's folder layout, so both can work on the same backup drive.

| Tool | What it does |
|---|---|
| 📦 **Process Takeout** | Copies a Google Takeout export into dated folders and writes the date, GPS, caption, people and favourite rating from Google's JSON sidecars |
| 🗓️ **Sort by Filename Date** | For Screenshots/WhatsApp/camera folders: takes the date from names like `IMG_20240315_143022.jpg`, sorts the files and writes the date into them |
| ✨ **Fix Missing Dates** | Walks an existing `year/month/day` drive and writes the folder's date **in place** into files that have none (JPEG, PNG, HEIC, RAW, MP4/MOV) |
| 🏷️ **Rename Old Folders** | `2024/01/15` → `2024/January/January_15`, merging safely when both layouts exist |
| 🧬 **Find Duplicates** | Finds byte-identical photos/videos by content hash; can move the extra copies to `_duplicates/` (never deletes) |

Every tool has a **Preview** mode (dry run), and every run writes a JSON + CSV report to `_photofix/` in the output or drive folder.

### The app

- **Overview page:** pick what you're trying to do ("I downloaded my Google Photos with Takeout", "Photos on my backup drive have no date", …). It also shows recent runs and this computer's ExifTool status.
- **Each tool walks you through three steps:** choose folders, options, run.
  - **Folder checks as you type:** photo count and size, Takeout sidecars found, free space at the output, and old-style folders on a drive (with a one-click jump to *Rename old folders*).
  - **Options:** the common ones are visible, the rest sit under *Advanced options*. A live example path shows what the output layout will look like.
  - **Preview first, then Apply:** after a preview, the results card offers **Apply for real** in one click. Tools that edit a drive in place ask for confirmation first.
- **Activity panel:**
  - Status, elapsed time, progress, speed and time remaining
  - Pause/Resume and Stop
  - A plain-English summary of the results
  - Clickable stat tiles (hover them for an explanation) that open a searchable per-file results list, which can be copied into Excel
  - A log filtered by warnings or errors, with search
- **Light and dark themes** (or follow the system), a phone-width layout, and no internet needed.

### Options worth knowing

| Option | Tools | What it does |
|---|---|---|
| Folder layout | Takeout, Sort | `2024/March/March_15` (default), `2024/03/15`, keep Takeout's folders, or one folder |
| Keep dates already in the file | Takeout, Sort | Camera dates win; only missing GPS/caption is added |
| Rename files to their date | Takeout, Sort | `IMG_1234.JPG` → `2019-07-04_10-30-00.jpg` |
| Which files | Takeout, Sort | Photos & videos / photos only / videos only |
| Only photos taken between | Takeout, Sort | Process one year (or any date range) at a time |
| Check for duplicates first | Takeout | Review renamed identical copies before processing |
| Also correct dates that disagree with their folder | Fix missing dates | Moves a wrong date to the folder's day, keeping the time of day |
| Move the extra copies | Find duplicates | Moves duplicates to `_duplicates/` instead of only reporting |

### Quick start (Windows)

1. Download the repo zip and extract it anywhere.
2. Double-click **`Start.bat`**. It downloads ExifTool, installs Flask and opens the browser.
3. Pick a tool in the sidebar, choose your folders and press **Start** (or tick **Dry run** first).

### Output layout

```
Output/
├── 2024/March/March_15/IMG_20240315_143022.jpg   ← same layout as the Android app
├── no-date/      ← files with no date anywhere (sidecar, filename or embedded)
├── error/        ← files that could not be copied
└── _photofix/    ← reports (report-*.json/.csv) + manifest.json for safe re-runs
```

Choose **Numeric** for the old `2024/03/15` layout, or **Preserve** / **Flat**.

### Where the date comes from (in order)

1. A date **already embedded** in the file (kept unless you turn off *Keep dates already in the file*; only missing GPS/caption is added)
2. Takeout `photoTakenTime`
3. The **filename**: `IMG_20240315_143022`, `PXL_…`, `Screenshot_2024-03-15-14-30-22`, `VID-20240315-WA0001`, epoch-millisecond names. Times in filenames are read as this PC's local time; a date with no time becomes 12:00 UTC, the same as the Android app.
4. Takeout `creationTime` (upload date, the last resort)

### What it writes

| Tag | Why it matters |
|---|---|
| `DateTimeOriginal` + `OffsetTimeOriginal` (`+00:00` for sidecar dates) | Primary "taken on" date in Google Photos |
| `CreateDate`, `ModifyDate`, `OffsetTime*` | Secondary dates (Explorer, Lightroom) |
| QuickTime `CreateDate` (UTC) + `Keys:CreationDate` (MP4/MOV) | Video dates in Google/Apple Photos |
| `GPSLatitude/Longitude/Altitude` / `Keys:GPSCoordinates` (video) | Location on the map |
| `ImageDescription`, `XMP:PersonInImage`, `XMP:Rating=5` | Caption, people, favourites |
| File modified time | Sorting in Explorer and apps that ignore EXIF |

### Highlights

- **Fast:** one long-running ExifTool process per worker (`-stay_open`), with parallel workers. That is roughly 10–50× faster than starting ExifTool once per file.
- **Safe re-runs:** stop at any time, then press Start again to continue. Files already in the output are skipped (tracked in `_photofix/manifest.json`) instead of being copied again as `photo_1.jpg`. Files are written under a temporary name and renamed when complete, so an interrupted run never leaves half-written photos behind.
- **Duplicates handled:** the same photo appearing in "Photos from 2019" and in an album is copied once. Different photos with the same name both survive (`IMG_1.jpg`, `IMG_1_1.jpg`). Optional pre-scan for identical files with different names.
- **Better sidecar matching:** truncated `.supplemental-metad.json` names, `(1)` numbering, the 46-character limit, `-edited` in 15 languages, Live Photo videos (`IMG_1.MP4` uses `IMG_1.HEIC.json`), and the JSON `title` field as a last resort.
- **Locked down:** listens on 127.0.0.1 only. Every API call needs a random per-launch token and a localhost `Host` header, so other websites can't drive it.

### Command line

The same engine is available without the browser:

```bat
python cli.py takeout  D:\Takeout E:\Photos --dry-run
python cli.py takeout  D:\Takeout E:\Photos --from 2019-01-01 --to 2019-12-31 --rename-to-date
python cli.py sort     D:\Screenshots E:\Photos --only photos
python cli.py fix-dates E:\Photos --fix-mismatched
python cli.py rename   E:\Photos
python cli.py dupes    E:\Photos --move
python cli.py takeout --help        &:: all options
```

### Manual setup

Requires Python 3.7+, Flask (`pip install flask`) and ExifTool (on PATH or in `.\tools\`).

```bat
python web_app.py [--port 5000] [--no-browser]
```

Tests: `pip install pytest` and then `python -m pytest tests/`. The integration tests need ExifTool, and the video test needs ffmpeg.

### Project layout

```
Photos-backup-fix/
├── web_app.py          ← Flask web server (entry point)
├── core.py             ← All processing logic (no GUI deps)
├── cli.py              ← Command-line interface
├── templates/index.html← Browser UI
├── tests/              ← pytest suite
├── app.py              ← Legacy tkinter UI (Takeout only)
├── Start.bat           ← Windows launcher
└── setup.ps1           ← PowerShell setup script
```

### How sidecar matching works

| Photo filename | JSON tried |
|---|---|
| `photo.jpg` | `photo.jpg.json` → `photo.json` → `photo.jpg.supplemental-metadata.json` → any truncation like `photo.jpg.suppl.json` |
| `photo(1).jpg` | `photo.jpg(1).json` → `photo(1).json` → `photo.jpg.supplemental-metadata(1).json` |
| `photo-edited.jpg` | the original's sidecar (`-edited`, `-bearbeitet`, `-modifié`, …) |
| Name > 46 chars | truncated at 46 chars (Google's limit) |
| `IMG_1.MP4` (Live Photo) | `IMG_1.HEIC.json` |
| anything else | the sidecar whose `title` field equals the filename |

---

## Tool 2 — Android: **Übertrag**

**Übertrag** is an Android app (min SDK 26 / Android 8) that backs up phone photos to an external drive **and** repairs missing EXIF dates — no computer needed.

The app icon is a cartoon **German Shepherd** face on a warm amber background.

### Features

#### Phone → Drive backup (`Copy to Drive`)
- Scans phone media via MediaStore
- Copies to a USB/SD drive organised by date (`2024/January/January_07/`)
- Tracks each file as `PENDING → COPIED / SKIPPED / FAILED`
- Date range filter for selective backup
- Skips files already present at the destination (idempotent)
- Copy speed displayed in MB/s during transfer

#### Google Takeout processing on-device (`Process Google Takeout`)
- Points at a Takeout folder anywhere SAF can reach (phone storage, SD card, USB drive)
- Finds JSON sidecars using the same matching logic as the Windows tool
- Writes `DateTimeOriginal`, UTC offset, and GPS to **JPEG / PNG / WebP** files
- Falls back to filename date (`IMG_20240315_…`) when no sidecar exists
- **"Skip files that already have a date"** toggle
- Copies all files (HEIC, video, RAW) to correct date folders even when EXIF can't be written

#### Fix Missing EXIF Dates (`Fix Missing EXIF Dates`)

Two modes selectable with a toggle:

| Mode | Description |
|---|---|
| **Drive-structure mode** (default) | Reads `year / month / day` folder names from your connected drive and stamps EXIF dates on JPEG/PNG/WebP files that are missing them |
| **Filename Date mode** | Scans any flat folder (Screenshots, WhatsApp exports, etc.), extracts the date from each filename, copies files to an output folder organised as `year / month / day`, and writes EXIF |

Both modes show a **real-time scrollable log panel** inside the app so you can see what's happening file by file.

#### Rename Legacy Folders (`Rename Drive Folders`)
- Renames old numeric month/day folders to spelled-out names
- `2024/01/15` → `2024/January/January_07` (zero-padded day)
- Day folders renamed first, then month folders (correct ordering)

### Navigation

All features are accessible via a **hamburger sidebar** (`☰` button in every top bar):

```
≡ Übertrag
  ─ Backup
    📱 Copy to Drive
    📋 View Queue
  ─ Drive Utilities
    ✨ Fix Missing EXIF Dates
    🏷  Rename Drive Folders
  ─ Import
    📦 Process Google Takeout
```

### Supported formats for EXIF writing

| Format | Date | GPS | Notes |
|---|---|---|---|
| JPEG / JPG | ✓ | ✓ | Full support |
| PNG | ✓ | ✓ | ExifInterface 1.3.7+ |
| WebP | ✓ | ✓ | ExifInterface 1.3.7+ |
| HEIC / HEIF | ✗ | ✗ | Read-only in ExifInterface |
| RAW formats | ✗ | ✗ | Read-only in ExifInterface |
| Video | ✗ | ✗ | No native Android metadata write API |

Files in unsupported formats are always copied to the correct date folder.

### Architecture

```
android-app/app/src/main/java/com/firebolt141/ubertrag/
├── data/
│   ├── AppDatabase.kt          ← Room database
│   ├── QueueDao.kt             ← DAO for queue items
│   ├── QueueItem.kt            ← Entity (PENDING/COPIED/SKIPPED/FAILED)
│   └── Prefs.kt                ← DataStore (drive URI, date range)
├── repository/
│   └── SyncRepository.kt       ← All business logic (scan, copy, Takeout, rename, EXIF fix)
├── service/
│   └── CopyService.kt          ← Foreground service for copy operations
├── ui/
│   ├── AppDrawer.kt            ← Hamburger sidebar content
│   ├── SharedComponents.kt     ← DriveStatusCard + FolderPickerCard (shared)
│   ├── HomeScreen.kt           ← Scan/copy/retry + drive picker
│   ├── QueueScreen.kt          ← Queue browser with filter chips
│   ├── MainViewModel.kt        ← State for Home + Queue + Rename
│   ├── FixExifScreen.kt        ← Fix EXIF (drive mode + filename mode + live log)
│   ├── FixExifViewModel.kt     ← State for FixExifScreen
│   ├── RenameFoldersScreen.kt  ← Rename legacy numeric folders
│   ├── TakeoutScreen.kt        ← Process Takeout screen
│   └── TakeoutViewModel.kt     ← State for TakeoutScreen
└── util/
    ├── StorageHelper.kt        ← SAF helpers, folder creation/rename
    ├── DateExtractor.kt        ← EXIF + video metadata date reading
    ├── ExifFixer.kt            ← fixMissingExif() + fixByFilename()
    └── TakeoutProcessor.kt     ← Takeout processing (pure + Android functions)
```

### Building

Open `android-app/` in Android Studio (Hedgehog or later).

```bash
cd android-app
./gradlew assembleDebug
```

Dev builds are published automatically on every push to `main` as a GitHub release tagged `build-N-<sha>`.

### Running unit tests

```bash
cd android-app
./gradlew test
```

Tests cover `TakeoutProcessor`'s pure functions: JSON sidecar name generation, sidecar parsing, and filename date extraction.

---

## Troubleshooting

**Windows — "ExifTool not found"** — Run `Start.bat` which downloads it automatically.

**Windows — Browser doesn't open** — Open the URL printed in the console window (usually `http://127.0.0.1:5000`; another port is picked if 5000 is busy). Opening the bare URL is fine — the page carries its own access token.

**Android — Drive not showing as connected** — Disconnect and reconnect the drive, then tap "Change Drive" to re-grant SAF permission.

**Android — Fix EXIF returns 0 files on a flat folder** — Switch to **Filename Date mode** (the toggle at the top of the Fix Missing EXIF Dates screen). Drive-structure mode requires `year/month/day` subfolders.

**Android — HEIC / video files not getting dates** — This is a platform limitation. The files are still copied to the correct date folder based on the Takeout JSON timestamp. Plug the drive into a PC and run **Fix Missing Dates** in the Windows tool. It writes the folder date into HEIC, RAW and video files too.

**Both tools — Some files still show wrong dates after upload** — Google Photos caches metadata. Try removing and re-adding the photos, or wait 24 h for the index to refresh.

---

## License

MIT
