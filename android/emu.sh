#!/usr/bin/env bash
# kiosk AVD 에뮬레이터(가상머신)를 띄운다 — 부팅 완료까지 대기 후 반환.
# 부팅되면 실물 지폐기 브리지(adb reverse 포트 바인딩 포함)도 자동으로 함께 띄운다.
#
#   ./android/emu.sh              # 에뮬 + 실물 시리얼 브리지 (Mac에 꽂힌 지폐기)
#   BILL=off ./android/emu.sh     # 에뮬만 (지폐기 브리지 안 띄움)
#   ./android/emu.sh -l           # 등록된 AVD 목록
#   ./android/emu.sh other-avd    # 다른 AVD 이름 지정
#
# 이미 에뮬레이터가 떠 있으면 재사용하고, 지폐기 브리지도 안 떠 있으면 그때 띄운다.
# 에뮬레이터/브리지 모두 백그라운드로 떠서 이 스크립트가 끝나도 계속 살아 있다.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
EMU="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"
AVD="${1:-kiosk}"
BILL="${BILL:-on}"                                     # on | off (기본: 실물 브리지 기동)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"               # 저장소 루트
WATCH_LOG="/tmp/kiosk-bill-watch.log"

[ -x "$EMU" ] || { echo "emulator 실행파일 없음: $EMU — ANDROID_SDK_ROOT 확인" >&2; exit 1; }

# 실물 지폐기 브리지(adb reverse 포함)를 백그라운드로 띄운다 (이미 떠 있으면 재사용).
start_bill_bridge() {
  [ "$BILL" = "off" ] && { echo "[emu] BILL=off — 지폐기 브리지 생략"; return; }
  if pgrep -f 'bill-emu-watch.sh' >/dev/null 2>&1; then
    echo "[emu] 지폐기 watcher 이미 실행 중 — 재사용"
    return
  fi
  echo "[emu] 실물 지폐기 브리지 기동 — 로그: $WATCH_LOG"
  nohup "$ROOT/scripts/bill-emu-watch.sh" >"$WATCH_LOG" 2>&1 &
  disown
}

if [ "$AVD" = "-l" ] || [ "$AVD" = "--list" ]; then
  "$EMU" -list-avds
  exit 0
fi

# 이미 온라인 에뮬레이터가 있으면 그대로 재사용
if "$ADB" devices | awk 'NR>1 && $1 ~ /^emulator-/ && $2=="device"{f=1} END{exit !f}'; then
  echo "[emu] 이미 실행 중인 에뮬레이터를 재사용합니다"
  start_bill_bridge
  exit 0
fi

# AVD 존재 확인
if ! "$EMU" -list-avds | grep -qx "$AVD"; then
  echo "[emu] AVD '$AVD' 가 없습니다. 등록된 목록:" >&2
  "$EMU" -list-avds >&2
  echo "생성: sdkmanager \"system-images;android-34;google_apis;arm64-v8a\" && \\" >&2
  echo "      avdmanager create avd -n $AVD -k \"system-images;android-34;google_apis;arm64-v8a\"" >&2
  exit 1
fi

echo "[emu] $AVD 부팅 중… (창이 뜹니다)"
# 스크립트가 끝나도 살아있도록 nohup + disown 으로 분리 실행
nohup "$EMU" -avd "$AVD" >/dev/null 2>&1 &
disown

"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
  sleep 1
done
echo "[emu] $AVD 부팅 완료"
start_bill_bridge
echo "[emu] 이제 ./android/install.sh 로 앱 설치/실행"
