#!/usr/bin/env bash
# 윈도우 키오스크 셸 빌드 — docker만 있으면 어디서나 가능.
#   1) .NET SDK 리눅스 컨테이너에서 win-x64 전용 크로스 빌드
#   2) NSIS(makensis) 컨테이너로 설치파일 생성
#
#   ./build.sh   → output/windows/KioskShell-Setup-{버전}.exe
#                  (설치 시: Program Files 설치 + 시작메뉴 + 부팅 자동실행 +
#                   WebView2 런타임 자동 설치 + 언인스톨러)
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd .. && pwd)"
OUT="$ROOT/output/windows"
STAGING=".staging"
VERSION="$(grep -o '<Version>[^<]*' KioskShell/KioskShell.csproj | cut -d'>' -f2)"

mkdir -p "$OUT"

echo "==> 1/3 KioskShell.exe + KioskAgent.exe 크로스 빌드 (win-x64)"
# 같은 폴더에 publish 해서 .NET 런타임 파일을 공유한다 (설치 용량 절감)
rm -rf "$STAGING"
docker run --rm \
  -v "$ROOT:/src" \
  -v kiosk-nuget-cache:/root/.nuget \
  -w /src/windows \
  mcr.microsoft.com/dotnet/sdk:8.0 \
  bash -c "
    dotnet publish KioskShell -c Release -r win-x64 --self-contained true -o '/src/windows/$STAGING' &&
    dotnet publish KioskAgent -c Release -r win-x64 --self-contained true -o '/src/windows/$STAGING'
  "
rm -f "$STAGING"/*.pdb

echo "==> 2/3 WebView2 런타임 부트스트래퍼 준비"
mkdir -p vendor
if [ ! -f vendor/MicrosoftEdgeWebView2Setup.exe ]; then
  curl -fsSL -o vendor/MicrosoftEdgeWebView2Setup.exe \
    "https://go.microsoft.com/fwlink/p/?LinkId=2124703"
fi

echo "==> 3/3 NSIS 설치파일 생성"
docker build -q -f Dockerfile.nsis -t kiosk-nsis-builder . > /dev/null
docker run --rm \
  -v "$ROOT:/src" \
  -w /src/windows \
  kiosk-nsis-builder \
  makensis -INPUTCHARSET UTF8 \
    -DSTAGING="/src/windows/$STAGING" \
    -DSRCDIR=/src/windows/KioskShell \
    -DREDIST=/src/windows/vendor \
    -DVERSION="$VERSION" \
    -DOUTFILE="/src/output/windows/KioskShell-Setup-$VERSION.exe" \
    ${EASYCARDK_URL:+-DEASYCARDK_URL="$EASYCARDK_URL"} \
    ${RUSTDESK_URL:+-DRUSTDESK_URL="$RUSTDESK_URL"} \
    installer.nsi

rm -rf "$STAGING"

echo ""
echo "==> 완료: output/windows/KioskShell-Setup-$VERSION.exe (win-x64 전용)"
echo "    윈도우 장비에서 설치파일 실행 → 설치 중 '부팅 시 자동 실행' 선택 가능"
echo "    설정 변경: 시작메뉴 > Kiosk Shell > 설정 파일 (kiosk.ini)"
