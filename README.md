# kiosk-android

키오스크 — **하나의 Nuxt 웹**이 Android와 Windows 두 플랫폼에서 동작하며,
`useKiosk()` 컴포저블 하나로 **카드결제(KICC)** 와 **지폐기(USB 시리얼)** 를 제어한다.

```
                ┌── Nuxt 앱 (useKiosk 컴포저블 — 플랫폼 자동 감지) ──┐
                │                                                  │
   [Android] WebView 네이티브 앱 (Kotlin)              [Windows] WebView2 네이티브 셸 (C#, 64비트 전용)
                │                                                  │
   카드: EasyCardA 앱 ←(Broadcast 브리지)              카드+지폐기: 사이드카 KioskAgent ←(HTTP+SSE, 127.0.0.1:9100)
   지폐기: 네이티브 USB 시리얼 (CH340 9600bps)            ├ 카드: EasyCardK(8090) 프록시, 진행상태 WS(37445)→SSE
   진행상태: EVENT_CODE 브로드캐스트                      └ 지폐기: C# SerialPort
   브리지: window.android                              브리지: window.windows (reload/restart)
```

두 플랫폼 모두 **장치 연동은 네이티브가 한다** — 안드로이드는 앱(Kotlin), 윈도우는
사이드카(KioskAgent, C#). 웹은 플랫폼별 어댑터를 호출만 하고, 장치 이벤트는 같은
CustomEvent(`kiosk:bill-*`, `kiosk:card-event`)로 흐른다.

| 디렉토리 | 내용 |
|---|---|
| `output/` | **빌드 산출물** — `output/android/kiosk-*.apk`, `output/windows/KioskShell-Setup-*.exe` |
| `android/` | 안드로이드 네이티브 앱 (Kotlin). **Docker로 빌드** — 로컬에 JDK/SDK 불필요 |
| `windows/KioskShell/` | 윈도우 네이티브 셸 (C# WinForms + WebView2). **Docker로 크로스 빌드** (win-x64 전용) |
| `nuxt/` | Nuxt 4 프론트. `useKiosk()` + 플랫폼 어댑터(`composables/kiosk/`), `/kiosk-test` 테스트 페이지 |
| `guide/` | 연동 문서 (EasyCardA xlsx, **윈도우 EASYCARD Interface Spec pdf**, 지폐기 프로토콜) |
| `scripts/` | `sync-web.sh`(정적 빌드→APK 내장), `mock-easycardk.py`(EasyCardK 모의 — 실제 사이드카 테스트용), `mock-kioskagent.py`(사이드카 모의 — 웹 카드/지폐기 플로우 테스트용) |

## 빠른 시작 (개발)

필요한 것: **docker**, **adb** (장비 USB 연결), nuxt 쪽은 bun 또는 pnpm.

```bash
# 1. APK 빌드 (최초 1회는 이미지 생성 때문에 오래 걸림) → output/android/kiosk-debug.apk
./android/build.sh

# 2. nuxt dev 서버 실행 (별도 터미널)
cd nuxt && bun dev          # http://localhost:3000

# 3. 장비에 설치 + adb reverse + 실행
./android/install.sh
```

PC의 3000 포트를 다른 프로세스(예: docker postgrest)가 쓰고 있으면 다른 포트로:

```bash
cd nuxt && bun dev --port 3300
NUXT_PORT=3300 ./android/install.sh   # 장비의 localhost:3000 → PC:3300 으로 연결
```

앱의 기본 URL은 `http://localhost:3000` 이고, `install.sh`가 `adb reverse tcp:3000 tcp:3000`
을 걸어주므로 장비의 WebView가 PC의 dev 서버를 그대로 본다. HMR도 동작한다.
테스트 페이지: 장비 화면에서 `/kiosk-test` 로 이동
(`adb shell am start -n dev.payot.kiosk/.MainActivity --es url "http://localhost:3000/kiosk-test"`).

### 에뮬레이터 (실기기 없이 개발)

Android SDK 에뮬레이터로 실기기 없이 UI/웹 흐름을 테스트할 수 있다.

```bash
# kiosk AVD(가상머신) 실행
emulator -avd kiosk        # 별도 터미널에서 실행 (부팅될 때까지 대기)

# 부팅되면 실기기와 동일하게 설치 (install.sh 가 emulator-5554 를 자동 대상으로 잡는다)
./android/install.sh
```

AVD 목록은 `emulator -list-avds`, 다른 AVD 는 `emulator -avd <이름>`.

> 지폐기(시리얼)는 Mac 에뮬레이터에서 테스트하지 않는다 — 실기기에서만 검증한다.

#### 카드결제 (에뮬레이터 — 모의)

에뮬레이터에는 실제 EasyCardA 앱이 없으므로, **DEBUG 빌드**는 카드 요청을 네이티브에서
**모의 승인**으로 처리한다 ([EasyCardA.kt](android/app/src/main/java/dev/payot/kiosk/EasyCardA.kt) `mockRequest`).
별도 설정 없이 `window.android.cardRequest()`(또는 `kiosk.card.approve/cancel`)를 부르면:

- 진행 이벤트(`kiosk:card-event`, E001) → **약 1.5초 딜레이** → 가짜 승인(`kiosk:card-result`, RESULT_CODE `0000`).
- D4(취소)는 요청에 넣은 원승인번호/일자를 되돌려 준다. 승인번호가 없으면 `MOCK########` 생성.
- DEBUG 에서는 `getInfo().easyCardInstalled` 가 `true` 로 보고돼 웹의 카드 UI 가 열린다.
- 운영(release) 빌드는 모의를 쓰지 않는다 — 실제 EasyCardA 앱이 없으면 오류를 돌려준다.

> 이 저장소의 `nuxt/` 웹을 쓰지 않고 별도 웹을 붙여도, 카드 모의는
> **네이티브(APK) 레벨**이라 그대로 동작한다 (플랫폼 감지는 `window.android` 존재로).

자주 쓰면 PATH 에 등록해두면 `emulator -avd kiosk` 로 짧게 쓸 수 있다:

```bash
export PATH="$HOME/Library/Android/sdk/emulator:$HOME/Library/Android/sdk/platform-tools:$PATH"
```

AVD 가 없으면 시스템 이미지 설치 후 생성한다:

```bash
sdkmanager "system-images;android-34;google_apis;arm64-v8a"
avdmanager create avd -n kiosk -k "system-images;android-34;google_apis;arm64-v8a"
```

### URL 변경

우선순위: `am start --es url ...` (영구 저장됨) > 마지막 저장값 > 빌드 시 `-PkioskUrl` 기본값.

```bash
./android/install.sh debug http://192.168.0.10:3000   # 원격 서버로
./android/install.sh debug assets                      # 내장 정적 빌드로
```

### 서버 없는 단독 APK (운영)

```bash
./scripts/sync-web.sh              # nuxt generate → android/app/src/main/assets/www
./android/build.sh release assets  # 기본 URL을 assets 모드로 빌드
./android/install.sh release
```

## Windows 키오스크 (KioskShell — WebView2 네이티브 셸)

안드로이드와 같은 구성의 윈도우 네이티브 앱. **win-x64(64비트) 전용**.

```bash
./windows/build.sh        # Docker로 크로스 빌드 → output/windows/KioskShell-Setup-{버전}.exe
```

빌드는 2단계 모두 Docker라 윈도우/비주얼스튜디오 없이 어디서나 가능하다:
.NET SDK 컨테이너(exe 크로스 빌드) → NSIS 컨테이너(설치파일 패키징).

윈도우 장비 셋업:

```
1. KICC EasyCardK 설치 (svc.kicc.co.kr) — 127.0.0.1:8090 로컬서버로 상주
   EasyCardK 설정: 가맹점/TID, 리더기 연동(시리얼 57600), 서명 방식
   (아래 설치파일의 "카드결제 모듈" 옵션으로 자체 서버에서 자동 다운로드 설치도 가능)
2. KioskShell-Setup-*.exe 실행 — 설치 옵션:
   · 부팅(로그인) 시 자동 실행   · 바탕화면 바로가기
   · 카드결제 모듈(EasyCardK) / 원격 지원(RustDesk) — 자체 서버에서 다운로드 설치
   WebView2 런타임이 없으면 설치 중 자동으로 함께 설치된다 (부트스트래퍼 동봉).
3. 설정: 시작메뉴 > Kiosk Shell > "설정 파일 (kiosk.ini)" — Url / SerialPort(지폐기 COM포트)
   (kiosk.ini는 재설치/업그레이드해도 보존된다)
```

동반 프로그램(EasyCardK/RustDesk)은 설치파일에 동봉하지 않고 **설치 시점에 다운로드**한다
(윈도우 내장 curl 사용, 인터넷 필요). 기본 URL은 `installer.nsi` 상단 정의
(EasyCardK = 자체 서버 blob, RustDesk = GitHub 릴리스 고정 버전) — 교체는 정의 수정 또는 빌드 시 주입:

```bash
EASYCARDK_URL=https://my-server/EasyCardK-Setup.exe \
RUSTDESK_URL=https://github.com/rustdesk/rustdesk/releases/download/... ./windows/build.sh
```

자가 업데이트(무인 `/S`) 때는 둘 다 건너뛰므로 업데이트마다 재설치되지 않는다.

제거는 프로그램 추가/제거에서. `windows/start-kiosk.bat`은 셸 없이 Chrome으로 띄우는
개발용 폴백으로만 남아 있다.

### 사이드카 (KioskAgent) — 결제장치 + 원격 관리

셸과 **별도 프로세스**로 상주하는 경량 HTTP 에이전트. 세 가지 역할:

1. **지폐기** — 안드로이드처럼 COM 포트(시리얼)를 네이티브(C# SerialPort)가 잡고,
   웹은 `/bill/*` 명령 + SSE(`/events`) 이벤트만 쓴다. 셸이 재시작돼도
   시리얼 연결은 사이드카에 남는다.
2. **카드 (EasyCardK 프록시)** — EasyCardK(127.0.0.1:8090)의 홑따옴표 JS 리터럴
   응답(JSONP 전용)을 사이드카가 받아 표준 JSON으로 변환해 주고, 진행상태
   웹소켓(ws://127.0.0.1:37445)도 사이드카가 구독해 SSE(`kiosk:card-event`)로 중계한다.
   웹은 `/card/approve`·`/card/cancel`에 금액만 보내면 된다 (KICC ^구분 전문은 사이드카가 조립).
3. **원격 관리** — 셸이 죽거나 행이 걸려도 에이전트는 살아있어 원격 복구가 가능하다.

설치 시 "사이드카 (KioskAgent)" 섹션으로 포함 (자동실행 + 방화벽 규칙까지 등록).
설정(kiosk.ini): `SerialPort=`(지폐기 COM 포트, 비우면 자동), `AgentPort=9100`,
`AgentToken=`(**비우면 localhost 전용** — 원격 관리하려면 반드시 토큰 설정),
`AgentWatchdog=`(true면 셸 꺼짐 감지 시 30초 내 자동 재시작),
`UpdateUrl=`(자가 업데이트 매니페스트 URL), `UpdateCheckHours=6`(0이면 비활성).
예약 발화(`kiosk:cron`)와 진단 로그는 상주하는 에이전트가 관리하므로 셸이 죽어도 유지된다.

| 메서드 | 경로 | 동작 |
|---|---|---|
| GET | `/status` | 셸 실행 여부 + **응답 여부**(행 감지), 지폐기 상태, 에이전트 가동시간 등 |
| POST | `/reload` | 페이지 새로고침 (셸 파이프 경유) |
| POST | `/url?url=...` | URL 변경 — kiosk.ini 영구 저장 + 즉시 이동 |
| POST | `/restart` | 셸 재시작 (정상 종료 시도 → 강제 종료 폴백) |
| POST | `/start` `/stop` | 셸 시작/종료 (stop 시 워치독도 되살리지 않음) |
| POST | `/reboot` | 윈도우 재부팅 (5초 후) |
| POST | `/update` | 즉시 업데이트 확인 — 새 버전 있으면 무인설치(/S) |
| GET | `/screenshot` | 주 모니터 화면 캡처 (PNG) — 원격 장애 진단용 |
| GET | `/sysinfo` | 시스템 진단정보 — 메모리/디스크/업타임/OS/CPU (안드로이드 `getEnv()` 대응) |
| GET | `/logs` | 원격 진단 로그 — `?tail=N`(기본 500), `?src=shell`로 셸 로그 |
| POST | `/cron` | 예약 등록 — 본문 `{id?, at(epoch ms), repeat?, data?}` → `{id}` (안드로이드 `cronSchedule` 대응) |
| POST | `/cron/cancel` | 예약 취소 — `?id=` 또는 본문 |
| GET | `/cron/list` | 등록된 예약 목록 `[{id, at, period, data}]` |
| GET | `/events` | **SSE** — `kiosk:bill-*`/`kiosk:card-event`/`kiosk:cron` 푸시 (접속 즉시 지폐기 상태 스냅샷 + 밀린 크론) |
| GET | `/bill/status` | `{connected, running}` |
| POST | `/bill/connect` `/bill/disconnect` | 시리얼 연결/해제 |
| POST | `/bill/run` `/bill/stop` `/bill/write` | 투입구 개방/차폐/임의 명령(`?cmd=` 또는 본문) |
| GET | `/card/ping` | EasyCardK 설치/실행 확인 (GV 전문) |
| POST | `/card/approve` | 신용 승인 D1 — 본문 `{amount, tax?, tip?, installment?, timeoutSec?, taxOption?}` |
| POST | `/card/cancel` | 승인 취소 D4 — 본문에 `approvalNum`/`approvalDate` 추가. 응답은 EasyCardK 원본 필드 JSON |

`/events` `/bill/*` `/card/*` `/cron*` `/sysinfo` `/logs` 는 키오스크 화면(같은 PC의 브라우저)이
쓰므로 **loopback 접속은 토큰 없이 허용**되고 CORS가 열려 있다. 나머지 관리 API는 토큰 규칙 그대로.

```bash
# 사용 예 (토큰은 X-Token 헤더 또는 ?token=)
curl http://키오스크IP:9100/status -H "X-Token: 비밀토큰"
curl -X POST http://키오스크IP:9100/restart -H "X-Token: 비밀토큰"
curl -X POST "http://키오스크IP:9100/url?url=http://new-server/" -H "X-Token: 비밀토큰"
curl http://키오스크IP:9100/screenshot -H "X-Token: 비밀토큰" -o now.png
```

셸↔에이전트는 로컬 Named Pipe(`kioskshell-cmd`)로 통신한다 — PING/RELOAD/URL/QUIT.
파이프 무응답(행)이면 `/status`의 `shellResponsive=false`로 드러나고 `/restart`로 복구.

셸이 제공하는 키오스크 운영 기능 (안드로이드와 동일):

| 기능 | 구현 |
|---|---|
| 상시 켜짐 | `SetThreadExecutionState` (디스플레이/시스템 절전 차단) |
| 죽으면 자동 재실행 | 전역 예외 핸들러 → 1.5초 뒤 자기 재실행, WebView2 프로세스 사망 시 셸 재시작 |
| 부팅 자동실행 | 설치 시 "부팅 시 자동 실행" 옵션 (HKCU Run 레지스트리) |
| 페이지 로드 실패 | 5초 간격 자동 재시도 |
| 잠금 | 전체화면 + 항상 위 + Alt+F4 차단 (종료: **Ctrl+Alt+Shift+Q**) |
| JS 브리지 | `window.windows` — reload·restartApp (지폐기는 사이드카 `/bill/*` + SSE) |

- 카드·지폐기 모두 사이드카(KioskAgent, `127.0.0.1:9100`) 경유라 **셸이든 일반
  Chrome/Edge든 동일하게 동작**한다 — 웹 어댑터는 `nuxt/app/composables/kiosk/agentWin.ts`
  하나 (JSONP/Web Serial/웹소켓 직접 연결 없음).
- 플랫폼 감지: `window.android`(안드로이드) → `window.windows`(윈도우 셸) → Windows UA(브라우저).
  강제 지정은 `?platform=windows|android|none` 쿼리 또는 localStorage `kiosk:platform`.
- **장치 없이 개발/테스트**: `python3 scripts/mock-kioskagent.py` 가 사이드카와 같은
  `/card/*` `/bill/*` + SSE를 제공한다 (가짜 승인번호 + 진행상태 이벤트, 지폐 투입은
  `curl -X POST "http://127.0.0.1:9100/mock/insert?amount=1000"`).
- **EasyCardK 없이 실제 사이드카 테스트**(윈도우): `python3 scripts/mock-easycardk.py` 가
  EasyCardK 자리(8090)에서 D1/D4를 가짜 승인으로 응답한다 (전문 형식 확인용).

## Docker 빌드

`android/Dockerfile` 이 gradle + Android SDK를 포함한다. `build.sh`가 알아서 이미지를
만들고 빌드하므로 누구나 `docker`만 있으면 된다.

- gradle 캐시는 `kiosk-gradle-cache` 도커 볼륨에 보존 → 두 번째 빌드부터 빠름
- AAPT2가 x86_64 전용이라 이미지를 `linux/amd64`로 고정 (Apple Silicon은 Rosetta로 동작)
- 서명: `android/keystore/kiosk.keystore` 가 없으면 빌드가 자동 생성한다.
  **같은 키로 서명해야 `adb install -r` 갱신이 되므로 keystore를 커밋해서 공유할 것.**
  (개발용 키다. 스토어/운영 배포 시 별도 키로 교체)

## JS API — 플랫폼 공통

Nuxt에서는 `useKiosk()` 컴포저블만 쓰면 된다 ([useKiosk.ts](nuxt/app/composables/useKiosk.ts)).
아래 API는 **Android/Windows 동일하게 동작**한다 (내부에서 어댑터 자동 선택):

```ts
const kiosk = useKiosk()
kiosk.platform        // 'android' | 'windows' | 'none'

// 카드 승인/취소 — Promise 로 최종 응답
const result = await kiosk.card.approve({ amount: 1000 })
if (result.ok) { /* result.approvalNum, result.approvalDate ... */ }
await kiosk.card.cancel({ amount: 1000, approvalNum: result.approvalNum, approvalDate: result.approvalDate })

// 지폐기
kiosk.bill.connect()           // 시리얼 연결 (Android: USB 권한 다이얼로그 / Windows: 사이드카가 COM 포트 오픈)
kiosk.bill.run()               // 투입구 개방 → 이후 투입될 때마다 total 누적
kiosk.bill.stop()              // 차폐
kiosk.bill.total               // ref<number> 누적 금액
kiosk.bill.onEvent(e => ...)   // 원본 이벤트 구독

// 예약 작업(크론) — Android=AlarmManager, Windows=사이드카 스케줄러. 발화는 kiosk:cron 이벤트로
const id = await kiosk.cron.schedule({ at: Date.now() + 3600_000, repeat: 'daily', data: { job: 'reboot' } })
window.addEventListener('kiosk:cron', (e) => { if (e.detail.data?.job === 'reboot') kiosk.restartApp() })
await kiosk.cron.list()        // [{id, at, period, data}]
kiosk.cron.cancel(id)

// 진단 — 원격 장애 대응
const env = await kiosk.getEnv()   // 버전·메모리·저장소·업타임 (플랫폼별 필드 상이)
const log = await kiosk.getLogs()  // 최근 로그 텍스트 (Windows는 getLogs('shell')로 셸 로그)
```

두 플랫폼 모두 **예약/진단은 앱(안드로이드)·상주 에이전트(윈도우)가 관리**해 셸/페이지가
죽어도 유지된다. 발화분은 리스너가 없던 동안 보류됐다가 복귀·재접속 시 전달된다(유실 없음).

저수준 인터페이스(`window.android` · `window.windows` / `kiosk:*` CustomEvent)는
[kiosk-native.d.ts](kiosk-native.d.ts) 타입 정의와
[KioskBridge.kt](android/app/src/main/java/dev/payot/kiosk/KioskBridge.kt) 주석 참조.

## 결제 장치 연동 메모

### 카드 (KICC EasyCardA)

- 장비에 설치된 `kr.co.kicc.easycarda` 앱을 Broadcast로 호출한다
  (요청 `kr.co.kicc.easycarda.ACTION_REQ_BROADCAST` / 응답 `kr.co.kicc.easycarda.broadcast`).
- 주요 TRAN_TYPE: `D1` 승인, `D4` 취소, `B1`~`B4` 현금영수증, `RB` 망취소.
  전체 파라미터는 `guide/EasyCardA 연동개발 메뉴얼.xlsx` Parameter 시트.
- 응답 `RESULT_CODE === "0000"` 이 정상 승인. 진행 중에는 `EVENT_CODE`(E001 카드를
  리딩해주세요 …) 이벤트가 따로 올라온다.
- EasyCardA 설정(관리자 비밀번호 12345)에서 서버(개발/운영), 리더기 연동(EzCtrl/Direct),
  서명 방식을 맞춰야 한다.

### 지폐기 (USB 시리얼)

- CH340 계열(vid `0x1A86`), **9600bps**, ASCII 라인 프로토콜(CRLF). 상세: `guide/지폐기-명령체계.md`
- `RUN` → `RUN:OK` 투입구 개방, `STOP` → `STOP:OK` 차폐,
  투입 시마다 지폐기가 `BILL:{금액}` 푸시 (합계는 앱이 누적).
- 네이티브 단일 read 스레드로만 읽는다 (과거 JS 폴링으로 바이트 유실 사고 이력).
  Android는 앱(BillAcceptor.kt), Windows는 사이드카(KioskAgent/BillAcceptor.cs)가 읽는다 —
  웹/WebView는 어느 플랫폼에서도 시리얼을 직접 만지지 않는다.
- STOP 버튼 활성화는 "연결 여부" 기준으로 — 개방 확인을 못 받아도 강제 차폐 가능해야 한다.

## 키오스크 운영 기능 (기본 내장, 장비에서 검증됨)

| 기능 | 구현 | 비고 |
|---|---|---|
| 상시 켜짐 | `FLAG_KEEP_SCREEN_ON` + `setTurnScreenOn`/`setShowWhenLocked` + install.sh가 시스템 `screen_off_timeout` 무제한 설정 | 잠금화면 위로도 올라옴 |
| 재부팅 시 자동 실행 | `BootReceiver` (BOOT_COMPLETED/QUICKBOOT) + 3초 알람 백업. install.sh가 `SYSTEM_ALERT_WINDOW` appops 허용으로 Android 10+ 백그라운드 실행 제한 우회 | rk3576 보드에서 재부팅 → 자동실행 실측 확인 |
| 앱 죽으면 자동 재실행 | `KioskApp` 전역 예외 핸들러 → AlarmManager로 1.5초 뒤 재실행. WebView 렌더러 크래시는 액티비티 재생성으로 즉시 복구 | 테스트: `adb shell am broadcast -a dev.payot.kiosk.DEBUG_CRASH` (debug 빌드 전용) |
| JS에서 재시작 | `kiosk.restartApp()` — 프로세스 종료 후 1.5초 뒤 자동 재실행 | 이상 상태/메모리 복구용 |
| 그 외 | 뒤로가기 차단, 시스템바 숨김(immersive), 서버 연결 실패 시 5초 자동 재시도 (URL은 재부팅 후에도 유지) | |

설치 시 `install.sh`가 overlay 권한 허용·도즈 제외·화면 타임아웃 해제를 자동 적용하므로
**새 장비에는 반드시 install.sh로 설치**할 것 (수동 `adb install`만 하면 부팅 자동실행이 막힐 수 있음).

- **홈 런처 모드(선택)**: 더 강한 잠금이 필요하면 `AndroidManifest.xml` 의 HOME 카테고리
  주석 해제 → 재설치 → 홈 버튼 눌러 기본 런처 지정. 사용자가 앱 밖으로 못 나간다.

## 알려진 이슈

- **EasyCardA `RESULT_CODE 9988` "루팅 디바이스는 이용할 수 없습니다"** — 테스트 보드
  (rk3576)가 루팅/test-keys 펌웨어로 감지되면 EasyCardA가 거래를 거부한다
  (`guide/20260504.txt` 로그에서 실제 발생). 운영 장비 펌웨어 확인 또는 KICC 영업
  담당자와 협의 필요.
- 자주 보는 거절코드: `9995` 리더기 연결을 확인해주세요(리더기 미연결),
  `9994` 리딩 시간 종료, `9999` 사용자 취소. 전체 목록은 매뉴얼 ResponseCode 시트.
- WebView 진단: debug 빌드는 PC 크롬에서 `chrome://inspect` 로 원격 디버깅 가능.
