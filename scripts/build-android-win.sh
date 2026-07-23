#!/usr/bin/env bash
# ============================================================
#  원격 윈도우 빌드머신(win-build)에서 안드로이드 APK 를 빌드하고
#  결과 APK 만 이 Mac 의 release/android/ 로 회수한다.
#
#    scripts/build-android-win.sh                 # debug 빌드
#    scripts/build-android-win.sh release         # release 빌드
#    scripts/build-android-win.sh debug http://192.168.0.10:3000
#    scripts/build-android-win.sh debug assets
#
#  동작:
#    1) android/ 소스를 원격 c:\build\android-kiosk 로 동기화 (tar over ssh)
#    2) 원격에서 android/build.bat 실행 (기본 Docker 빌드)
#    3) 생성된 APK 만 scp 로 release/android/ 에 회수
#
#  사전조건(원격 win-build):
#    - OpenSSH 접속 가능 (ssh win-build)
#    - tar (Windows 10 17063+ 기본 포함)
#    - Docker Desktop (기본)  또는  native gradle+SDK
#      → 방식 전환은 android/build.bat 의 BUILD_MODE 참고
#
#  환경변수로 대상 변경 가능:
#    REMOTE=win-build  REMOTE_DIR=c:/build/android-kiosk
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."   # 프로젝트 루트

REMOTE="${REMOTE:-win-build}"
REMOTE_DIR="${REMOTE_DIR:-c:/build/android-kiosk}"     # scp 용 (슬래시)
REMOTE_DIR_WIN="${REMOTE_DIR//\//\\}"                  # cmd 용 (백슬래시)

VARIANT="${1:-debug}"
KIOSK_URL="${2:-}"

case "$VARIANT" in
  debug|release) ;;
  *) echo "사용법: $0 [debug|release] [기본URL|assets]" >&2; exit 1 ;;
esac

echo "==> [1/3] 프로젝트 동기화  →  $REMOTE:$REMOTE_DIR"
ssh "$REMOTE" "if not exist $REMOTE_DIR_WIN mkdir $REMOTE_DIR_WIN"
# COPYFILE_DISABLE=1: macOS bsdtar 가 리소스 포크(._*) 파일을 넣지 않도록
COPYFILE_DISABLE=1 tar -czf - -C android \
  --exclude='./build' \
  --exclude='./.gradle' \
  --exclude='./.kotlin' \
  --exclude='./app/build' \
  --exclude='./output' \
  . | ssh "$REMOTE" "tar -xzf - -C $REMOTE_DIR_WIN"

echo "==> [2/3] 원격 빌드  (variant=$VARIANT${KIOSK_URL:+  url=$KIOSK_URL})"
ssh "$REMOTE" "cd /d $REMOTE_DIR_WIN && build.bat $VARIANT $KIOSK_URL"

echo "==> [3/3] APK 회수  →  release/android/"
mkdir -p release/android
scp "$REMOTE:$REMOTE_DIR/output/android/kiosk-$VARIANT.apk" "release/android/kiosk-$VARIANT.apk"

echo ""
echo "==> 완료: release/android/kiosk-$VARIANT.apk"
