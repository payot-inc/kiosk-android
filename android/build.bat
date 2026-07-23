@echo off
setlocal enabledelayedexpansion

rem ============================================================
rem  Android APK build (run on the Windows build machine)
rem  NOTE: keep this file ASCII-only. cmd.exe parses .bat files
rem  in the OEM code page, so non-ASCII (Korean) bytes corrupt it.
rem
rem    build.bat                 debug build   (default URL = assets)
rem    build.bat release         release build
rem    build.bat debug assets    embedded static build (assets/www)
rem    build.bat debug http://192.168.0.10:3000   custom default URL
rem
rem  BUILD_MODE:
rem    native  (default) - gradlew.bat (pinned 8.14) or PATH gradle + Android SDK
rem    docker            - build via android/Dockerfile (Docker Desktop)
rem
rem  Called remotely by scripts/build-android-win.sh. Standalone use OK
rem  (run from c:\build\android-kiosk).
rem ============================================================

set "BUILD_MODE=native"
set "IMAGE=kiosk-android-builder"

set "VARIANT=%~1"
if "%VARIANT%"=="" set "VARIANT=debug"
set "KIOSK_URL=%~2"

if /i "%VARIANT%"=="debug" (
  set "TASK=assembleDebug"
) else if /i "%VARIANT%"=="release" (
  set "TASK=assembleRelease"
) else (
  echo [ERROR] usage: build.bat [debug^|release] [URL^|assets] 1>&2
  exit /b 1
)

set "GRADLE_ARGS=%TASK% --no-daemon"
if not "%KIOSK_URL%"=="" set "GRADLE_ARGS=%GRADLE_ARGS% -PkioskUrl=%KIOSK_URL%"

rem move to this script's dir (= android project root)
cd /d "%~dp0"

if /i "%BUILD_MODE%"=="docker" (
  echo ==^> building docker image ^(first run only^)
  docker build --platform linux/amd64 -t "%IMAGE%" . || exit /b 1

  echo ==^> gradle %TASK% ^(docker^)
  docker run --rm --platform linux/amd64 ^
    -v "%CD%:/workspace" ^
    -v kiosk-gradle-cache:/root/.gradle ^
    -w /workspace ^
    "%IMAGE%" gradle %GRADLE_ARGS% || exit /b 1
) else (
  rem prefer the pinned gradle wrapper; fall back to PATH gradle
  if exist "gradlew.bat" (
    echo ==^> gradlew %TASK% ^(native^)
    call gradlew.bat %GRADLE_ARGS% || exit /b 1
  ) else (
    echo ==^> gradle %TASK% ^(native, PATH^)
    call gradle %GRADLE_ARGS% || exit /b 1
  )
)

rem copy artifact to output\android (retrieved by the Mac side)
set "SRC=app\build\outputs\apk\%VARIANT%\app-%VARIANT%.apk"
set "OUTDIR=output\android"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

if not exist "%SRC%" (
  echo [ERROR] APK not found: %SRC% 1>&2
  exit /b 1
)
copy /y "%SRC%" "%OUTDIR%\kiosk-%VARIANT%.apk" >nul || exit /b 1

echo.
echo ==^> done: %OUTDIR%\kiosk-%VARIANT%.apk
endlocal
