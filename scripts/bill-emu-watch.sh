#!/usr/bin/env bash
# 에뮬레이터 생명주기에 맞춰 지폐기 브리지를 자동 관리한다.
#
#   에뮬레이터가 뜨면 → adb reverse 설정 + bill-bridge.py 시작(시리얼 오픈, 지폐기 연결)
#   에뮬레이터가 닫히면 → 브리지 중지(시리얼 해제) + reverse 제거
#
# 실행:
#   ./scripts/bill-emu-watch.sh            # 실물 지폐기(시리얼) 브리지
#   NUXT_PORT=3300 ./scripts/bill-emu-watch.sh   # nuxt dev 포트가 다를 때
#
# 주의: 같은 포트(6790)를 여는 다른 브리지가 이미 떠 있으면 충돌한다.
#       기존에 수동 실행한 bill-bridge.py 는 먼저 종료할 것.
set -uo pipefail
cd "$(dirname "$0")/.."

BILL_PORT="${BILL_PORT:-6790}"     # 지폐기 브리지 TCP 포트 (앱이 붙는 소켓)
NUXT_PORT="${NUXT_PORT:-3000}"     # nuxt dev 서버 포트 (에뮬레이터 localhost:3000 → PC)
BRIDGE="scripts/bill-bridge.py"
POLL=2                             # 감시 주기(초)

bridge_pid=""

# 온라인 상태인 emulator-* 시리얼 하나를 고른다(없으면 빈 문자열).
emu_serial() {
  adb devices | awk 'NR>1 && $1 ~ /^emulator-/ && $2=="device"{print $1; exit}'
}

start_bridge() {
  local serial="$1"
  # 이미 살아있으면 아무것도 안 함
  if [ -n "$bridge_pid" ] && kill -0 "$bridge_pid" 2>/dev/null; then
    return
  fi
  # 포워딩 재설정(에뮬레이터 재기동마다 사라지므로 매번 건다)
  ANDROID_SERIAL="$serial" adb reverse tcp:"$BILL_PORT" tcp:"$BILL_PORT" >/dev/null 2>&1 || true
  ANDROID_SERIAL="$serial" adb reverse tcp:3000 tcp:"$NUXT_PORT"          >/dev/null 2>&1 || true
  echo "[watch] $serial 감지 → reverse(bill:$BILL_PORT, nuxt:3000→$NUXT_PORT) + 실물 브리지 시작"
  python3 "$BRIDGE" --port "$BILL_PORT" &
  bridge_pid=$!
}

stop_bridge() {
  [ -z "$bridge_pid" ] && return
  echo "[watch] 에뮬레이터 종료 → 브리지 중지(시리얼 해제)"
  kill "$bridge_pid" 2>/dev/null || true
  wait "$bridge_pid" 2>/dev/null || true
  bridge_pid=""
}

cleanup() { echo; stop_bridge; exit 0; }
trap cleanup INT TERM

echo "[watch] 에뮬레이터 대기 중… (Ctrl-C 종료)"
while true; do
  serial="$(emu_serial)"
  if [ -n "$serial" ]; then
    start_bridge "$serial"
    # 에뮬레이터는 살아있는데 브리지가 죽었으면(시리얼 분리 등) 다음 루프에서 재기동
    if [ -n "$bridge_pid" ] && ! kill -0 "$bridge_pid" 2>/dev/null; then
      echo "[watch] 브리지가 종료됨 — ${POLL}s 후 재시도(지폐기 USB 연결 확인)"
      bridge_pid=""
    fi
  else
    stop_bridge
  fi
  sleep "$POLL"
done
