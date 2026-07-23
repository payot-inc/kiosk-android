@echo off
rem ============================================================
rem  윈도우 키오스크 실행 (개발/수동 실행용)
rem    사용법: start-kiosk.bat [URL]
rem
rem  정식 배포는 output\windows\KioskShell-Setup-*.exe 설치를 사용한다
rem  (설치 옵션으로 부팅 자동실행/바로가기 구성).
rem
rem  1순위: 설치된 KioskShell (WebView2 네이티브 셸)
rem  2순위: Chrome --kiosk 폴백 (셸 미설치 시)
rem  지폐기는 두 경우 모두 사이드카(KioskAgent, 127.0.0.1:9100)가 시리얼로 처리한다
rem  — 미설치 개발 환경에서는 scripts\mock-kioskagent.py 로 대체 가능
rem ============================================================
setlocal

set "SHELL_EXE=%ProgramFiles%\KioskShell\KioskShell.exe"

if exist "%SHELL_EXE%" (
  start "" "%SHELL_EXE%" %1
  goto :eof
)

echo KioskShell이 설치되어 있지 않아 Chrome 폴백으로 실행합니다.
echo (정식 설치: KioskShell-Setup-*.exe)
set "URL=%~1"
if "%URL%"=="" set "URL=http://localhost:3000/"

set "CHROME=%ProgramFiles%\Google\Chrome\Application\chrome.exe"
if not exist "%CHROME%" set "CHROME=%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"
if not exist "%CHROME%" set "CHROME=%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"

start "" "%CHROME%" --kiosk "%URL%" ^
  --noerrdialogs ^
  --disable-session-crashed-bubble ^
  --disable-infobars ^
  --autoplay-policy=no-user-gesture-required

endlocal
