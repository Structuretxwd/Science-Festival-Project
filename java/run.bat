@echo off
setlocal
cd /d "%~dp0"

rem ---------------------------------------------------------------------------
rem  Campus Agent "Xiao Zhu Shou" - Java launcher
rem
rem  Builds if needed, then starts the local HTTP server and opens the UI in
rem  an app window (Edge/Chrome --app). Keep this file ASCII-only: cmd.exe
rem  mis-parses UTF-8 batch files, so Chinese text here breaks parsing.
rem
rem  Usage:
rem    run.bat                 start server + open browser
rem    run.bat --no-browser    start server only (for automated tests)
rem ---------------------------------------------------------------------------

chcp 65001 >nul

where java >nul 2>nul
if errorlevel 1 (
  echo [run] ERROR: "java" was not found in PATH.
  echo [run] Please install JDK 17 or newer, or add it to PATH.
  echo.
  pause
  exit /b 1
)

if not exist "out\campus\Main.class" (
  echo [run] no compiled classes found - building first ...
  call build.bat
  if errorlevel 1 (
    echo.
    echo [run] ERROR: build failed.
    pause
    exit /b 1
  )
  echo.
)

echo [run] starting campus agent ...
echo.
java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp out campus.Main %*
set RC=%errorlevel%

echo.
if not "%RC%"=="0" (
  echo [run] process exited with code %RC%
) else (
  echo [run] process exited normally
)
pause
exit /b %RC%
