; KioskShell 윈도우 설치파일 (NSIS 3, 64비트 전용)
; 빌드: ./build.sh 가 docker 안에서 makensis 로 컴파일한다.
;   필요 define: STAGING(dotnet publish 결과), SRCDIR(KioskShell 소스 — kiosk.ini),
;               REDIST(WebView2 부트스트래퍼 폴더), VERSION, OUTFILE

Unicode true
!include "MUI2.nsh"
!include "x64.nsh"

Name "Kiosk Shell ${VERSION}"
OutFile "${OUTFILE}"
InstallDir "$PROGRAMFILES64\KioskShell"
RequestExecutionLevel admin
SetCompressor /SOLID lzma

!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\KioskShell"
!define RUN_KEY "Software\Microsoft\Windows\CurrentVersion\Run"
; WebView2 런타임 설치 여부 레지스트리 (64비트 OS 시스템 설치 기준)
!define WV2_CLIENT "SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"

; --- 동반 프로그램 (설치 시점 다운로드 — 설치파일에 동봉하지 않음) ---
; 빌드 시 환경변수로 덮어쓰기 가능: EASYCARDK_URL=... RUSTDESK_URL=... ./build.sh
!ifndef EASYCARDK_URL
  !define EASYCARDK_URL "https://coinmachine.blob.core.windows.net/kiosk/EasyCardK_1.2.0.86.exe"
!endif
; RustDesk는 GitHub 릴리스 고정 버전 (자산 이름에 버전이 들어가 latest 리다이렉트 불가 — 갱신 시 버전 교체)
!ifndef RUSTDESK_URL
  !define RUSTDESK_URL "https://github.com/rustdesk/rustdesk/releases/download/1.4.7/rustdesk-1.4.7-x86_64.exe"
!endif

!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\KioskShell.exe"
!define MUI_FINISHPAGE_RUN_TEXT "지금 키오스크 실행"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Korean"

Function .onInit
  ${IfNot} ${RunningX64}
    MessageBox MB_ICONSTOP "이 프로그램은 64비트 윈도우 전용입니다."
    Abort
  ${EndIf}
  SetRegView 64
FunctionEnd

Section "Kiosk Shell (필수)" SecMain
  SectionIn RO

  ; 실행 중이면 종료 후 설치 (업그레이드 대응)
  nsExec::Exec 'taskkill /f /im KioskShell.exe'
  nsExec::Exec 'taskkill /f /im KioskAgent.exe'
  Sleep 500

  SetOutPath "$INSTDIR"
  File /r "${STAGING}/*"

  ; 설정 파일은 기존 편집본 보존 — 없을 때만 기본값 설치
  IfFileExists "$INSTDIR\kiosk.ini" +2 0
  File "/oname=kiosk.ini" "${SRCDIR}/kiosk.ini"

  ; WebView2 런타임 — 미설치 시 동봉된 부트스트래퍼로 설치
  ReadRegStr $0 HKLM "${WV2_CLIENT}" "pv"
  ${If} $0 == ""
    ReadRegStr $0 HKCU "Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}" "pv"
  ${EndIf}
  ${If} $0 == ""
    DetailPrint "WebView2 런타임 설치 중..."
    SetOutPath "$TEMP"
    File "${REDIST}/MicrosoftEdgeWebView2Setup.exe"
    ExecWait '"$TEMP\MicrosoftEdgeWebView2Setup.exe" /silent /install'
    Delete "$TEMP\MicrosoftEdgeWebView2Setup.exe"
    SetOutPath "$INSTDIR"
  ${EndIf}

  ; 바로가기
  CreateDirectory "$SMPROGRAMS\Kiosk Shell"
  CreateShortcut "$SMPROGRAMS\Kiosk Shell\Kiosk Shell.lnk" "$INSTDIR\KioskShell.exe"
  CreateShortcut "$SMPROGRAMS\Kiosk Shell\설정 파일 (kiosk.ini).lnk" "notepad.exe" '"$INSTDIR\kiosk.ini"'

  ; 언인스톨러 + 프로그램 추가/제거 등록
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKLM "${UNINST_KEY}" "DisplayName" "Kiosk Shell"
  WriteRegStr HKLM "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKLM "${UNINST_KEY}" "Publisher" "Payot"
  WriteRegStr HKLM "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKLM "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\KioskShell.exe"
  WriteRegStr HKLM "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKLM "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKLM "${UNINST_KEY}" "NoRepair" 1
SectionEnd

Section "부팅(로그인) 시 자동 실행" SecAutostart
  WriteRegStr HKCU "${RUN_KEY}" "KioskShell" '"$INSTDIR\KioskShell.exe"'
SectionEnd

Section "사이드카 (KioskAgent) — 지폐기 시리얼 + 원격 관리" SecAgent
  SectionIn RO  ; 지폐기가 사이드카 경유라 필수
  ; 로그인 시 자동 시작 + 수신 포트 방화벽 허용 + 즉시 시작
  WriteRegStr HKCU "${RUN_KEY}" "KioskAgent" '"$INSTDIR\KioskAgent.exe"'
  nsExec::Exec 'netsh advfirewall firewall delete rule name="KioskAgent"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="KioskAgent" dir=in action=allow program="$INSTDIR\KioskAgent.exe" enable=yes'
  Exec '"$INSTDIR\KioskAgent.exe"'
SectionEnd

Section "바탕화면 바로가기" SecDesktop
  CreateShortcut "$DESKTOP\Kiosk Shell.lnk" "$INSTDIR\KioskShell.exe"
SectionEnd

; --- 동반 프로그램: 자체 서버에서 다운로드 후 설치 ---
; 무인 설치(자가 업데이트 /S)에서는 건너뛴다 — 업데이트마다 재설치되는 것 방지.
; 다운로드는 윈도우 내장 curl.exe(Win10 1803+) 사용 — NSIS 플러그인 불필요.

Section "카드결제 모듈 (EasyCardK) — 다운로드 설치" SecEasyCard
  ${If} ${Silent}
    DetailPrint "EasyCardK: 무인 설치 — 건너뜀"
  ${Else}
    DetailPrint "EasyCardK 다운로드 중... (${EASYCARDK_URL})"
    nsExec::ExecToLog '"$SYSDIR\curl.exe" -fsSL --retry 2 -o "$TEMP\EasyCardK-Setup.exe" "${EASYCARDK_URL}"'
    Pop $0
    ${If} $0 == 0
      ; KICC 설치본의 무인 스위치 미확인 — 대화형으로 실행 (설치 마법사가 뜬다)
      ExecWait '"$TEMP\EasyCardK-Setup.exe"'
      Delete "$TEMP\EasyCardK-Setup.exe"
    ${Else}
      DetailPrint "EasyCardK 다운로드 실패(curl 종료코드 $0) — 수동 설치 필요 (svc.kicc.co.kr)"
    ${EndIf}
  ${EndIf}
SectionEnd

Section "원격 지원 (RustDesk) — 다운로드 설치" SecRustDesk
  ${If} ${Silent}
    DetailPrint "RustDesk: 무인 설치 — 건너뜀"
  ${ElseIf} ${FileExists} "$PROGRAMFILES64\RustDesk\rustdesk.exe"
    DetailPrint "RustDesk: 이미 설치됨 — 건너뜀"
  ${Else}
    DetailPrint "RustDesk 다운로드 중... (${RUSTDESK_URL})"
    nsExec::ExecToLog '"$SYSDIR\curl.exe" -fsSL --retry 2 -o "$TEMP\rustdesk.exe" "${RUSTDESK_URL}"'
    Pop $0
    ${If} $0 == 0
      ExecWait '"$TEMP\rustdesk.exe" --silent-install'
      Delete "$TEMP\rustdesk.exe"
    ${Else}
      DetailPrint "RustDesk 다운로드 실패(curl 종료코드 $0) — 수동 설치 필요"
    ${EndIf}
  ${EndIf}
SectionEnd

; 무인설치(자가 업데이트) 마무리 — 셸을 즉시 재실행한다.
; 대화형 설치는 마침 페이지의 "지금 실행"이 처리하므로 silent 일 때만.
Section "-Relaunch"
  IfSilent 0 +2
    Exec '"$INSTDIR\KioskShell.exe"'
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} "키오스크 셸 본체 (WebView2 전체화면)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SecAutostart} "윈도우 로그인 시 키오스크를 자동으로 시작합니다 (키오스크 운영 권장)"
  !insertmacro MUI_DESCRIPTION_TEXT ${SecAgent} "지폐기 시리얼(COM) 처리 + 원격 HTTP 명령(상태조회/재시작/URL변경/화면캡처) 사이드카. 지폐기 사용에 필수. 원격 접근은 kiosk.ini에 AgentToken 설정 필요"
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} "바탕화면에 실행 바로가기를 만듭니다"
  !insertmacro MUI_DESCRIPTION_TEXT ${SecEasyCard} "KICC 카드결제 모듈을 자체 서버에서 내려받아 설치합니다 (설치 마법사가 뜸). 인터넷 연결 필요"
  !insertmacro MUI_DESCRIPTION_TEXT ${SecRustDesk} "원격 지원용 RustDesk를 자체 서버에서 내려받아 무인 설치합니다. 인터넷 연결 필요"
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  SetRegView 64
  nsExec::Exec 'taskkill /f /im KioskShell.exe'
  nsExec::Exec 'taskkill /f /im KioskAgent.exe'
  Sleep 500

  nsExec::Exec 'netsh advfirewall firewall delete rule name="KioskAgent"'
  RMDir /r "$INSTDIR"
  RMDir /r "$SMPROGRAMS\Kiosk Shell"
  Delete "$DESKTOP\Kiosk Shell.lnk"
  DeleteRegValue HKCU "${RUN_KEY}" "KioskShell"
  DeleteRegValue HKCU "${RUN_KEY}" "KioskAgent"
  DeleteRegKey HKLM "${UNINST_KEY}"
SectionEnd
