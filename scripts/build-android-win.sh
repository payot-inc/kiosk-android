#!/usr/bin/env bash
# ============================================================
#  원격 윈도우 빌드머신(win-build)에서 안드로이드 APK 를 빌드하고
#  결과 APK 만 이 Mac 의 release/android/ 로 회수한다.
#
#    scripts/build-android-win.sh            # 개발환경 (기본)
#    scripts/build-android-win.sh dev        # 개발환경
#    scripts/build-android-win.sh release    # 릴리즈환경
#
#  WebView URL 은 build.gradle.kts 의 빌드타입 기본값으로 고정된다(인자 없음):
#    dev(debug)  → http://192.168.199.63:4050   (개발 서버)
#    release     → https://app.coin-machine.com (운영)
#
#  릴리즈환경 서명(선택): 로컬 keystore(android/keystore/kiosk-release.keystore)로
#  서명하려면 비밀번호를 환경변수로 준다.  없으면 릴리스는 서명되지 않는다(설치 불가).
#    KIOSK_KEYSTORE_PW=... scripts/build-android-win.sh release
#  ※ 정식 서명 릴리스는 CI(release.yml, 태그 push)가 담당한다. 이 모드는 로컬 확인용.
#
#  동작:
#    1) android/ 소스를 원격 c:\build\android-kiosk 로 동기화 (tar over ssh)
#    2) 원격에서 android/build.bat 실행 (네이티브 gradle)
#    3) 생성된 APK 만 scp 로 release/android/ 에 회수
#
#  환경변수로 대상 변경 가능:  REMOTE=win-build  REMOTE_DIR=c:/build/android-kiosk
# ============================================================
set -euo pipefail
cd "$(dirname "$0")/.."   # 프로젝트 루트

REMOTE="${REMOTE:-win-build}"
REMOTE_DIR="${REMOTE_DIR:-c:/build/android-kiosk}"     # scp 용 (슬래시)
REMOTE_DIR_WIN="${REMOTE_DIR//\//\\}"                  # cmd 용 (백슬래시)

ENVIRONMENT="${1:-dev}"
case "$ENVIRONMENT" in
  dev)     VARIANT=debug ;;
  release) VARIANT=release ;;
  *) echo "사용법: $0 [dev|release]" >&2; exit 1 ;;
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

echo "==> [2/3] 원격 빌드  (환경=$ENVIRONMENT / variant=$VARIANT)"
REMOTE_CMD="cd /d $REMOTE_DIR_WIN && "
if [ "$VARIANT" = "release" ] && [ -n "${KIOSK_KEYSTORE_PW:-}" ]; then
  # 릴리스 서명 프로퍼티를 ORG_GRADLE_PROJECT_* 로 전달 (gradle 이 프로젝트 프로퍼티로 인식)
  REMOTE_CMD+="set \"ORG_GRADLE_PROJECT_RELEASE_STORE_FILE=$REMOTE_DIR_WIN\\keystore\\kiosk-release.keystore\" && "
  REMOTE_CMD+="set \"ORG_GRADLE_PROJECT_RELEASE_STORE_PASSWORD=$KIOSK_KEYSTORE_PW\" && "
  REMOTE_CMD+="set \"ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS=kiosk\" && "
  REMOTE_CMD+="set \"ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD=$KIOSK_KEYSTORE_PW\" && "
elif [ "$VARIANT" = "release" ]; then
  echo "  ⚠️  KIOSK_KEYSTORE_PW 미설정 → 릴리스가 서명되지 않습니다(설치 불가). 정식 릴리스는 CI 사용." >&2
fi
ssh "$REMOTE" "${REMOTE_CMD}build.bat $VARIANT"

echo "==> [3/3] APK 회수  →  release/android/"
mkdir -p release/android
scp "$REMOTE:$REMOTE_DIR/output/android/kiosk-$VARIANT.apk" "release/android/kiosk-$VARIANT.apk"

echo ""
echo "==> 완료: release/android/kiosk-$VARIANT.apk  (환경=$ENVIRONMENT)"
