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
- **Output organized by date** — `2024/01/15/photo.jpg`
- **Processing report** — `_processing_report.json` written to output folder

### Output structure

```
output/
├── 2024/
│   └── 01/
│       └── 15/
│           └── photo.jpg
└── no-date/
    └── unknown.jpg
```

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

## Tool 2 — Android: Übertrag

An Android app (min SDK 26 / Android 8) that backs up phone photos to an external drive **and** processes Google Takeout exports directly on-device — no computer needed for the second use case.

### Features

#### Phone → Drive backup
- Scans phone media via MediaStore
- Copies to a USB/SD drive organised by date (`2024/January/January 15/`)
- Tracks each file as `PENDING → COPIED / SKIPPED / FAILED`
- Date range filter for selective backup
- Skips files already present at the destination (idempotent)

#### Google Takeout processing (on-device)
- Points at a Takeout folder anywhere SAF can reach (phone storage, SD card)
- Finds JSON sidecars using the same matching logic as the Windows tool
- Writes `DateTimeOriginal`, UTC offset, and GPS to **JPEG / PNG / WebP** files
- Falls back to filename date (`IMG_20240315_…`) when no sidecar exists
- **"Skip files that already have a date"** toggle — only processes missing metadata
- Copies all files (HEIC, video, RAW) to correct date folders even when EXIF can't be written

#### Drive maintenance
- **Rename Old Month/Day Folders** — renames `01/15` → `January/January 15` on the drive
- **Fix Missing EXIF Dates** — stamps EXIF date from folder name for files already on the drive

### Supported formats for EXIF writing

| Format | Date | GPS | Notes |
|---|---|---|---|
| JPEG / JPG | ✓ | ✓ | Full support |
| PNG | ✓ | ✓ | ExifInterface 1.3.0+ |
| WebP | ✓ | ✓ | ExifInterface 1.3.0+ |
| HEIC / HEIF | ✗ | ✗ | Read-only in ExifInterface |
| RAW formats | ✗ | ✗ | Read-only in ExifInterface |
| Video | ✗ | ✗ | No native Android metadata write API |

Files in unsupported formats are always copied to the correct date folder.

### Architecture

```
android-app/app/src/main/java/com/firebolt141/ubertrag/
├── data/
│   ├── AppDatabase.kt      ← Room database
│   ├── QueueDao.kt         ← DAO for queue items
│   ├── QueueItem.kt        ← Entity (PENDING/COPIED/SKIPPED/FAILED)
│   └── Prefs.kt            ← DataStore (drive URI, date range)
├── repository/
│   └── SyncRepository.kt   ← All business logic (scan, copy, Takeout, rename, EXIF fix)
├── service/
│   └── CopyService.kt      ← Foreground service for copy operations
├── ui/
│   ├── HomeScreen.kt       ← Main screen
│   ├── QueueScreen.kt      ← Queue browser with filter chips
│   ├── TakeoutScreen.kt    ← Process Takeout screen
│   ├── MainViewModel.kt    ← State for HomeScreen + QueueScreen
│   └── TakeoutViewModel.kt ← State for TakeoutScreen
└── util/
    ├── StorageHelper.kt    ← SAF helpers, folder creation/rename
    ├── DateExtractor.kt    ← EXIF + video metadata date reading
    ├── ExifFixer.kt        ← Fix missing EXIF on existing drive files
    └── TakeoutProcessor.kt ← Takeout processing (pure + Android functions)
```

### Building

Open `android-app/` in Android Studio. Requires Android Studio Hedgehog or later.

```bash
cd android-app
./gradlew assembleDebug
```

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

**Android — HEIC / video files not getting dates** — This is a platform limitation. The files are still copied to the correct date folder based on the Takeout JSON timestamp.

**Both tools — Some files still show wrong dates after upload** — Google Photos caches metadata. Try removing and re-adding the photos, or wait 24 h for the index to refresh.

---

## License

MIT
