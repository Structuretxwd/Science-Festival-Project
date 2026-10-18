@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ---------------------------------------------------------------------------
rem  Campus Agent "Xiao Zhu Shou" - Java build script
rem
rem  No Maven / Gradle on purpose: if javac is available, this builds.
rem  Third-party jars go into lib\ and are added to the classpath automatically.
rem  The current implementation uses only the JDK standard library
rem  (java.net.http / com.sun.net.httpserver), so an empty lib\ still compiles.
rem
rem  NOTE: keep this file ASCII-only. cmd.exe mis-parses UTF-8 batch files,
rem  and Chinese comments here break parsing. Chinese output belongs in Java.
rem ---------------------------------------------------------------------------

echo [build] cleaning out\ ...
if exist out rmdir /s /q out
mkdir out

rem Paths contain spaces, so every source file is passed quoted on the command
rem line. An @argfile cannot be used here: javac strips the backslashes out of
rem Windows paths inside argfiles.
echo [build] collecting sources ...
set SRCS=
set NFILE=0
for /r src\campus %%f in (*.java) do (
  set SRCS=!SRCS! "%%f"
  set /a NFILE+=1
)
echo [build] %NFILE% source file(s)

set LIBARGS=
if exist lib (
  for %%f in (lib\*.jar) do set LIBARGS=!LIBARGS!;%%f
)

echo [build] compiling (javac -encoding UTF-8) ...
javac -encoding UTF-8 -d out -cp "out.!LIBARGS!" !SRCS!
set RC=%errorlevel%

if not "%RC%"=="0" (
  echo.
  echo [build] FAILED - see javac output above.
  exit /b %RC%
)

echo [build] OK - class files are in out\
exit /b 0
