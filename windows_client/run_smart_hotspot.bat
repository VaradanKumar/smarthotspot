@echo off
set "BASE_DIR=%~dp0"
cd /d "%BASE_DIR%"

:: 1. Silent Python Detection
python --version >nul 2>&1
if %ERRORLEVEL% EQU 0 ( set "PY=python" ) else (
    py --version >nul 2>&1
    if %ERRORLEVEL% EQU 0 ( set "PY=py" ) else ( exit /b )
)

:: 2. Smart Dependency Check (Only runs if needed)
%PY% -c "import PySide6, bleak, customtkinter" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo Installing required components...
    %PY% -m pip install PySide6 bleak customtkinter >nul 2>&1
)

:: 3. Launch one BLE owner. Starting both desktop front ends creates two
:: independent BLE clients that compete for the same phone connection.
tasklist /FI "IMAGENAME eq pythonw.exe" | find /i "HotspotTrigger.py" >nul
if %ERRORLEVEL% NEQ 0 (
    start "AirBeam Pro Tray" /min %PY%w HotspotTrigger.py
)

exit
