@echo off
:: Install ExifTool on Windows using winget, Chocolatey, or manual download

where exiftool >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo ExifTool is already installed.
    exiftool -ver
    goto :end
)

echo ExifTool not found. Attempting installation...

:: Try winget first (available on Windows 10 1709+ with App Installer)
where winget >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo Installing via winget...
    winget install --id OliverBetz.ExifTool -e --silent
    goto :check
)

:: Try Chocolatey
where choco >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo Installing via Chocolatey...
    choco install exiftool -y
    goto :check
)

echo.
echo Automatic installation not available.
echo Please install ExifTool manually:
echo   1. Go to https://exiftool.org
echo   2. Download the "Windows Executable" zip
echo   3. Extract exiftool(-k).exe, rename it to exiftool.exe
echo   4. Place exiftool.exe in a folder on your PATH (e.g. C:\Windows\System32)
echo.
pause
goto :end

:check
where exiftool >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo.
    echo ExifTool installed successfully:
    exiftool -ver
    echo You can now run:  python app.py
) else (
    echo.
    echo Installation may require a terminal restart to take effect.
    echo If ExifTool still isn't found, add its folder to your PATH manually.
)

:end
pause
