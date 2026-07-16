@echo off
set "BASE_DIR=%~dp0"
cd /d "%BASE_DIR%"

:: Silent detection
python --version >nul 2>&1
if %ERRORLEVEL% EQU 0 ( set "PY=python" ) else (
    py --version >nul 2>&1
    if %ERRORLEVEL% EQU 0 ( set "PY=py" ) else ( exit /b )
)

:: Silent dependency check
%PY% -c "import PySide6, bleak" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    %PY% -m pip install PySide6 bleak >nul 2>&1
)

:: Run invisibly
start "" %PY%w HotspotTrigger.py
exit
