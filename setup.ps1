#Requires -Version 3.0
<#
.SYNOPSIS
    One-shot setup and launcher for the Google Takeout EXIF Restoration Tool.

.DESCRIPTION
    1. Checks for ExifTool; downloads the portable Windows build if missing.
    2. Checks for Python 3.7+; installs via winget if missing.
    3. Launches app.py.

    Run via Start.bat (which sets -ExecutionPolicy Bypass automatically).
#>

$ErrorActionPreference = 'Stop'
# Force TLS 1.2 so Invoke-WebRequest works on older Windows builds
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$AppDir    = $PSScriptRoot
$ToolsDir  = Join-Path $AppDir 'tools'
$ExifExe   = Join-Path $ToolsDir 'exiftool.exe'
$AppScript = Join-Path $AppDir  'app.py'

# ── Helpers ───────────────────────────────────────────────────────────────────

function Write-Header {
    Write-Host ''
    Write-Host '  ╔══════════════════════════════════════════════════╗' -ForegroundColor DarkCyan
    Write-Host '  ║   Google Takeout EXIF Restoration Tool           ║' -ForegroundColor White
    Write-Host '  ║   First-run setup                                ║' -ForegroundColor DarkGray
    Write-Host '  ╚══════════════════════════════════════════════════╝' -ForegroundColor DarkCyan
    Write-Host ''
}

function Write-Step([int]$n, [string]$msg) {
    Write-Host "  [$n/2] $msg" -ForegroundColor Cyan
}

function Write-OK([string]$msg) {
    Write-Host "        OK  $msg" -ForegroundColor Green
}

function Write-Warn([string]$msg) {
    Write-Host "         !  $msg" -ForegroundColor Yellow
}

function Write-Fail([string]$msg) {
    Write-Host "         X  $msg" -ForegroundColor Red
}

Write-Header

# ── Step 1: ExifTool ──────────────────────────────────────────────────────────
Write-Step 1 'ExifTool'

$exifCmd = $null

# Check system PATH first
if (Get-Command exiftool -ErrorAction SilentlyContinue) {
    $exifCmd = 'exiftool'
    $ver = & exiftool -ver 2>$null
    Write-OK "System ExifTool v$ver"
}

# Check local tools\ folder
if (-not $exifCmd -and (Test-Path $ExifExe)) {
    $ver = & $ExifExe -ver 2>$null
    Write-OK "Local ExifTool v$ver  (.\tools\exiftool.exe)"
    $exifCmd = $ExifExe
}

# Download ExifTool portable build
if (-not $exifCmd) {
    Write-Warn 'ExifTool not found — downloading portable build...'
    try {
        New-Item -ItemType Directory -Path $ToolsDir -Force | Out-Null

        # ver.txt always contains the current stable version number
        $version = (Invoke-WebRequest -Uri 'https://exiftool.org/ver.txt' `
                        -UseBasicParsing).Content.Trim()
        $zipUrl  = "https://exiftool.org/exiftool-$version.zip"
        $zipFile = Join-Path $ToolsDir 'exiftool.zip'

        Write-Warn "  Fetching ExifTool $version from exiftool.org ..."
        Invoke-WebRequest -Uri $zipUrl -OutFile $zipFile -UseBasicParsing

        Expand-Archive -Path $zipFile -DestinationPath $ToolsDir -Force
        Remove-Item $zipFile -Force

        # The zip ships the exe as "exiftool(-k).exe"; rename it
        $found = Get-ChildItem $ToolsDir -Filter '*.exe' | Select-Object -First 1
        if ($found -and ($found.Name -ne 'exiftool.exe')) {
            Rename-Item $found.FullName 'exiftool.exe' -Force
        }

        $ver     = & $ExifExe -ver 2>$null
        $exifCmd = $ExifExe
        Write-OK "ExifTool $ver downloaded to .\tools\"
    }
    catch {
        Write-Fail "Download failed: $_"
        Write-Host ''
        Write-Host '  Install ExifTool manually then re-run this script:' -ForegroundColor Yellow
        Write-Host '    1. Go to  https://exiftool.org' -ForegroundColor Yellow
        Write-Host '    2. Download the Windows Executable zip' -ForegroundColor Yellow
        Write-Host '    3. Extract, rename exiftool(-k).exe -> exiftool.exe' -ForegroundColor Yellow
        Write-Host "    4. Place exiftool.exe in:  $ToolsDir" -ForegroundColor Yellow
        Write-Host ''
        Read-Host 'Press Enter to exit'
        exit 1
    }
}

# Make sure local tools\ is on PATH for this session
if ($exifCmd -eq $ExifExe) {
    $env:PATH = "$ToolsDir;$env:PATH"
}

# ── Step 2: Python ────────────────────────────────────────────────────────────
Write-Step 2 'Python 3.7+'

$python    = $null
$minPyVer  = [Version]'3.7.0'

foreach ($cmd in @('py', 'python', 'python3')) {
    try {
        $raw = & $cmd '--version' 2>&1
        if ("$raw" -match '(\d+\.\d+\.\d+)') {
            if ([Version]$Matches[1] -ge $minPyVer) {
                $python = $cmd
                Write-OK "Python $($Matches[1])  ($cmd)"
                break
            }
            Write-Warn "Python $($Matches[1]) is too old (need 3.7+)"
        }
    } catch { }
}

if (-not $python) {
    Write-Warn 'Python 3.7+ not found — trying winget install...'
    try {
        winget install --id Python.Python.3.13 -e --silent `
            --accept-package-agreements --accept-source-agreements 2>&1 | Out-Null

        # Reload PATH from registry so the new Python is visible
        $env:PATH = [System.Environment]::GetEnvironmentVariable('PATH', 'Machine') +
                    ';' +
                    [System.Environment]::GetEnvironmentVariable('PATH', 'User')

        foreach ($cmd in @('py', 'python')) {
            try {
                $raw = & $cmd '--version' 2>&1
                if ("$raw" -match '(\d+\.\d+\.\d+)') {
                    $python = $cmd
                    Write-OK "Python $($Matches[1]) installed  ($cmd)"
                    break
                }
            } catch { }
        }
    }
    catch {
        Write-Warn "winget not available or failed: $_"
    }
}

if (-not $python) {
    Write-Fail 'Python 3.7+ could not be found or installed automatically.'
    Write-Host ''
    Write-Host '  Please install Python manually:' -ForegroundColor Yellow
    Write-Host '    https://python.org/downloads' -ForegroundColor Yellow
    Write-Host '    Tick "Add Python to PATH" during installation.' -ForegroundColor Yellow
    Write-Host '    Then re-run Start.bat.' -ForegroundColor Yellow
    Write-Host ''
    Start-Process 'https://python.org/downloads'
    Read-Host 'Press Enter to exit'
    exit 1
}

# ── Launch ────────────────────────────────────────────────────────────────────
Write-Host ''
Write-Host '  Setup complete.  Starting the app...' -ForegroundColor Green
Write-Host ''

# Resolve to an absolute path so Start-Process can locate the executable.
# Prefer python.exe over pythonw.exe: pythonw is a GUI-subsystem binary that
# detaches from the console immediately, so '& pythonw' returns at once and
# $LASTEXITCODE is never set correctly.
$pyResolved = $python
try {
    $info = Get-Command $python -ErrorAction Stop
    $pyResolved = $info.Source
    if ($pyResolved -imatch '\\pythonw\.exe$') {
        $alt = $pyResolved -ireplace '\\pythonw\.exe$', '\python.exe'
        if (Test-Path $alt) { $pyResolved = $alt }
    }
} catch { }

# Use Start-Process -Wait instead of '& python' so PowerShell blocks until the
# GUI exits regardless of whether the Python binary uses the console or GUI
# subsystem.  -NoNewWindow keeps everything in the same terminal.
# stderr is captured to a temp file so crash tracebacks can be shown even
# when the window never appears.
$stderrFile  = [System.IO.Path]::GetTempFileName()
$appExitCode = 0

try {
    $proc = Start-Process `
        -FilePath              $pyResolved `
        -ArgumentList          "`"$AppScript`"" `
        -NoNewWindow           `
        -Wait                  `
        -PassThru              `
        -RedirectStandardError $stderrFile
    $appExitCode = $proc.ExitCode
} catch {
    Write-Fail "Could not launch the app: $_"
    $appExitCode = 1
}

if ($appExitCode -ne 0) {
    Write-Host ''
    Write-Fail "The app exited with an error (exit code $appExitCode)."

    if (Test-Path $stderrFile) {
        $errText = (Get-Content $stderrFile -Raw).Trim()
        if ($errText) {
            Write-Host ''
            Write-Host '  Error output:' -ForegroundColor Yellow
            $errText.Split("`n") | ForEach-Object {
                Write-Host "    $_" -ForegroundColor Red
            }
        }
    }

    Write-Host ''
    Write-Host '  To see the full error, open a Command Prompt and run:' -ForegroundColor Yellow
    Write-Host "      python `"$AppScript`"" -ForegroundColor Yellow
    Write-Host ''
    Remove-Item $stderrFile -Force -ErrorAction SilentlyContinue
    Read-Host 'Press Enter to exit'
    exit $appExitCode
}

Remove-Item $stderrFile -Force -ErrorAction SilentlyContinue
