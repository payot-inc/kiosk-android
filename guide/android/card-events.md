# 카드결제 이벤트/오류 수신 가이드 (웹팀용)

키오스크 안드로이드 앱에서 카드결제(KICC EasyCardA) 진행 상태와 오류를 웹(JS)에서 받는 방법.

- 네이티브 구현: [`KioskBridge.kt`](../../android/app/src/main/java/dev/payot/kiosk/KioskBridge.kt) · [`EasyCardA.kt`](../../android/app/src/main/java/dev/payot/kiosk/EasyCardA.kt)
- 기본 API 문서: [`js-api.md`](./js-api.md)
- 원본 스펙: [`EasyCardA 연동개발 메뉴얼.xlsx`](./EasyCardA%20연동개발%20메뉴얼.xlsx) (EventCode / Parameter 시트)

> 이 문서의 이벤트 값은 실기기(리더 ED-977M, EasyCardA 1.1.1.8)에서 카드 오투입을 실제로 재현해 로그로 검증한 내용입니다.

---

## 통신 구조 (2갈래)

- **웹 → 네이티브 (요청)**: `window.android.cardRequest(JSON.stringify(params))` — 동기 호출, 즉시 반환. 결과는 반환값이 아니라 **이벤트**로 온다.
- **네이티브 → 웹 (응답/진행)**: `window` 에 `CustomEvent` 로 디스패치. 두 종류:

| 이벤트 | 언제 | 페이로드 | 판별 |
|---|---|---|---|
| `kiosk:card-event` | 거래 **진행 중** (카드 대기, 리딩 실패, 승인중 …) | `{ EVENT_CODE, EVENT_MSG, ... }` | `EVENT_CODE` 존재 |
| `kiosk:card-result` | 거래 **최종** (승인/거절/오류/타임아웃) | `{ RESULT_CODE, RESULT_MSG, ... }` | `RESULT_CODE` 존재 |

> ⚠️ **핵심**: "카드를 잘못 넣었다" 같은 안내는 **최종 결과(`card-result`)가 아니라 진행 이벤트(`card-event`)로 온다.**
> 실기기 확인 — IC 카드 오투입 시 `kiosk:card-event` 로 `EVENT_CODE: "E010", EVENT_MSG: "IC 실패, MS로 거래요망"` 가 시도할 때마다 반복 수신됨.

---

## 받는 코드 (복붙용)

```js
const native = typeof window.android !== "undefined" ? window.android : null;
// ⚠️ window.android 는 키오스크 앱 안에서만 존재. 일반 브라우저에선 undefined → 반드시 가드.

// 1) 진행 이벤트 (카드 투입 안내 / 오류 안내가 여기로 옴)
window.addEventListener("kiosk:card-event", (e) => {
  const { EVENT_CODE, EVENT_MSG } = e.detail;
  showGuide(EVENT_MSG);            // 그냥 EVENT_MSG 를 화면에 띄워도 됨
  // 코드별 커스텀 처리가 필요하면 EVENT_CODE 로 분기 (아래 표 참고)
});

// 2) 최종 결과
window.addEventListener("kiosk:card-result", (e) => {
  const r = e.detail;
  if (r.RESULT_CODE === "0000") {
    // 승인 성공: r.APPROVAL_NUM, r.TOTAL_AMOUNT, r.CARD_NAME ...
  } else if (r.RESULT_CODE === "TIMEOUT") {
    // 카드 대기 시간 초과 (네이티브 워치독)
  } else if (r.RESULT_CODE === "XXXX") {
    // 네이티브 오류 (예: EasyCardA 미설치) — r.RESULT_MSG 확인
  } else {
    // 그 외 = 카드사/VAN 거절. r.RESULT_MSG 표시
  }
});

// 3) 결제 시작
native?.cardRequest(JSON.stringify({
  TRAN_TYPE: "D1", TERMINAL_TYPE: "40",
  TRAN_NO: "260727173735",   // 보통 YYMMDDhhmmss
  TOTAL_AMOUNT: 1000, TAX: 0, TIP: 0, INSTALLMENT: "0",
  TIMEOUT: 60,
}));
```

---

## EVENT_CODE 표 (진행 이벤트 · 매뉴얼 EventCode 시트 기준)

| CODE | MSG | 성격 |
|---|---|---|
| E001 | 카드를 리딩해주세요 | 안내(대기) |
| E002 | 카드 인입 | 안내 |
| E003 | 리딩완료 | 안내 |
| E004 | 서명을 대기중입니다 | 안내 |
| E005 | 승인중입니다 | 안내 |
| E006 | 장비 초기화 | 안내 |
| E007 | 통신취소중입니다 | 안내 |
| E008 | pin입력 대기중입니다 | 안내 |
| E009 | 진행중인 거래가 있습니다 | **오류(중복요청)** |
| **E010** | **IC 실패, MS로 거래요망** | **오류(오투입/폴백)** |
| **E011** | **IC카드로 거래해주세요** | **오류(오투입)** |
| **E012** | **카드를 다시 읽어주세요** | **오류(리딩실패)** |
| E013 | 카드 빠짐 | 오류 |
| E014 | 카드 없음 (TIT리더기) | 오류 |
| E015 | 초기화할 수 없습니다 | 오류 |
| E016 | pin 다운로드 중 | 안내 |
| E017 | pin 다운로드 완료 | 안내 |
| E018 | pin 다운로드 실패 | 오류 |
| E020 | 파일 다운로드 중 | 안내 |

**오투입 안내가 필요한 건 주로 E010 / E011 / E012.** 이 세 개를 받아서 "카드를 다시 넣어주세요" UI를 띄우면 된다.

### 참고: EVENT_CODE 매핑 상수 (TypeScript)

```ts
export const CARD_EVENT_MESSAGES: Record<string, string> = {
  E001: "카드를 리딩해주세요",
  E002: "카드 인입",
  E003: "리딩완료",
  E004: "서명을 대기중입니다",
  E005: "승인중입니다",
  E006: "장비 초기화",
  E007: "통신취소중입니다",
  E008: "PIN 입력 대기중입니다",
  E009: "진행중인 거래가 있습니다",
  E010: "IC 실패, MS로 거래요망",
  E011: "IC카드로 거래해주세요",
  E012: "카드를 다시 읽어주세요",
  E013: "카드 빠짐",
  E014: "카드 없음",
  E015: "초기화할 수 없습니다",
  E016: "PIN 다운로드 중",
  E017: "PIN 다운로드 완료",
  E018: "PIN 다운로드 실패",
  E020: "파일 다운로드 중",
};

/** 사용자에게 재투입을 유도해야 하는 오류성 이벤트 */
export const CARD_ERROR_EVENT_CODES = new Set(["E009", "E010", "E011", "E012", "E013", "E014", "E015", "E018"]);
```

---

## 주의사항 (자주 놓치는 것)

1. **`card-event` 는 여러 번 온다.** 오투입을 반복하면 E010 이 매번 온다(실측 6회). 화면 안내는 계속 갱신하되, **"결제 완료/실패 확정"은 `card-result` 에서만** 판단할 것.
2. **리스너를 먼저 등록**하고 `cardRequest` 를 호출할 것 (응답이 비동기).
3. `window.android` 가드 필수 (개발 브라우저에서는 `undefined`).
4. 안내 문구를 그대로 쓰려면 `EVENT_MSG` 를, 로직 분기는 `EVENT_CODE` 를 사용. (문구는 EasyCardA 버전에 따라 미세하게 다를 수 있으므로 분기는 **코드 기준** 권장.)

---

## 실측 로그 예시 (카드 오투입 재현)

```
[17:37:37] EVENT_CODE : E001  | EVENT_MSG : 카드를 리딩해주세요.       ← 대기 시작
[17:37:39] EVENT_CODE : E010  | EVENT_MSG : IC 실패, MS로 거래요망     ← 오투입 1
[17:37:42] EVENT_CODE : E010  | EVENT_MSG : IC 실패, MS로 거래요망     ← 오투입 2
[17:37:46] EVENT_CODE : E010  | EVENT_MSG : IC 실패, MS로 거래요망     ← 오투입 3
   ... (오투입 시도할 때마다 반복) ...
```

> EasyCardA → POS broadcast(`kr.co.kicc.easycarda.broadcast`) → 키오스크 앱 리시버 → 웹 `kiosk:card-event` 로 전달되는 경로가 실기기에서 확인됨.
