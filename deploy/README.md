# GitHub 릴리스로 배포·자동 업데이트

키오스크 안드로이드 앱을 **플레이스토어 없이** 비공개 GitHub 저장소로 배포하고, 현장 기기가
스스로 최신 버전을 받아 설치하도록 하는 구성이다.

```
개발자                    GitHub(비공개)                 현장 키오스크
  │  git tag v0.2.0          │  Actions: 빌드+서명         │
  ├─ git push origin v0.2.0 ─▶  릴리스 v0.2.0 생성 ────────┤ 6시간마다 releases/latest 확인
                             │   ├ kiosk-release.apk       │ versionCode 높으면
                             │   └ manifest.json           │ APK 받아 설치
```

## 동작 원리

- 앱의 [`Updater`](../android/app/src/main/java/dev/payot/kiosk/Updater.kt)가 6시간마다
  `.../releases/latest` (GitHub API)를 확인한다.
- 릴리스에 올라온 `manifest.json` 의 `android.versionCode` 가 설치된 것보다 높으면,
  같은 릴리스의 `kiosk-release.apk` 에셋을 받아(`sha256` 검증) `PackageInstaller` 로 설치한다.
- **비공개 저장소**라 다운로드에 인증이 필요하다 → 앱에 읽기 전용 토큰이 내장된다(아래 참고).
- 릴리스는 [`.github/workflows/release.yml`](../.github/workflows/release.yml)이 `v*` 태그 push 시 자동 생성한다.
  `versionCode` 는 워크플로 실행번호(단조 증가), `versionName` 은 태그(`v0.2.0`→`0.2.0`).

## 최초 1회 설정

### 1) 저장소 준비 (아직 git 저장소가 아니면)

```bash
cd /Users/payot/dev/01_desktopapp/kiosk-android
git init && git add -A && git commit -m "init"
gh repo create payot/kiosk --private --source=. --push   # 저장소 이름은 원하는 대로
```

> `android/keystore/kiosk.keystore` 는 의도적으로 커밋된 **개발용 서명키**다.
> 항상 같은 키로 서명해야 `install -r`/자가 업데이트가 되므로 저장소에 둔다(스토어 배포용 아님).

### 2) 다운로드용 토큰 (기기에 내장됨)

기기가 비공개 릴리스를 받으려면 읽기 전용 토큰이 필요하다. **Fine-grained PAT** 로 최소 권한만 준다.

1. GitHub → Settings → Developer settings → **Fine-grained tokens** → Generate new token
2. Repository access: **Only select repositories** → 이 저장소 하나만
3. Permissions → Repository → **Contents: Read-only** (그 외 전부 No access)
4. 만료일은 길게(예: 1년). 만료 전 재발급 후 새 릴리스를 내면 기기가 새 토큰이 든 APK로 갱신된다.
5. 생성된 토큰을 저장소 → Settings → Secrets and variables → **Actions → Secrets** 에
   `KIOSK_UPDATE_TOKEN` 으로 등록.

> 이 토큰은 APK 에 박혀 배포되므로 누구든 APK 를 뜯으면 읽을 수 있다. 그래서 **읽기 전용·이 저장소 한정**
> 이어야 한다(노출돼도 소스 읽기 이상은 불가, 언제든 폐기·회전 가능). 소스까지 감추고 싶으면 릴리스만
> 담는 별도 비공개 저장소를 파서 그 저장소에만 토큰을 발급하면 된다.

### 3) 운영 웹 URL (선택)

WebView 가 띄울 운영 URL을 저장소 → Settings → Secrets and variables → **Actions → Variables** 에
`KIOSK_URL` 로 등록한다. 비우면 [`build.gradle.kts`](../android/app/build.gradle.kts) 기본값을 쓴다.

> 앱 내장 정적 빌드(`assets`) 모드로 릴리스하려면 워크플로에 `scripts/sync-web.sh`(nuxt generate)
> 실행 단계를 먼저 넣어야 한다. 현재 워크플로는 원격 URL 방식만 다룬다.

## 새 버전 배포

```bash
# 코드 변경 후
git commit -am "지폐기 안정성 개선"
git tag v0.2.0
git push && git push origin v0.2.0
```

Actions 탭에서 `release-android` 워크플로가 끝나면 릴리스가 생기고, 현장 기기는 다음 확인 주기(≤6시간)에
자동으로 업데이트된다. 즉시 확인시키려면 기기에서 앱을 재시작하면 된다(시작 10초 후 1차 확인).

## 무인(무확인) 설치

일반 사이드로드는 `REQUEST_INSTALL_PACKAGES` 가 있어도 마지막에 설치 확인 화면이 뜰 수 있다.
확인 없이 완전 자동 설치하려면 기기를 **디바이스 오너**로 지정한다(공장초기화 직후, 계정 등록 전 1회):

```bash
adb shell dpm set-device-owner dev.payot.kiosk/.DeviceAdmin
```

## 문제 해결

- 로그: `adb logcat -s Updater` — 확인/다운로드/설치 단계가 찍힌다.
- 업데이트가 안 뜬다: 릴리스의 `manifest.json` `versionCode` 가 기기 설치본보다 큰지, 토큰(`KIOSK_UPDATE_TOKEN`)이
  유효한지 확인. 토큰 만료 시 401 로 조용히 실패한다.
- `sha256 불일치`: 릴리스 에셋이 손상/교체됨 — 재발행.
```
