#Requires -Version 5.1
<#
.SYNOPSIS
    One-shot setup and launcher for Photos Backup Fix.

.DESCRIPTION
    1. Checks for ExifTool; downloads the portable Windows build if missing.
    2. Checks for Python 3.9+; installs via winget if missing.
    3. Installs Flask (pip install flask) if not already present.
    4. Launches web_app.py and opens the browser UI at http://127.0.0.1:5000

    Run via Start.bat (which sets -ExecutionPolicy Bypass automatically).
#>

$ErrorActionPreference = 'Stop'
trap {
    Write-Host ''
    Write-Host "  Unexpected error: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "  at line $($_.InvocationInfo.ScriptLineNumber): $($_.InvocationInfo.Line.Trim())" -ForegroundColor DarkGray
    exit 1
}
# Force TLS 1.2 so Invoke-WebRequest works on older Windows builds
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$AppDir    = $PSScriptRoot
$ToolsDir  = Join-Path $AppDir 'tools'
$ExifExe   = Join-Path $ToolsDir 'exiftool.exe'
$AppScript = Join-Path $AppDir  'web_app.py'

# -- Helpers -------------------------------------------------------------------

function Write-Header {
    Write-Host ''
    Write-Host '  ==================================================' -ForegroundColor DarkCyan
    Write-Host '    Photos Backup Fix' -ForegroundColor White
    Write-Host '    Setup and launcher' -ForegroundColor DarkGray
    Write-Host '  ==================================================' -ForegroundColor DarkCyan
    Write-Host ''
}

function Invoke-Native([scriptblock]$NativeBlock__) {
    # Runs a native program and returns its output lines. Under
    # $ErrorActionPreference='Stop', Windows PowerShell 5.1 turns *any* stderr
    # line (e.g. a pip warning) into a fatal error, so relax it here and let
    # callers check $LASTEXITCODE instead.
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    # (Parameter has an unusual name on purpose: the block is run in this
    #  scope, so a parameter called e.g. $cmd would hide the caller's $cmd.)
    try { & $NativeBlock__ 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $oldEap__ }
}

function Write-Step([int]$n, [string]$msg) {
    Write-Host "  [$n/3] $msg" -ForegroundColor Cyan
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

# -- Step 1: ExifTool ----------------------------------------------------------
Write-Step 1 'ExifTool'

function Test-ExifTool([string]$exe) {
    # Version string if $exe runs, else $null. Never throws and never hangs:
    # stdin is closed (the "(-k)" build waits for Enter otherwise) and there is
    # a 30 s limit. Native stderr is captured, so $ErrorActionPreference='Stop'
    # can't turn an ExifTool warning into a script-killing error.
    if (-not $exe) { return $null }
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName               = $exe
        $psi.Arguments              = '-ver'
        $psi.UseShellExecute        = $false
        $psi.CreateNoWindow         = $true
        $psi.RedirectStandardInput  = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError  = $true
        $p = [System.Diagnostics.Process]::Start($psi)
        $p.StandardInput.Close()
        $outTask = $p.StandardOutput.ReadToEndAsync()
        $null    = $p.StandardError.ReadToEndAsync()
        if (-not $p.WaitForExit(30000)) { try { $p.Kill() } catch { }; return $null }
        $out = $outTask.Result.Trim()
        if ($out -match '^\d+\.\d+') { return $Matches[0] }
    } catch { }
    return $null
}

function Install-LocalExifTool {
    # Puts a working exiftool.exe (+ its exiftool_files folder) directly in
    # tools\, whatever shape it arrived in:
    #   tools\exiftool.exe                         (already right)
    #   tools\exiftool(-k).exe + exiftool_files\   (extracted, not renamed)
    #   tools\exiftool-13.xx_64\exiftool(-k).exe   (zip extracted into a sub-folder)
    #   tools\exiftool-13.xx_64.zip                (zip not extracted)
    if (-not (Test-Path $ToolsDir)) { return }

    $zip = Get-ChildItem $ToolsDir -Filter 'exiftool*.zip' -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($zip -and -not (Test-Path $ExifExe)) {
        Write-Warn "Extracting $($zip.Name) ..."
        $extract = Join-Path $ToolsDir '_extract'
        if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
        Expand-Archive -Path $zip.FullName -DestinationPath $extract -Force
    }

    if (-not (Test-Path $ExifExe)) {
        $exe = Get-ChildItem $ToolsDir -Recurse -File -Filter 'exiftool*.exe' -ErrorAction SilentlyContinue |
               Sort-Object { $_.FullName.Length } | Select-Object -First 1
        if ($exe) {
            Write-Warn "Using $($exe.FullName.Substring($ToolsDir.Length + 1)) as tools\exiftool.exe"
            Copy-Item $exe.FullName $ExifExe -Force
            $support = [IO.Path]::GetFullPath((Join-Path $exe.DirectoryName 'exiftool_files'))
            $dest    = [IO.Path]::GetFullPath((Join-Path $ToolsDir 'exiftool_files'))
            # Only copy when it lives somewhere else (e.g. a sub-folder); never
            # delete the folder we are about to copy from.
            if ((Test-Path $support) -and ($support.TrimEnd('\','/') -ne $dest.TrimEnd('\','/'))) {
                if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
                Copy-Item $support $dest -Recurse -Force
            }
        }
    }
    $extract = Join-Path $ToolsDir '_extract'
    if (Test-Path $extract) { Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue }

    # Files downloaded with a browser are marked "from the internet"; Windows
    # can then refuse to run them silently. Clear that mark.
    Get-ChildItem $ToolsDir -Recurse -File -ErrorAction SilentlyContinue |
        Unblock-File -ErrorAction SilentlyContinue
}

function Write-ExifToolHelp {
    Write-Host ''
    Write-Host '  Install ExifTool by hand, then run Start.bat again:' -ForegroundColor Yellow
    Write-Host '    1. Go to  https://exiftool.org  and download the "Windows Executable" zip (64-bit).' -ForegroundColor Yellow
    Write-Host "    2. Put the zip itself in:  $ToolsDir" -ForegroundColor Yellow
    Write-Host '       (or extract it there - the exiftool(-k).exe file AND the exiftool_files' -ForegroundColor Yellow
    Write-Host '        folder must both be copied; Start.bat renames and arranges them for you)' -ForegroundColor Yellow
    Write-Host ''
}

$exifCmd = $null

# 1) ExifTool already on PATH (only if it actually runs)
$onPath = Get-Command exiftool -ErrorAction SilentlyContinue | Select-Object -First 1
if ($onPath) {
    $ver = Test-ExifTool $onPath.Source
    if ($ver) {
        $exifCmd = $onPath.Source
        Write-OK "System ExifTool v$ver"
    } else {
        Write-Warn "Ignoring $($onPath.Source): it did not run"
    }
}

# 2) tools\ folder (arranged first if needed)
if (-not $exifCmd) {
    try { Install-LocalExifTool } catch { Write-Warn "Could not arrange tools\: $($_.Exception.Message)" }
    if (Test-Path $ExifExe) {
        $ver = Test-ExifTool $ExifExe
        if ($ver) {
            $exifCmd = $ExifExe
            Write-OK "Local ExifTool v$ver  (.\tools\exiftool.exe)"
        } else {
            Write-Warn 'tools\exiftool.exe is there but does not run.'
            if (-not (Test-Path (Join-Path $ToolsDir 'exiftool_files'))) {
                Write-Warn 'The exiftool_files folder is missing next to it (it comes in the same zip).'
            } else {
                Write-Warn 'Antivirus may be blocking it - check its quarantine / allow list.'
            }
            Write-Warn 'Downloading a fresh copy instead...'
            Remove-Item $ExifExe -Force -ErrorAction SilentlyContinue
        }
    }
}

# 3) Download the portable build
if (-not $exifCmd) {
    Write-Warn 'ExifTool not found - downloading it (about 11 MB) ...'
    try {
        New-Item -ItemType Directory -Path $ToolsDir -Force | Out-Null

        # ver.txt always contains the current stable version number
        $version = (Invoke-WebRequest -Uri 'https://exiftool.org/ver.txt' -UseBasicParsing -TimeoutSec 30).Content.Trim()
        if ($version -notmatch '^\d+\.\d+$') { throw "Unexpected ExifTool version text '$version'" }

        # The Windows zips (exiftool-<ver>_64.zip / _32.zip, containing
        # "exiftool(-k).exe" + "exiftool_files\") are hosted on SourceForge
        # since 2025. SourceForge serves an HTML page instead of the file to
        # browser-like clients, so ask as curl, and check we really got a zip.
        $arch  = if ([Environment]::Is64BitOperatingSystem) { @('_64', '_32') } else { @('_32') }
        $hosts = @('https://downloads.sourceforge.net/project/exiftool/',
                   'https://master.dl.sourceforge.net/project/exiftool/',
                   'https://exiftool.org/')
        $downloaded = $false
        foreach ($sfx in $arch) {
            $name = "exiftool-$version$sfx.zip"
            $zip  = Join-Path $ToolsDir $name
            foreach ($h in $hosts) {
                try {
                    Write-Warn "  Fetching $h$name ..."
                    Invoke-WebRequest -Uri "$h$name" -OutFile $zip -UseBasicParsing `
                        -UserAgent 'curl/8.5.0' -MaximumRedirection 10 -TimeoutSec 300
                    $fs = [IO.File]::OpenRead($zip)
                    try { $b0 = $fs.ReadByte(); $b1 = $fs.ReadByte() } finally { $fs.Close() }
                    if ($b0 -eq 0x50 -and $b1 -eq 0x4B) { $downloaded = $true; break }   # "PK" = zip
                    Write-Warn '  (got a web page instead of the zip, trying the next mirror)'
                } catch {
                    Write-Warn "  ($($_.Exception.Message))"
                }
                Remove-Item $zip -Force -ErrorAction SilentlyContinue
            }
            if ($downloaded) { break }
        }
        if (-not $downloaded) { throw "Could not download ExifTool $version (no internet, or the download sites are blocked)" }

        Install-LocalExifTool
        Get-ChildItem $ToolsDir -Filter 'exiftool*.zip' -File | Remove-Item -Force -ErrorAction SilentlyContinue
        $ver = Test-ExifTool $ExifExe
        if (-not $ver) { throw 'The downloaded ExifTool does not run (antivirus?)' }
        $exifCmd = $ExifExe
        Write-OK "ExifTool $ver downloaded to .\tools\"
    }
    catch {
        Write-Fail "ExifTool setup failed: $($_.Exception.Message)"
        Write-ExifToolHelp
        exit 1
    }
}

# Make sure local tools\ is on PATH for this session, and tell the app exactly
# which ExifTool was verified (so it can't pick a different, broken copy).
if ($exifCmd -eq $ExifExe) {
    $env:PATH = "$ToolsDir;$env:PATH"
}
$env:PHOTOFIX_EXIFTOOL = $exifCmd

# -- Step 2: Python ------------------------------------------------------------
Write-Step 2 'Python 3.9+'

$python    = $null
$minPyVer  = [Version]'3.9.0'

foreach ($cmd in @('py', 'python', 'python3')) {
    try {
        if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { continue }
        $raw = Invoke-Native { & $cmd '--version' }
        if ($LASTEXITCODE -eq 0 -and "$raw" -match '(\d+\.\d+\.\d+)') {
            if ([Version]$Matches[1] -ge $minPyVer) {
                $python = $cmd
                Write-OK "Python $($Matches[1])  ($cmd)"
                break
            }
            Write-Warn "Python $($Matches[1]) is too old (need 3.9+)"
        }
    } catch { }
}

if (-not $python) {
    Write-Warn 'Python 3.9+ not found - trying winget install...'
    try {
        winget install --id Python.Python.3.14 -e --silent `
            --accept-package-agreements --accept-source-agreements 2>&1 | Out-Null

        # Reload PATH from registry so the new Python is visible
        $env:PATH = [System.Environment]::GetEnvironmentVariable('PATH', 'Machine') +
                    ';' +
                    [System.Environment]::GetEnvironmentVariable('PATH', 'User')

        foreach ($cmd in @('py', 'python')) {
            try {
                if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { continue }
                $raw = Invoke-Native { & $cmd '--version' }
                if ($LASTEXITCODE -eq 0 -and "$raw" -match '(\d+\.\d+\.\d+)') {
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
    Write-Fail 'Python 3.9+ could not be found or installed automatically.'
    Write-Host ''
    Write-Host '  Please install Python manually:' -ForegroundColor Yellow
    Write-Host '    https://python.org/downloads' -ForegroundColor Yellow
    Write-Host '    Tick "Add Python to PATH" during installation.' -ForegroundColor Yellow
    Write-Host '    Then re-run Start.bat.' -ForegroundColor Yellow
    Write-Host ''
    Start-Process 'https://python.org/downloads'
    exit 1
}

# -- Step 3: Flask -------------------------------------------------------------
Write-Step 3 'Flask (web UI)'

$flaskOk = $false
$flaskCheck = Invoke-Native { & $python -c "import importlib.metadata; print(importlib.metadata.version('flask'))" }
if ($LASTEXITCODE -eq 0 -and "$flaskCheck" -match '^(\d+)\.') {
    if ([int]$Matches[1] -ge 3) {
        Write-OK "Flask $flaskCheck already installed"
        $flaskOk = $true
    } else {
        Write-Warn "Flask $flaskCheck is too old (3.0 or newer needed)"
    }
}

if (-not $flaskOk) {
    Write-Warn 'Installing Flask with pip (one-time) ...'
    $pipOut = Invoke-Native { & $python -m pip install --upgrade -r (Join-Path $PSScriptRoot 'requirements.txt') --disable-pip-version-check }
    $pipCode = $LASTEXITCODE
    $flaskVer = Invoke-Native { & $python -c "import importlib.metadata; print(importlib.metadata.version('flask'))" }
    if ($LASTEXITCODE -eq 0 -and "$flaskVer" -match '^\d+\.') {
        Write-OK "Flask $flaskVer installed"
    } else {
        Write-Fail "Installing Flask failed (pip exit code $pipCode):"
        $pipOut | Select-Object -Last 15 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
        Write-Host ''
        Write-Host "  Try running:  $python -m pip install -r requirements.txt" -ForegroundColor Yellow
        exit 1
    }
}

# -- Launch --------------------------------------------------------------------
Write-Host ''
Write-Host '  Setup complete.  Launching web UI...' -ForegroundColor Green
Write-Host '  The browser opens automatically (if it does not, open the address printed below).' -ForegroundColor DarkGray
Write-Host '  Keep this window open while you use the app. Close it (or press Ctrl+C) to stop.' -ForegroundColor DarkGray
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

# Ctrl+C (0xC000013A, or a KeyboardInterrupt traceback) is the normal way to stop.
$stoppedByUser = ($appExitCode -eq -1073741510) -or ($appExitCode -eq 3221225786)
if (-not $stoppedByUser -and $appExitCode -ne 0 -and (Test-Path $stderrFile)) {
    $stoppedByUser = [bool](Select-String -Path $stderrFile -Pattern 'KeyboardInterrupt' -SimpleMatch -Quiet)
}
if ($stoppedByUser) { $appExitCode = 0 }

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
    Write-Host "      python `"$AppScript`""  -ForegroundColor Yellow
    Write-Host '  Make sure Flask is installed:  python -m pip install -r requirements.txt' -ForegroundColor Yellow
    Write-Host ''
    Remove-Item $stderrFile -Force -ErrorAction SilentlyContinue
    exit $appExitCode
}

Remove-Item $stderrFile -Force -ErrorAction SilentlyContinue
