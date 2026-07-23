/**
 * 키오스크 네이티브 셸 JavaScript 브리지 타입 정의 (Android + Windows)
 * ====================================================================
 *
 * 네이티브 소스 기준:
 *   Android : android/app/src/main/java/dev/payot/kiosk/KioskBridge.kt  → window.android
 *   Windows : windows/KioskShell/MainForm.cs (Bootstrap) + Bridge.cs    → window.windows
 * 웹 사용 예시: nuxt/app/composables/useKiosk.ts
 *
 * ## 주입(inject) 방식
 *
 * - Android: MainActivity 가 WebView 생성 직후 페이지 로드 전에
 *   `webView.addJavascriptInterface(bridge, "android")` 로 주입한다.
 *   어떤 스크립트에서든 처음부터 `window.android` 로 접근 가능하다.
 * - Windows: KioskShell(WebView2) 의 부트스트랩 스크립트가 문서 생성 시점에
 *   `chrome.webview.hostObjects.sync.kiosk` 를 감싸 `window.windows` 를 정의한다.
 *   지폐기/카드는 셸이 아니라 사이드카(KioskAgent, 127.0.0.1:9100)가 처리하므로
 *   window.windows 에는 셸 제어(getInfo/reload/restartApp)만 있다.
 *   Android 브리지의 나머지 기능은 사이드카 HTTP로 대응된다:
 *     setUrl→POST /url, cron*→/cron·/cron/cancel·/cron/list, getEnv→GET /sysinfo,
 *     getLogs→GET /logs, 재부팅→POST /reboot, 업데이트→POST /update.
 * - 일반 브라우저에는 둘 다 없다. 존재 여부가 곧 플랫폼 감지 수단:
 *   `window.android` → Android 키오스크, `window.windows` → Windows 셸.
 *
 * ## 호출 규약 (JS -> 네이티브)
 *
 * - 모든 메서드는 **동기 호출**이다. 단, 파라미터/반환값은 원시 타입(string)만
 *   오갈 수 있어 구조화 데이터는 JSON 문자열로 주고받는다
 *   (getInfo/billStatus/cronSchedule/cronList 등).
 * - Android 호출은 WebView 의 JavaBridge 전용 스레드에서 실행된다. UI 작업이
 *   필요한 메서드는 네이티브가 내부에서 UI 스레드로 넘긴다 (호출자는 신경 쓸 필요 없음).
 * - 장치 작업(카드/지폐기)의 결과는 반환값이 아니라 **이벤트로 비동기 수신**한다.
 *
 * ## 이벤트 규약 (네이티브 -> JS)
 *
 * 네이티브는 `window.dispatchEvent(new CustomEvent('kiosk:...', {detail}))` 로
 * 이벤트를 쏜다. Windows 는 사이드카 SSE 를 웹이 같은 이름의 CustomEvent 로
 * 재발행하므로 두 플랫폼의 이벤트 흐름이 동일하다.
 * 아래 WindowEventMap 확장 덕에 addEventListener 가 타입 추론된다:
 *
 *   window.addEventListener('kiosk:bill-inserted', (e) => {
 *     e.detail.amount // number
 *   })
 *
 * ### 이벤트 유실 주의
 *
 * 이벤트는 dispatch 시점에 리스너가 등록되어 있어야 받는다.
 * - `kiosk:cron` 은 네이티브가 보류 큐(pending queue)를 두어 페이지 로드 완료
 *   (onPageFinished) / 앱 복귀(onResume) 시 밀린 이벤트를 재전달한다. 유실 없음.
 * - 그 외(카드/지폐기) 이벤트는 페이지 리로드·에러 페이지 표시 중에는 유실될 수 있다.
 *   지폐기 RUN(투입구 개방)은 반드시 리스너 등록 후에 호출할 것.
 */

// ---------------------------------------------------------------------------
// JSON 문자열 파라미터/반환값의 실제 구조
// ---------------------------------------------------------------------------

/** [Android] getInfo() 반환 JSON */
export interface KioskAndroidInfo {
  /** 네이티브 앱 버전 (BuildConfig.VERSION_NAME, 예: "0.1.0") */
  appVersion: string
  /** 패키지명 (예: "dev.payot.kiosk") */
  packageName: string
  /** WebView 가 현재 로드 대상으로 삼는 URL (저장값 > 빌드 기본값) */
  url: string
  /** KICC EasyCardA 결제 앱 설치 여부 */
  easyCardInstalled: boolean
}

/**
 * [Android] getEnv() 반환 JSON — getInfo 필드 + 시스템 진단 정보.
 * Windows 대응(사이드카 GET /sysinfo)은 필드명이 다르다(os/arch/cpuCount/diskTotal…).
 */
export interface KioskEnvInfo {
  appVersion: string
  versionCode: number
  packageName: string
  url: string
  easyCardInstalled: boolean
  /** 제조사/모델/기기 코드 (android.os.Build) */
  manufacturer: string
  model: string
  device: string
  /** 안드로이드 버전 (예: "13") / API 레벨 */
  androidRelease: string
  sdkInt: number
  /** 전체/가용 RAM (bytes), 저사양 메모리 경고 상태 */
  memoryTotal: number
  memoryAvailable: number
  lowMemory: boolean
  /** filesDir 파티션 전체/여유 (bytes) */
  diskTotal: number
  diskFree: number
  /** 부팅 후 경과 (초) */
  uptimeSec: number
}

/** [Windows] getInfo() 반환 JSON */
export interface KioskWindowsInfo {
  /** 셸 어셈블리 버전 (예: "0.1.0") */
  appVersion: string
  /** 항상 "windows" */
  shell: string
  /** 셸이 로드한 URL */
  url: string
}

/** billStatus() 반환 JSON */
export interface BillStatus {
  /** USB 시리얼 포트 연결 여부 */
  connected: boolean
  /** 투입구 개방(RUN) 상태 여부 */
  running: boolean
}

/**
 * cardRequest() 에 넘기는 요청 파라미터 (EasyCardA 연동 매뉴얼의 파라미터 그대로).
 * 값은 전부 문자열로 넣는 것을 권장한다 (네이티브가 Intent extra 로 그대로 전달).
 *
 * 대표 키:
 * - TRAN_TYPE      거래 구분 — "D1" 신용승인, "D4" 승인취소, "B1" 현금영수증, "RB" 망취소 …
 * - TERMINAL_TYPE  단말 유형 — 키오스크는 "40"
 * - TRAN_NO        거래 추적 번호 (임의, 보통 YYMMDDhhmmss)
 * - TOTAL_AMOUNT   총 금액 (원)
 * - TAX / TIP      부가세 / 봉사료 (기본 "0")
 * - TAX_OPTION     "M" 수동 | "A" 총액의 10% 자동계산
 * - INSTALLMENT    "0" 일시불 | "N" N개월 할부
 * - TIMEOUT        카드 대기 타임아웃 (초)
 * - APPROVAL_NUM / APPROVAL_DATE  취소(D4) 시 원거래 승인번호 / 승인일자(YYMMDD)
 * - TEXT_PROCESS / TEXT_COMPLETE  결제 중 / 완료 시 EasyCardA 화면 문구
 *
 * 전체 목록: guide/EasyCardA 연동개발 메뉴얼.xlsx (Parameter 시트)
 */
export type CardRequestParams = Record<string, string | number | boolean>

/** cronSchedule() 에 넘기는 예약 JSON */
export interface CronScheduleInput {
  /** 예약 식별자. 생략 시 UUID 자동 생성. 같은 id 로 다시 등록하면 덮어쓴다. */
  id?: string
  /** 발화 시각 (epoch ms). 필수. */
  at: number
  /**
   * 반복 규칙:
   * - 생략 또는 "none" — 일회성
   * - "daily"          — 매일 (86,400,000ms 간격)
   * - 숫자             — 해당 밀리초 간격 반복
   */
  repeat?: 'none' | 'daily' | number
  /** 발화 시 kiosk:cron 이벤트의 detail.data 로 그대로 되돌아오는 임의 데이터 */
  data?: unknown
}

/** cronList() 반환 JSON 배열의 원소 (네이티브 저장 형태) */
export interface CronEntry {
  id: string
  /** 다음 발화 시각 (epoch ms) — 반복 예약은 발화 때마다 갱신된다 */
  at: number
  /** 반복 간격 ms. 0 = 일회성 */
  period: number
  /** 등록 시 넣은 data (없으면 null) */
  data: unknown
}

// ---------------------------------------------------------------------------
// 네이티브 -> JS 이벤트 detail 타입
// ---------------------------------------------------------------------------

/**
 * kiosk:card-result — 카드 거래 최종 응답.
 * EasyCardA 응답 Intent 의 extras 전체가 문자열화되어 들어온다.
 *
 * 대표 키 (전체는 연동 매뉴얼 참조):
 * - RESULT_CODE   "0000" = 정상 승인. 그 외는 실패/거절.
 *                 (네이티브 자체 오류 시 "XXXX" — 예: EasyCardA 미설치)
 * - RESULT_MSG    결과 메시지
 * - APPROVAL_NUM  승인번호 (취소 시 필요)
 * - APPROVAL_DATE 승인일시 YYMMDDhhmmssN (취소 시 앞 6자리 사용)
 * - TOTAL_AMOUNT  승인 금액
 * - CARD_NAME     카드사/카드명
 */
export type CardResultDetail = Record<string, string>

/** kiosk:card-event — 카드 거래 진행 중 상태 이벤트 (예: E001 "카드를 리딩해주세요") */
export interface CardEventDetail extends Record<string, string> {
  EVENT_CODE: string
  EVENT_MSG: string
}

/** kiosk:bill-line — 지폐기 수신 원본 라인 (디버깅/로깅용. 파싱된 이벤트가 별도로 온다) */
export interface BillLineDetail {
  /** 예: "RUN:OK", "BILL:1000" */
  line: string
}

/** kiosk:bill-inserted — 지폐 1장 투입. 합계 누적은 JS 몫 (장치는 장당 단건 통보) */
export interface BillInsertedDetail {
  /** 투입된 지폐 금액 (원). 예: 1000, 5000, 10000, 50000 */
  amount: number
}

/** kiosk:bill-status — 투입구 개방/차폐 상태 변화 (RUN:OK / STOP:OK 수신 시) */
export interface BillStatusDetail {
  running: boolean
}

/** kiosk:bill-connection — USB 시리얼 연결/해제 */
export interface BillConnectionDetail {
  connected: boolean
  /** 연결 시에만: "vid:pid 제품명" (예: "1a86:7523 USB Serial") */
  device?: string
}

/** kiosk:bill-error — 지폐기 오류 (연결 실패, 전송 실패, 권한 거부, 파싱 실패 등) */
export interface BillErrorDetail {
  message: string
}

/**
 * kiosk:cron — 예약 작업 발화. 두 플랫폼 공통:
 * - Android: AlarmManager. 앱이 백그라운드/종료 상태였다면 보류 큐에 쌓였다가 복귀 시 전달.
 * - Windows: 사이드카 스케줄러 → SSE. 발화 시 접속된 클라이언트가 없으면 보류 후 재접속 때 전달.
 */
export interface CronFireDetail {
  /** cronSchedule() 이 반환한 예약 id */
  id: string
  /** 등록 시 넣은 data (없으면 null) */
  data: unknown
  /** 실제 발화 시각 (epoch ms) — 보류됐다 전달되면 at 보다 늦을 수 있다 */
  firedAt: number
}

// ---------------------------------------------------------------------------
// window.android — Android WebView 브리지 본체
// ---------------------------------------------------------------------------

export interface KioskAndroidBridge {
  // ---------- 공통 ----------

  /** 앱/환경 정보 조회. 반환: KioskAndroidInfo 의 JSON 문자열 — JSON.parse 해서 사용 */
  getInfo(): string

  /**
   * 시스템 진단 정보 조회 (getInfo 상위 호환 — 메모리/저장소/기기/업타임 포함).
   * 반환: KioskEnvInfo 의 JSON 문자열. Windows 대응은 사이드카 GET /sysinfo.
   */
  getEnv(): string

  /** 원격 진단용 파일 로그 최근 줄(기본 500) 반환. Windows 대응은 사이드카 GET /logs. */
  getLogs(): string

  /** 웹에서 진단 로그에 한 줄 남긴다 (장애 재현 흐름 기록용) */
  logWrite(message: string): void

  /** WebView 를 현재 설정된 URL 로 다시 로드 (에러 페이지의 자동 재시도도 이걸 호출) */
  reload(): void

  /**
   * 앱 프로세스 재시작 — 즉시 종료 후 1.5초 뒤 알람으로 자동 재실행된다.
   * WebView 메모리 누수/이상 상태 복구용. 호출하면 페이지 컨텍스트가 통째로 사라진다.
   */
  restartApp(): void

  /**
   * WebView 가 띄울 URL 변경 (SharedPreferences 에 영구 저장 후 즉시 로드).
   * "assets" 를 주면 앱 내장 정적 빌드 모드로 전환된다.
   */
  setUrl(url: string): void

  // ---------- 카드결제 (KICC EasyCardA) ----------

  /**
   * EasyCardA 로 거래 요청을 브로드캐스트한다. 반환값 없음 — 응답은 이벤트로:
   * - 진행 상태: kiosk:card-event (EVENT_CODE/EVENT_MSG)
   * - 최종 응답: kiosk:card-result (RESULT_CODE "0000" = 승인)
   *
   * @param paramsJson CardRequestParams 를 JSON.stringify 한 문자열
   */
  cardRequest(paramsJson: string): void

  // ---------- 지폐기 (USB 시리얼, CH340 9600bps) ----------

  /**
   * USB 시리얼 연결 시도. 결과는 kiosk:bill-connection / kiosk:bill-error 이벤트로.
   * OS USB 권한이 없으면 권한 요청 다이얼로그가 뜨고, 승인되면 자동으로 재연결한다.
   */
  billConnect(): void

  /** 시리얼 연결 해제 (kiosk:bill-connection {connected:false} 발생) */
  billDisconnect(): void

  /**
   * 투입구 개방 (RUN 명령 전송). 이후 지폐가 들어올 때마다 kiosk:bill-inserted 발생.
   * 장치가 RUN:OK 로 응답하면 kiosk:bill-status {running:true} 가 온다.
   */
  billRun(): void

  /** 투입구 차폐 (STOP 명령 전송). STOP:OK 응답 시 kiosk:bill-status {running:false} */
  billStop(): void

  /**
   * 임의 명령 전송 (CRLF 는 네이티브가 붙인다). RUN/STOP 외 확장 명령용.
   * 미연결 상태면 kiosk:bill-error 이벤트가 온다.
   */
  billWrite(command: string): void

  /** 현재 상태 스냅샷. 반환: BillStatus 의 JSON 문자열 */
  billStatus(): string

  // ---------- 크론 (예약 작업 — AlarmManager 기반, 앱 재시작/재부팅에도 유지) ----------

  /**
   * 예약 등록. 발화 시 kiosk:cron 이벤트로 전달된다 (백그라운드 발화분은 보류 후 재전달).
   *
   * @param json CronScheduleInput 을 JSON.stringify 한 문자열
   * @returns 등록된 예약 id (id 미지정 시 자동 생성된 UUID)
   */
  cronSchedule(json: string): string

  /** 예약 취소 */
  cronCancel(id: string): void

  /** 등록된 예약 목록. 반환: CronEntry[] 의 JSON 문자열 */
  cronList(): string
}

// ---------------------------------------------------------------------------
// window.windows — Windows KioskShell(WebView2) 브리지 본체
// ---------------------------------------------------------------------------

/**
 * 셸 제어만 담당한다. 카드/지폐기는 사이드카 KioskAgent(127.0.0.1:9100)의
 * HTTP + SSE 를 웹이 직접 사용한다 (nuxt/app/composables/kiosk/agentWin.ts 참조).
 */
export interface KioskWindowsBridge {
  /** 셸 정보 조회. 반환: KioskWindowsInfo 의 JSON 문자열 */
  getInfo(): string

  /** 설정된 홈 URL 로 다시 이동 */
  reload(): void

  /** 셸 프로세스 재시작 — Android restartApp 과 동일한 역할 */
  restartApp(): void
}

// ---------------------------------------------------------------------------
// 전역 선언 — window.android / window.windows + kiosk:* CustomEvent 타입 매핑
// ---------------------------------------------------------------------------

declare global {
  interface Window {
    /**
     * Android WebView 에서만 존재 (MainActivity 가 "android" 이름으로 주입).
     * 존재 여부가 곧 플랫폼 감지 수단: `if (window.android) { → Android 키오스크 }`
     */
    android?: KioskAndroidBridge

    /**
     * Windows KioskShell(WebView2) 에서만 존재 (부트스트랩 스크립트가 정의).
     * 일반 Windows 브라우저에는 없다 — 그 경우 UA 로 감지해 사이드카만 사용.
     */
    windows?: KioskWindowsBridge
  }

  /**
   * kiosk:* CustomEvent 의 detail 타입 추론.
   *
   * WindowEventMap 은 TypeScript DOM 표준 라이브러리(lib.dom.d.ts)가 정의하는
   * "이벤트 이름 -> 이벤트 객체 타입" 매핑이다. window.addEventListener 는 이
   * 맵을 조회해 핸들러 파라미터 타입을 결정한다 ('click' -> MouseEvent 처럼).
   *
   * 인터페이스는 선언 병합(declaration merging)되므로, declare global 안에서
   * 같은 이름으로 다시 선언하면 표준 맵에 우리 키오스크 이벤트가 **추가**된다
   * (덮어쓰기가 아님). 그 결과:
   *
   *   window.addEventListener('kiosk:bill-inserted', (e) => {
   *     e.detail.amount   // number — 캐스팅 불필요
   *   })
   *
   * 이 병합이 없으면 커스텀 이벤트명은 문자열 오버로드로 빠져 핸들러가 Event 를
   * 받게 되고, 매번 `(e as CustomEvent<BillInsertedDetail>).detail` 캐스팅이
   * 필요하다. removeEventListener 도 같은 맵을 쓰므로 함께 타입 안전해진다.
   *
   * 주의:
   * - 이벤트명은 리터럴 문자열일 때만 추론된다 (변수로 넘기면 string 오버로드).
   * - 이 선언은 컴파일 타임 전용이다 — 네이티브가 실제로 dispatch 하는 detail
   *   구조와 여기 타입이 어긋나도 TS 는 잡아주지 못하므로, 브리지(KioskBridge.kt
   *   / KioskEvents.cs) 변경 시 이 파일도 함께 갱신해야 한다.
   */
  interface WindowEventMap {
    'kiosk:card-result': CustomEvent<CardResultDetail>
    'kiosk:card-event': CustomEvent<CardEventDetail>
    'kiosk:bill-line': CustomEvent<BillLineDetail>
    'kiosk:bill-inserted': CustomEvent<BillInsertedDetail>
    'kiosk:bill-status': CustomEvent<BillStatusDetail>
    'kiosk:bill-connection': CustomEvent<BillConnectionDetail>
    'kiosk:bill-error': CustomEvent<BillErrorDetail>
    'kiosk:cron': CustomEvent<CronFireDetail>
  }
}

export {}
