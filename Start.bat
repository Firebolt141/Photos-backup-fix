@echo off
title Photos Backup Fix
cd /d "%~dp0"

:: Run the PowerShell setup+launcher with execution-policy bypass so the
:: user does not need to change any system settings first.
powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
set "RC=%ERRORLEVEL%"

:: Keep the window open on any error so the message can be read.
:: (No parentheses blocks here: an unescaped ")" inside one ends the block
::  early and makes cmd quit with ". was unexpected at this time".)
if "%RC%"=="0" goto :eof
echo.
echo   Setup stopped with an error ^(exit code %RC%^).
echo   Read the messages above, fix what they say, then run Start.bat again.
echo.
pause
