# Photos Backup Fix — Dev Workflow

You are working on **firebolt141/photos-backup-fix**, a Python/tkinter desktop app that restores EXIF metadata from Google Takeout JSON sidecars.

## Project layout

| File | Purpose |
|---|---|
| `app.py` | Main GUI app (tkinter). All core logic lives here. |
| `Start.bat` | Windows double-click launcher |
| `setup.ps1` | PowerShell setup script: installs ExifTool, finds Python, launches app |
| `install_exiftool.sh` | Linux/macOS ExifTool installer helper |
| `android-app/` | Companion Android app (Kotlin) |

## Active development branch

`claude/fix-startup-exiftool-iR2c0`

Always develop on this branch. Never push to `main` directly.

## Your task

$ARGUMENTS

## Workflow

1. **Understand** — read the relevant code before changing anything.
2. **Implement** — make the smallest correct change. No refactors beyond what's asked.
3. **Verify** — run `python3 -m py_compile app.py` to catch syntax errors. If the change touches `setup.ps1`, review the PowerShell logic manually.
4. **Commit** — write a clear commit message explaining *why*, not just *what*. Include the session URL at the end.
5. **Push** — `git push -u origin claude/fix-startup-exiftool-iR2c0`

## Key constraints

- `app.py` uses **only Python standard library** — no pip installs ever.
- ExifTool is an external binary; the app checks for it via `_check_exiftool()` (module-level function).
- `setup.ps1` must stay compatible with **Windows PowerShell 5.1** (the default on Windows 10/11).
- `Start.bat` → `setup.ps1` → `app.py` is the Windows launch chain. Keep all three in sync.
- The GUI is tkinter — no web framework, no Electron, no other UI toolkit.

## GitHub tools available

Use `mcp__github__*` tools to read issues, review PRs, and post comments without leaving this chat.
