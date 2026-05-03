# Photos Backup Fix — Dev Workflow

You are working on **firebolt141/photos-backup-fix**, a tool that restores EXIF metadata (dates, times, GPS) from Google Takeout JSON sidecars. The primary UI is now a **browser-based web app** (Flask + SSE + vanilla JS). A legacy tkinter desktop UI (`app.py`) is kept for reference but is no longer the default launch target.

## Architecture

| File | Purpose |
|---|---|
| `core.py` | **All processing logic** — no GUI deps. Imported by both UIs. |
| `web_app.py` | **Primary entry point.** Flask server + SSE streaming. Opens browser at `localhost:5000`. |
| `templates/index.html` | Full browser UI — dark theme, animated stats, real-time log, duplicate modal. |
| `app.py` | Legacy tkinter desktop UI. Imports from `core.py`. Not launched by default. |
| `Start.bat` | Windows double-click launcher → `setup.ps1` → `web_app.py` |
| `setup.ps1` | PowerShell setup: installs ExifTool + Python + Flask, launches `web_app.py` |
| `install_exiftool.sh` | Linux/macOS ExifTool installer helper |
| `android-app/` | Companion Android app (Kotlin) |

## Key data flow

```
Start.bat → setup.ps1
  ├─ installs ExifTool (portable .exe if needed)
  ├─ installs Python (winget if needed)
  ├─ pip install flask
  └─ python web_app.py
       ├─ opens http://127.0.0.1:5000 in browser
       ├─ POST /api/start → Processor (from core.py) runs in a thread
       └─ GET  /api/stream → SSE → browser updates live
```

## Active development branch

`claude/fix-startup-exiftool-iR2c0`

Always develop on this branch. Never push to `main` directly.

## Your task

$ARGUMENTS

## Workflow

1. **Understand** — read the relevant code before changing anything.
2. **Implement** — make the smallest correct change. No refactors beyond what's asked.
3. **Verify** — run `python3 -m py_compile core.py web_app.py app.py` to catch syntax errors. Logic that touches `core.py` can be smoke-tested via `python3 -c "from core import Processor, _check_exiftool; print('OK')"`.
4. **Commit** — clear message explaining *why*, not just *what*. Session URL at the end.
5. **Push** — `git push -u origin claude/fix-startup-exiftool-iR2c0`

## Key constraints

- **`core.py`** — stdlib only, no tkinter, no flask. Must stay importable in headless environments.
- **`web_app.py`** — only non-stdlib dependency is `flask`. Keep it that way.
- **`app.py`** — tkinter GUI. Imports everything from `core.py`; add no duplicate logic here.
- **`setup.ps1`** — must stay compatible with **Windows PowerShell 5.1** (default on Windows 10/11).
- **`Start.bat → setup.ps1 → web_app.py`** is the Windows launch chain. Keep all three in sync.
- The browser UI (`templates/index.html`) is self-contained — embedded CSS + JS, no build tools.

## GitHub tools available

Use `mcp__github__*` tools to read issues, review PRs, and post comments without leaving this chat.
