#!/usr/bin/env bash
# adb로 장비에 설치하고 실행한다.
#
#   ./install.sh                  # debug APK 설치 + 실행
#   ./install.sh release
#   ./install.sh debug http://192.168.0.10:3000   # 실행하면서 URL 변경(영구 저장)
#   ./install.sh debug assets                      # 내장 정적 빌드 모드로 전환
set -euo pipefail
cd "$(dirname "$0")"

VARIANT="${1:-debug}"
URL="${2:-}"
APK="../output/android/kiosk-$VARIANT.apk"

# 무선 디버깅 시 mDNS 자동연결로 같은 기기가 2개(ip:port + adb-...tcp)로 잡혀
# adb 가 "more than one device" 로 실패한다. 대상을 자동 선택한다.
# ANDROID_SERIAL 을 직접 지정하면 그 값을 그대로 쓴다.
if [ -z "${ANDROID_SERIAL:-}" ]; then
  online=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  if [ "$(printf '%s\n' "$online" | grep -c .)" -gt 1 ]; then
    sel=$(printf '%s\n' "$online" | grep ':[0-9]' | head -1)
    [ -z "$sel" ] && sel=$(printf '%s\n' "$online" | head -1)
    export ANDROID_SERIAL="$sel"
    echo "여러 기기 감지 → ANDROID_SERIAL=$ANDROID_SERIAL 사용" >&2
  fi
fi

if [ ! -f "$APK" ]; then
  echo "APK가 없습니다: output/android/kiosk-$VARIANT.apk — 먼저 ./build.sh $VARIANT 를 실행하세요" >&2
  exit 1
fi

adb install -r "$APK"

# --- 키오스크 운영 설정 ---
# 부팅 직후/백그라운드에서 액티비티를 띄울 수 있게 overlay 권한 허용 (Android 10+ 제한 우회)
adb shell appops set dev.payot.kiosk SYSTEM_ALERT_WINDOW allow || true
# 배터리 최적화(도즈) 대상에서 제외 — 재실행 알람이 지연되지 않도록
adb shell dumpsys deviceidle whitelist +dev.payot.kiosk > /dev/null || true
# 자가 업데이트: 알 수 없는 출처 앱 설치 허용 (설치 확인 화면 최소화)
adb shell appops set dev.payot.kiosk REQUEST_INSTALL_PACKAGES allow || true
# 홈 배너: /sdcard/KioskBanner 를 운영자가 파일관리자로 넣고 앱이 읽도록 전체 파일 접근 허용
adb shell appops set dev.payot.kiosk MANAGE_EXTERNAL_STORAGE allow || true
# 상시 켜짐: 시스템 화면 타임아웃도 사실상 무제한으로 (앱 자체도 KEEP_SCREEN_ON 유지)
adb shell settings put system screen_off_timeout 2147460000 || true

if [ -n "$URL" ]; then
  adb shell am start -n dev.payot.kiosk/.MainActivity --es url "$URL"
else
  adb shell am start -n dev.payot.kiosk/.MainActivity
fi
