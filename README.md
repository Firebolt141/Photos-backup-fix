# Photos Backup Fix

Two tools for rescuing Google Photos metadata and backing up your phone photos — one for Windows (desktop), one for Android.

---

## Tool 1 — Windows: Google Takeout EXIF Restoration

Fixes broken dates, GPS, and descriptions on photos exported via **Google Takeout** by reading each file's `.json` sidecar and writing the correct metadata using **ExifTool**.

When Google Takeout archives a photo it strips the EXIF and puts the real date in a JSON sidecar. Most upload tools ignore these sidecars, so years of photos end up stamped with today's date. This tool reads the sidecars and writes everything back.

### Quick start (Windows)

1. Download the latest release zip and extract it anywhere.
2. Double-click **`Start.bat`** — it downloads ExifTool, installs Flask, and opens the browser.
3. Select your Takeout folder as **Source** and choose an **Output** folder.
4. Press **Start Processing**.

### What it writes

| Tag | Why it matters |
|---|---|
| `DateTimeOriginal` + `OffsetTimeOriginal=+00:00` | Primary "taken on" date in Google Photos |
| `CreateDate`, `ModifyDate` | Secondary dates (Explorer, Lightroom) |
| `Keys:CreationDate` (MP4/MOV) | Apple Photos / QuickTime |
| `GPSLatitude/Longitude/Altitude` | Location on map |
| `ImageDescription` | Caption from Takeout JSON |

The `OffsetTimeOriginal=+00:00` tag is critical — without it, timezone-aware apps shift the photo to the wrong day.

### Features

- **Filename fallback** — if no JSON sidecar exists, extracts date from the filename (`IMG_20240315_…`)
- **Duplicate scanner** — optional pre-scan with a review modal to skip or keep pairs
- **Real-time browser log** — SSE-streamed progress with color-coded lines
- **Native folder picker** — Browse buttons open the OS folder dialog
- **Processing report** — `_processing_report.json` written to output folder

### Supported formats

**Images:** JPG · PNG · GIF · BMP · TIFF · WEBP · HEIC · RAW · CR2 · NEF · ARW · DNG · ORF · and more

**Videos:** MP4 · MOV · AVI · M4V · MKV · WMV · 3GP · MTS · M2TS · WEBM · and more

### Manual setup

Requires Python 3.7+, Flask (`pip install flask`), and ExifTool.

```bat
python web_app.py       # Windows
python3 web_app.py      # Linux / macOS
```

### Project layout

```
Photos-backup-fix/
├── web_app.py          ← Flask web server (entry point)
├── core.py             ← Processing logic (no GUI deps, unit-testable)
├── templates/
│   └── index.html      ← Browser UI
├── Start.bat           ← Windows launcher
└── setup.ps1           ← PowerShell setup script
```

### How sidecar matching works

| Photo filename | JSON candidates tried |
|---|---|
| `photo.jpg` | `photo.jpg.json` → `photo.json` |
| `photo(1).jpg` | `photo.jpg(1).json` → `photo(1).json` |
| `photo-edited.jpg` | `photo.jpg.json` (strips `-edited`) |
| Name > 46 chars | Truncated at 46 chars (Google's limit) |
| Newer exports | `photo.jpg.supplemental-metadata.json` |

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

**Windows — Browser doesn't open** — Navigate to `http://127.0.0.1:5000` manually.

**Android — Drive not showing as connected** — Disconnect and reconnect the drive, then tap "Change Drive" to re-grant SAF permission.

**Android — Fix EXIF returns 0 files on a flat folder** — Switch to **Filename Date mode** (the toggle at the top of the Fix Missing EXIF Dates screen). Drive-structure mode requires `year/month/day` subfolders.

**Android — HEIC / video files not getting dates** — This is a platform limitation. The files are still copied to the correct date folder based on the Takeout JSON timestamp.

**Both tools — Some files still show wrong dates after upload** — Google Photos caches metadata. Try removing and re-adding the photos, or wait 24 h for the index to refresh.

---

## License

MIT
