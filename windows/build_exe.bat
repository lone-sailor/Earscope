@echo off
REM Builds Ear Scope into a single standalone Windows .exe.
REM Run this from Command Prompt or PowerShell in the script's directory.

setlocal enabledelayedexpansion

echo ===================================================
echo   Building Ear Scope Standalone Executable
echo ===================================================
echo.

REM Verify icon.ico exists before compiling
if not exist "icon.ico" (
    echo [ERROR] "icon.ico" not found in the current directory!
    echo Please ensure "icon.ico" is in the same folder as this script.
    pause
    exit /b 1
)

echo [1/3] Ensuring build dependencies are installed...
python -m pip install --upgrade pillow pyinstaller

echo.
echo [2/3] Cleaning previous build artifacts...
if exist "build" rd /s /q "build"
if exist "dist\EarScope.exe" del /f /q "dist\EarScope.exe"

echo.
echo [3/3] Compiling EarScope.exe with PyInstaller...
python -m PyInstaller --noconfirm --onefile --windowed --clean ^
    --name "EarScope" ^
    --icon "icon.ico" ^
    --add-data "icon.ico;." ^
    --paths "." ^
    itimo_gui.py

if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Build failed! Check the console output above for details.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ===================================================
echo   Build Successful!
echo ===================================================
echo Standalone executable located at:
echo   dist\EarScope.exe
echo.
pause