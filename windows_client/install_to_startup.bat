@echo off
set "SCRIPT_NAME=run_smart_hotspot.bat"
set "TARGET_PATH=%~dp0%SCRIPT_NAME%"
set "SHORTCUT_PATH=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\SmartHotspot.lnk"

echo Creating startup shortcut...
powershell -Command "$s=(New-Object -COM WScript.Shell).CreateShortcut('%SHORTCUT_PATH%');$s.TargetPath='%TARGET_PATH%';$s.WorkingDirectory='%~dp0';$s.Save()"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS: SmartHotspot will now start automatically with Windows!
    echo You can find the shortcut in your Startup folder.
) else (
    echo ERROR: Could not create shortcut.
)
pause
exit