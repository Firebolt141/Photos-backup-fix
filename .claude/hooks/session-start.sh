#!/bin/bash
set -euo pipefail

# Only run in remote (Claude Code on the web) environments
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# ── ExifTool ──────────────────────────────────────────────────────────────────
# Required by app.py at processing time and by _check_exiftool() at startup.
if ! command -v exiftool &>/dev/null; then
  echo "[session-start] Installing ExifTool..."
  if command -v apt-get &>/dev/null; then
    apt-get install -y --no-install-recommends libimage-exiftool-perl
  elif command -v brew &>/dev/null; then
    brew install exiftool
  else
    echo "[session-start] WARNING: could not install ExifTool (no apt-get or brew)."
  fi
else
  echo "[session-start] ExifTool $(exiftool -ver) already installed."
fi

# ── Flask ─────────────────────────────────────────────────────────────────────
# Required by web_app.py (the browser UI entry point).
if ! python3 -c "import flask" &>/dev/null; then
  echo "[session-start] Installing Flask..."
  python3 -m pip install flask --quiet --disable-pip-version-check --ignore-installed
else
  echo "[session-start] Flask $(python3 -c 'import flask; print(flask.__version__)') already installed."
fi

# ── Python sanity check ───────────────────────────────────────────────────────
# app.py requires Python 3.7+; no third-party packages needed.
# tkinter is a GUI library not available in headless server environments —
# that is expected and fine for remote development sessions.
python3 -c "
import sys
assert sys.version_info >= (3, 7), f'Python 3.7+ required, got {sys.version}'
print(f'[session-start] Python {sys.version.split()[0]} OK')
"
