#!/usr/bin/env bash
# Docker로 APK 빌드. 로컬에 JDK/Android SDK 불필요 — docker만 있으면 된다.
#
#   ./build.sh                # debug 빌드 (URL 기본값 http://localhost:3000)
#   ./build.sh release        # release 빌드
#   ./build.sh debug assets   # 내장 정적 빌드(assets/www) 모드로 빌드
#   ./build.sh debug http://192.168.0.10:3000   # 임의 URL을 기본값으로
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=kiosk-android-builder
VARIANT="${1:-debug}"
KIOSK_URL="${2:-}"

case "$VARIANT" in
  debug)   TASK=assembleDebug ;;
  release) TASK=assembleRelease ;;
  *) echo "사용법: ./build.sh [debug|release] [기본 URL|assets]" >&2; exit 1 ;;
esac

GRADLE_ARGS=("$TASK" "--no-daemon")
if [ -n "$KIOSK_URL" ]; then
  GRADLE_ARGS+=("-PkioskUrl=$KIOSK_URL")
fi

echo "==> 빌더 이미지 준비 (최초 1회만 오래 걸림)"
docker build --platform linux/amd64 -t "$IMAGE" .

echo "==> gradle $TASK"
docker run --rm --platform linux/amd64 \
  -v "$PWD:/workspace" \
  -v kiosk-gradle-cache:/root/.gradle \
  -w /workspace \
  "$IMAGE" gradle "${GRADLE_ARGS[@]}"

# 결과물을 프로젝트 루트 output/ 으로 복사
OUT_DIR="../output/android"
mkdir -p "$OUT_DIR"
cp "app/build/outputs/apk/$VARIANT/app-$VARIANT.apk" "$OUT_DIR/kiosk-$VARIANT.apk"

echo ""
echo "==> 완료: output/android/kiosk-$VARIANT.apk"
echo "    설치: ./install.sh $VARIANT"
