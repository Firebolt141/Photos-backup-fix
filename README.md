# Google Takeout EXIF Restoration Tool

Fixes broken dates, times, and GPS locations on photos and videos exported from Google Photos via **Google Takeout**, so they upload correctly to a new Google Photos account (or any other photo library).

When you download a Google Takeout archive, every media file comes with a `.json` sidecar containing the original metadata. Most upload tools ignore these sidecars, so ten years of photos end up stamped with today's date. This tool reads those JSON files and writes the correct metadata back into each file using **ExifTool**.

The UI runs entirely in your **browser** — no Electron, no cloud, no account needed. All processing happens locally on your Windows machine.

---

## Quick start (Windows)

1. Download the latest release zip from the [Releases](../../releases) page and extract it anywhere.
2. Double-click **`Start.bat`**.
3. The setup script automatically downloads ExifTool, verifies Python, and installs Flask — no manual steps needed.
4. Your browser opens at `http://127.0.0.1:5000`. Select your source and output folders and press **Start Processing**.

> **First run only:** `Start.bat` downloads the ExifTool portable build (~5 MB) into a local `tools\` folder and runs `pip install flask`. Subsequent launches skip these steps and open the browser in a few seconds.

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

## Browser UI

The app runs as a local web server and opens in your default browser. All features of the original desktop app are available:

- **Real-time progress** — animated bar, files/sec speed, current filename
- **Live log** — color-coded by severity (info / ok / warn / error), auto-scrolling
- **Animated stats** — Total · Fixed · No JSON · Errors, counts animate as files are processed
- **Native folder picker** — Browse buttons open the Windows folder dialog
- **Duplicate scanner** — optional pre-scan identifies copies across multiple Takeout exports; a review modal lets you skip or keep them
- **Open Output** — opens the output folder in Windows Explorer when done

The server binds to `127.0.0.1` only — it is not reachable from other devices on your network.

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
- **Flask** — `pip install flask`

### Run

```bat
python web_app.py
```

The server starts and opens your browser automatically. Press `Ctrl+C` in the terminal to stop it.

On Linux/macOS:

```bash
# Install ExifTool
sudo apt install libimage-exiftool-perl   # Debian/Ubuntu
brew install exiftool                      # macOS

pip install flask
python3 web_app.py
```

---

## Project layout

```
Photos-backup-fix/
├── web_app.py          ← primary entry point (Flask web server)
├── core.py             ← all processing logic (no GUI deps)
├── templates/
│   └── index.html      ← browser UI (HTML + CSS + JS, no build tools)
├── app.py              ← legacy tkinter desktop UI (kept for reference)
├── Start.bat           ← Windows double-click launcher
├── setup.ps1           ← PowerShell setup + launcher script
└── install_exiftool.sh ← Linux/macOS ExifTool installer helper
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

**Browser doesn't open automatically** — Navigate to `http://127.0.0.1:5000` manually. Check the terminal window for any error output.

**Port 5000 already in use** — The server will automatically pick the next free port and print the URL in the terminal.

**Some files still show wrong dates after upload** — Google Photos caches metadata on upload. Try removing and re-adding the photos, or wait 24 h for the index to refresh.

**Files with no JSON sidecar** — These are copied unchanged (if the option is ticked). Common causes: edited copies, screenshots, downloaded images, or files added outside the camera app.

**Errors on files with special characters in the path** — Make sure you are using the `Start.bat` launcher or have ExifTool 12.x+. The tool passes `-charset filename=UTF8` automatically on Windows.

---

## License

MIT — see [LICENSE](LICENSE) if present, otherwise use freely with attribution.
