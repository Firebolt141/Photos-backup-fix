# Google Takeout EXIF Restoration Tool

Fixes broken dates, times, and GPS locations on photos and videos exported from Google Photos via **Google Takeout**, so they upload correctly to a new Google Photos account (or any other photo library).

When you download a Google Takeout archive, every media file comes with a `.json` sidecar containing the original metadata. Most upload tools ignore these sidecars, so ten years of photos end up stamped with today's date. This tool reads those JSON files and writes the correct metadata back into each file using **ExifTool**.

---

## Quick start (Windows)

1. Download the latest release zip from the [Releases](../../releases) page and extract it anywhere.
2. Double-click **`Start.bat`**.
3. The setup script will automatically download ExifTool and verify Python — no manual installs needed.
4. Once setup completes the app opens. Select your source and output folders and press **Start Processing**.

> **First run only:** `Start.bat` downloads the ExifTool portable build (~5 MB) into a local `tools\` folder. Subsequent launches skip this step and open the app in a few seconds.

---

## What it fixes

| Tag written | Why it matters |
|---|---|
| `DateTimeOriginal` + `OffsetTimeOriginal=+00:00` | Google Photos uses this as the primary "taken on" date |
| `CreateDate`, `ModifyDate` | Secondary date fields read by Windows Explorer, Lightroom, etc. |
| `Keys:CreationDate` (MP4/MOV) | Apple Photos / QuickTime Player date |
| `QuickTime:CreateDate` + track/media dates | Video timeline dates |
| `GPSLatitude/Longitude/Altitude` | Location shown on map |
| `GPSDateStamp`, `GPSTimeStamp` | GPS time (always UTC per NMEA spec) |

The `OffsetTimeOriginal=+00:00` tag is the critical one most tools miss — without it, timezone-aware apps shift the date by the viewer's local UTC offset, which can move photos to the wrong day.

---

## Screenshots

```
┌─────────────────────────────────────────────────────────────┐
│ 📷  Google Takeout EXIF Restoration                         │
│ Restores original dates, times & GPS from JSON sidecars     │
├─────────────────────────────────────────────────────────────┤
│ Folders                                                     │
│  Source  [ C:\Takeout\Google Photos      ] [ Browse… ]      │
│  Output  [ D:\Photos-fixed               ] [ Browse… ]      │
│                                                             │
│ Options                                                     │
│  ☑ Copy files with no matching JSON (unchanged)             │
│  Output folder organisation:                                │
│  ◉ Organise by date  — output / 2021 / 01 / 15 / photo.jpg  │
│  ○ Preserve original folder structure                       │
│  ○ Flat — all files in one folder                           │
│                                                             │
│  [ ▶ Start Processing ]  [ ■ Stop ]  [ 📂 Open Output ]     │
├──────────┬──────────┬──────────┬──────────────────────────-─┤
│  1 247   │  1 198   │    31    │     18                      │
│  TOTAL   │  FIXED   │ NO JSON  │   ERRORS                    │
├─────────────────────────────────────────────────────────────┤
│ Processing 1198 of 1247  (96%)  ████████████████░░          │
├─────────────────────────────────────────────────────────────┤
│ [info]  ExifTool v12.76                                     │
│ [info]  Found 1247 media files                              │
│ [ok  ]  ✓  2021-01-15 14:32 UTC  |  GPS 37.4219, -122.0840 │
│ [warn]  !  No JSON sidecar — copied as-is                   │
└─────────────────────────────────────────────────────────────┘
```

---

## Output folder structure

With **Organise by date** selected (the default), output is sorted into a clean date tree:

```
output/
├── 2017/
│   └── 07/
│       └── 14/
│           └── IMG_4821.jpg
├── 2021/
│   ├── 01/
│   │   └── 01/
│   │       ├── IMG_0001.jpg
│   │       └── VID_20210101.mp4
│   └── 06/
│       └── 29/
│           └── IMG_0042.jpg
└── no-date/
    └── IMG_unknown.jpg   ← files with no date metadata at all
```

If two files from different source folders land on the same date with the same filename, the second one is automatically renamed `photo_1.jpg`, `photo_2.jpg`, etc.

For files without a JSON sidecar the tool tries to extract the date from the filename itself (e.g. `PXL_20210115_120000.jpg`, `IMG-20210115-WA0000.jpg`). If that also fails the file goes to `no-date/`.

---

## Supported formats

**Images:** JPG · JPEG · PNG · GIF · BMP · TIFF · WEBP · HEIC · HEIF · RAW · CR2 · NEF · ARW · DNG · ORF · RW2 · SRW · PEF · 3FR · IIQ · X3F

**Videos:** MP4 · MOV · AVI · M4V · MKV · WMV · 3GP · MPG · MPEG · MTS · M2TS · FLV · WEBM · TS

---

## Manual installation (if you prefer not to use Start.bat)

### Requirements

- **Python 3.7+** — [python.org/downloads](https://python.org/downloads)  
  *(tick "Add Python to PATH" during setup)*
- **ExifTool** — [exiftool.org](https://exiftool.org)  
  *(Windows: download the executable zip, rename `exiftool(-k).exe` → `exiftool.exe`, place on PATH)*

No Python packages to install — the app uses only the standard library.

### Run

```bat
python app.py
```

Or on Linux/macOS:

```bash
# Install ExifTool
sudo apt install libimage-exiftool-perl   # Debian/Ubuntu
brew install exiftool                      # macOS

python3 app.py
```

---

## How it works

Google Takeout places a `.json` sidecar next to every media file. The tool matches sidecars to their files using all known naming patterns:

| Photo filename | JSON sidecar looked for |
|---|---|
| `photo.jpg` | `photo.jpg.json` → `photo.json` |
| `photo(1).jpg` | `photo.jpg(1).json` |
| `photo-edited.jpg` | `photo.jpg.json` (strips `-edited`) |
| Very long names | Truncated at 46 chars (Google's limit) |
| Newer exports | `photo.jpg.supplemental-metadata.json` |

The JSON `photoTakenTime.timestamp` field (Unix UTC epoch) is used as the authoritative date. `creationTime` is the fallback. GPS comes from `geoDataExif` (preferred) or `geoData`.

All matching and writing is done locally — no data leaves your machine.

---

## Troubleshooting

**"ExifTool not found"** — Run `Start.bat` which downloads it automatically, or install it manually from [exiftool.org](https://exiftool.org).

**Some files still show wrong dates after upload** — Google Photos caches metadata on upload. Try removing and re-adding the photos, or wait 24 h for the index to refresh.

**Files with no JSON sidecar** — These are copied unchanged (if the option is ticked). Common causes: edited copies, screenshots, downloaded images, or files added outside the camera app.

**Errors on files with special characters in the path** — Make sure you are using the `Start.bat` launcher or have ExifTool 12.x+. The tool passes `-charset filename=UTF8` automatically on Windows.

---

## License

MIT — see [LICENSE](LICENSE) if present, otherwise use freely with attribution.
