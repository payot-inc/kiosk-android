# Kiosk Android — WebView JS API

키오스크 앱이 WebView 안의 웹(JS)에 노출하는 인터페이스 문서.
구현: [`KioskBridge.kt`](../../android/app/src/main/java/dev/payot/kiosk/KioskBridge.kt) · [`EasyCardA.kt`](../../android/app/src/main/java/dev/payot/kiosk/EasyCardA.kt) · [`BillAcceptor.kt`](../../android/app/src/main/java/dev/payot/kiosk/BillAcceptor.kt)

두 갈래로 통신한다.

- **JS → 네이티브**: `window.android.*` 메서드 호출 (동기 호출, 즉시 반환)
- **네이티브 → JS**: `window` 의 `CustomEvent` 디스패치 (비동기 결과/이벤트)

> ⚠️ `window.android` 는 네이티브 키오스크 앱 안에서만 존재한다. 일반 브라우저/개발 서버에서는 `undefined` 이므로 `typeof window.android !== "undefined"` 로 가드할 것.

---

## 1. 메서드 (`window.android`)

### 공통

| 메서드 | 반환 | 설명 |
|---|---|---|
| `getInfo()` | `string` (JSON) | 앱/환경 정보. 아래 참고 |
| `reload()` | `void` | 현재 URL 다시 로드 |
| `restartApp()` | `void` | 앱 프로세스 재시작 (약 1.5초 뒤 자동 재실행). 메모리 누수·이상 상태 복구용 |
| `setUrl(url: string)` | `void` | WebView 가 로드할 URL 변경(영구 저장). `"assets"` 를 주면 앱 내장 정적 빌드 모드로 전환 |

`getInfo()` 가 돌려주는 JSON 형태:

```json
{
  "appVersion": "1.0.0",
  "packageName": "dev.payot.kiosk",
  "url": "https://app.coin-machine.com",
  "easyCardInstalled": true
}
```

### 카드결제 (KICC EasyCardA)

| 메서드 | 반환 | 설명 |
|---|---|---|
| `cardRequest(paramsJson: string)` | `void` | EasyCardA 로 거래 요청. `paramsJson` 은 EasyCardA 매뉴얼의 요청 파라미터 그대로(`TRAN_TYPE`, `TOTAL_AMOUNT`, …)를 JSON 문자열로. 응답은 `kiosk:card-result` / `kiosk:card-event` 이벤트로 돌아온다 |

> EasyCardA 앱이 설치돼 있지 않으면 즉시 `kiosk:card-result` 이벤트가 `{ "RESULT_CODE": "XXXX", "RESULT_MSG": "EasyCardA 앱이 설치되어 있지 않습니다" }` 로 발생한다.

### 지폐기 (USB 시리얼 / CH340)

| 메서드 | 반환 | 설명 |
|---|---|---|
| `billConnect()` | `void` | USB 시리얼 지폐기 연결 시도. 결과는 `kiosk:bill-connection` 이벤트 |
| `billDisconnect()` | `void` | 연결 해제 |
| `billRun()` | `void` | 투입구 개방(`RUN`). 이후 지폐 투입마다 `kiosk:bill-inserted` 발생 |
| `billStop()` | `void` | 투입구 차폐(`STOP`) |
| `billWrite(command: string)` | `void` | 임의 시리얼 명령 직접 전송. 명령 체계는 [지폐기-명령체계.md](지폐기-명령체계.md) 참고 |
| `billStatus()` | `string` (JSON) | 현재 연결/구동 상태. `{ "connected": boolean, "running": boolean }` |

### 크론 (예약 작업)

지정한 시각에 알람을 등록하고, 발화 시 `kiosk:cron` 이벤트로 돌려받는다.
앱이 죽어 있어도 알람이 앱을 띄워 전달하며, **재부팅 후에도 예약이 유지**된다(BootReceiver 재등록).

| 메서드 | 반환 | 설명 |
|---|---|---|
| `cronSchedule(json: string)` | `string` (id) | 예약 등록. `json` 형태는 아래 참고. 반환은 등록된 `id`(미지정 시 자동 생성) |
| `cronCancel(id: string)` | `void` | 해당 `id` 예약 취소 |
| `cronList()` | `string` (JSON) | 등록된 예약 배열. 각 항목 `{ id, at, period, data }` |

`cronSchedule` 의 `json`:

```json
{
  "id": "morning-refresh",   // 선택. 생략 시 UUID 자동 생성. 같은 id 면 덮어씀
  "at": 1718600400000,        // 필수. 발화 시각(epoch ms)
  "repeat": "daily",          // 선택. 생략/"none"=1회, "daily"=매일, 숫자=해당 ms 간격 반복
  "data": { "foo": "bar" }    // 선택. 발화 이벤트에 그대로 실려 돌아오는 임의 페이로드
}
```

> 발화는 정확 알람(`setExactAndAllowWhileIdle`)으로 동작한다. 도즈 화이트리스트는 `install.sh` 가 등록한다.
> 앱이 백그라운드/종료 상태에서 울린 이벤트는 큐에 쌓였다가, 화면 복귀·페이지 로드 완료 시 한 번에 전달된다.

---

## 2. 이벤트 (`window.addEventListener`)

네이티브가 `window.dispatchEvent(new CustomEvent(name, { detail }))` 로 보낸다.
데이터는 항상 `event.detail` 에 담긴다.

| 이벤트 이름 | detail | 설명 |
|---|---|---|
| `kiosk:card-result` | EasyCardA 응답 extras 전체 (JSON) | 카드결제 **최종** 응답. `RESULT_CODE`, `RESULT_MSG` 등 매뉴얼 응답 키가 그대로 들어옴 |
| `kiosk:card-event` | `{ EVENT_CODE, EVENT_MSG, ... }` | 카드결제 **진행** 이벤트 (예: `E001` 카드를 리딩해주세요). `EVENT_CODE` 가 있으면 진행 이벤트로 분류 |
| `kiosk:bill-line` | `{ line: string }` | 지폐기에서 수신한 **원본 라인** (디버깅/로그용) |
| `kiosk:bill-inserted` | `{ amount: number }` | 지폐 투입 감지 (`BILL:<금액>` 라인 파싱 결과) |
| `kiosk:bill-status` | `{ running: boolean }` | 투입구 개방/차폐 상태 변화 (`RUN:OK` → true, `STOP:OK` → false) |
| `kiosk:bill-connection` | `{ connected: boolean, device?: string }` | 연결 상태. `device` 는 `"vvvv:pppp ProductName"` 형식 (연결 시) |
| `kiosk:bill-error` | `{ message: string }` | 지폐기 오류 (USB 권한 거부, 미연결 상태 전송, 통신 오류, 금액 파싱 실패 등) |
| `kiosk:cron` | `{ id: string, data: any, firedAt: number }` | 예약 작업 발화. `data` 는 등록 시 넘긴 페이로드, `firedAt` 은 발화 시각(epoch ms) |

---

## 3. 사용 예시

```js
// 가드 — 네이티브 앱 안에서만 동작
const native = typeof window.android !== "undefined" ? window.android : null;

// 앱 정보
if (native) {
  const info = JSON.parse(native.getInfo());
  console.log(info.appVersion, info.easyCardInstalled);
}

// --- 카드결제 ---
window.addEventListener("kiosk:card-event", (e) => {
  console.log("진행:", e.detail.EVENT_CODE, e.detail.EVENT_MSG);
});
window.addEventListener("kiosk:card-result", (e) => {
  console.log("결과:", e.detail.RESULT_CODE, e.detail.RESULT_MSG);
});

native?.cardRequest(JSON.stringify({
  TRAN_TYPE: "...",       // EasyCardA 매뉴얼 참고
  TOTAL_AMOUNT: "1000",
}));

// --- 지폐기 ---
window.addEventListener("kiosk:bill-connection", (e) => {
  console.log("연결:", e.detail.connected, e.detail.device ?? "");
});
window.addEventListener("kiosk:bill-inserted", (e) => {
  console.log("투입 금액:", e.detail.amount);
});
window.addEventListener("kiosk:bill-error", (e) => {
  console.warn("지폐기 오류:", e.detail.message);
});

native?.billConnect();
native?.billRun();        // 투입구 개방
// ... 결제 완료 후
native?.billStop();       // 투입구 차폐

const billState = JSON.parse(native.billStatus());  // { connected, running }

// --- 크론(예약 작업) ---
window.addEventListener("kiosk:cron", (e) => {
  console.log("예약 발화:", e.detail.id, e.detail.data, new Date(e.detail.firedAt));
});

// 매일 새벽 4시에 새로고침 트리거 등록
const next4am = new Date();
next4am.setHours(4, 0, 0, 0);
if (next4am.getTime() <= Date.now()) next4am.setDate(next4am.getDate() + 1);
native?.cronSchedule(JSON.stringify({
  id: "daily-refresh",
  at: next4am.getTime(),
  repeat: "daily",
  data: { action: "reload" },
}));

console.log(JSON.parse(native.cronList()));  // 등록된 예약 확인
// native?.cronCancel("daily-refresh");       // 취소
```

---

## 4. 참고

- 메서드 호출은 **동기**지만 부수효과는 비동기다. 카드/지폐 결과는 반환값이 아니라 **이벤트**로 받는다.
- `window.android` 는 `MainActivity` 의 `addJavascriptInterface(bridge, "android")` 로 주입된다 ([`MainActivity.kt`](../../android/app/src/main/java/dev/payot/kiosk/MainActivity.kt)).
- 카드결제 요청/응답 파라미터의 전체 스펙은 `guide/android/EasyCardA 연동개발 메뉴얼.xlsx` 를 따른다. 브리지는 extras 를 가공 없이 그대로 전달한다.
- 지폐기 시리얼 명령 체계는 [지폐기-명령체계.md](지폐기-명령체계.md) 참고.
