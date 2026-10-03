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
echo Easiest: just run Start.bat - it downloads ExifTool into the tools folder.
echo.
echo Or install it by hand:
echo   1. Go to https://exiftool.org and download the "Windows Executable" zip
echo   2. Put the zip ^(or everything inside it^) in the "tools" folder next to Start.bat.
echo      Start.bat renames exiftool^(-k^).exe and keeps the exiftool_files folder beside it.
echo      ^(Copying only the .exe does not work: it needs the exiftool_files folder.^)
echo.
pause
goto :end

:check
where exiftool >nul 2>&1
if %ERRORLEVEL% == 0 (
    echo.
    echo ExifTool installed successfully:
    exiftool -ver
    echo You can now run:  Start.bat
) else (
    echo.
    echo Installation may require a terminal restart to take effect.
    echo If ExifTool still isn't found, add its folder to your PATH manually.
)

:end
pause
