@echo off
title Google Takeout EXIF Restoration Tool
cd /d "%~dp0"

:: Run the PowerShell setup+launcher with execution-policy bypass so the
:: user does not need to change any system settings first.
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"

:: If setup.ps1 exits with an error, keep the window open so the user can read it.
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo   Setup encountered an error (exit code %ERRORLEVEL%).
    echo   Check the messages above and re-run Start.bat.
    echo.
    pause
)
