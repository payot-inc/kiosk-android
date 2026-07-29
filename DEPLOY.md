# 배포 가이드 — GitHub Release (안드로이드 키오스크)

`v*` 태그를 push 하면 GitHub Actions 가 **서명된 release APK** 를 빌드해 GitHub 릴리스로 발행하고,
기기는 그 릴리스를 **자동 업데이트**로 받는다.

- 워크플로: [`.github/workflows/release.yml`](.github/workflows/release.yml) (`release-android`)
- 자가 업데이트 로직: [`Updater.kt`](android/app/src/main/java/dev/payot/kiosk/Updater.kt)
- 저장소: `payot-inc/kiosk-android` (PUBLIC)

---

## 1. 한 줄 요약

```bash
git checkout main && git pull        # 최신화
git tag v0.6.0                       # 버전 태그 (semver)
git push origin v0.6.0               # → CI 가 릴리스 자동 발행
```

태그를 push 하는 순간부터는 **자동**이다. 나머지는 확인만 하면 된다.

---

## 2. 배포 절차 (상세)

### 1) main 최신화 · 빌드 확인
```bash
git checkout main && git pull
# (선택) 로컬 릴리스 빌드가 되는지 미리 확인
cd android && ./gradlew assembleRelease \
  -PRELEASE_STORE_FILE="$PWD/keystore/kiosk-release.keystore" \
  -PRELEASE_STORE_PASSWORD=<키비번> -PRELEASE_KEY_ALIAS=kiosk -PRELEASE_KEY_PASSWORD=<키비번>
```

### 2) 버전 결정 (Semantic Versioning)
- `vMAJOR.MINOR.PATCH` (예: `v0.6.0`)
- `versionName` = 태그에서 `v` 제거 (`v0.6.0` → `0.6.0`)

### 3) 태그 생성 & push
```bash
git tag v0.6.0
git push origin v0.6.0
```
> ⚠️ 태그는 **main 에 커밋을 먼저 push 한 뒤** 만든다 (릴리스는 태그가 가리키는 커밋으로 빌드됨).

### 4) CI 진행 확인
```bash
gh run watch "$(gh run list --workflow release-android -L1 --json databaseId --jq '.[0].databaseId')" \
  -R payot-inc/kiosk-android --exit-status
```
또는 GitHub 저장소 **Actions 탭** 에서 `release-android` 실행 확인. (~2~3분)

### 5) 릴리스 발행 확인
```bash
gh release view v0.6.0 -R payot-inc/kiosk-android
gh api repos/payot-inc/kiosk-android/releases/latest --jq '.tag_name'   # latest 가 방금 태그인지
```
릴리스에 **`kiosk-release.apk`** 와 **`manifest.json`** 두 에셋이 있어야 정상.

---

## 3. 버전 코드 규칙 (중요)

| 항목 | 값 | 비고 |
|---|---|---|
| `versionName` | 태그 (`0.6.0`) | 표시용 |
| `versionCode` | **GitHub Actions 실행번호**(`github.run_number`) | **단조 증가**. 태그 숫자와 무관 |

- 기기의 업데이트 판단은 **`versionCode` 기준**이다 (`manifest.versionCode > 현재`면 업데이트).
- 실행번호는 릴리스마다 자동으로 커지므로, 새 태그는 항상 더 큰 versionCode 를 갖는다 → 정상 업데이트.

---

## 4. 발행되는 산출물

CI 가 릴리스에 올리는 것:
- **`kiosk-release.apk`** — 서명된 운영 APK
  - WebView URL: `https://app.coin-machine.com` (운영, `vars.KIOSK_URL`)
  - 자동업데이트 URL(`UPDATE_URL`) 내장
- **`manifest.json`**
  ```json
  { "android": { "versionCode": N, "versionName": "0.6.0",
                 "apk": "kiosk-release.apk", "sha256": "...", "notes": "v0.6.0" } }
  ```

---

## 5. 기기 자동 업데이트 동작

- **대상**: `device owner` 로 지정된 기기 + 새 `versionCode`
- **확인 시점**: 앱 시작 **10초 후 1회** (주기 재확인 없음) → 부팅/재기동 때 반영. 이용 중엔 끼어들지 않음.
- **흐름**: `releases/latest` 확인 → 다운로드 → **sha256 검증** → **무음 설치**(device owner) → **자동 재실행**(`MY_PACKAGE_REPLACED`)

> device owner 가 아닌 기기는 무음 설치가 안 되고 설치 확인창이 뜬다. 프로비저닝 시 device owner 지정 필요.

---

## 6. 설정 (이미 구성됨 — 참고용)

### GitHub Secrets (서명)
| 이름 | 용도 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | 릴리스 keystore(base64) |
| `SIGNING_STORE_PASSWORD` | 스토어 비밀번호 |
| `SIGNING_KEY_PASSWORD` | 키 비밀번호 |
| `SIGNING_KEY_ALIAS` | 키 별칭 (`kiosk`) |

설정/변경:
```bash
base64 -i android/keystore/kiosk-release.keystore | gh secret set SIGNING_KEYSTORE_BASE64 -R payot-inc/kiosk-android
gh secret set SIGNING_STORE_PASSWORD -R payot-inc/kiosk-android   # 프롬프트에 입력
```

### GitHub Variables
| 이름 | 값 | 용도 |
|---|---|---|
| `KIOSK_URL` | `https://app.coin-machine.com` | 운영 WebView URL |

```bash
gh variable set KIOSK_URL -R payot-inc/kiosk-android --body "https://app.coin-machine.com"
```

### (선택) 비공개 저장소로 전환 시
- 릴리스를 기기가 받으려면 읽기 전용 PAT 필요:
  ```bash
  gh secret set KIOSK_UPDATE_TOKEN -R payot-inc/kiosk-android
  ```
- 공개 저장소면 불필요.

---

## 7. 롤백

versionCode 는 계속 증가하므로 **"이전 버전으로 내리기"도 새 릴리스로** 한다:
```bash
git revert <문제커밋>   # 또는 이전 소스 상태로 되돌리는 커밋
git push origin main
git tag v0.6.1 && git push origin v0.6.1   # 새 태그 → 기기가 자동으로 이 버전 받음
```
> 서명키가 바뀌면 기존 설치와 서명 불일치로 자동업데이트가 막힌다. **릴리스 키는 절대 교체하지 말 것**(교체 시 전 기기 재설치 필요).

---

## 8. 로컬에서 서명된 운영 APK 만들기 (선택)

CI 없이 운영 구성 APK 를 뽑아야 할 때:
```bash
KIOSK_KEYSTORE_PW=<키비번> scripts/build-android-win.sh release
# → release/android/kiosk-release.apk (운영 URL + 릴리스 서명)
```
정식 배포는 항상 **태그 push(CI)** 로 한다. 위는 확인/임시용.

---

## 9. 체크리스트

- [ ] main 에 배포할 커밋이 모두 push 됨
- [ ] 버전(semver) 결정
- [ ] `git tag vX.Y.Z && git push origin vX.Y.Z`
- [ ] Actions `release-android` 성공
- [ ] `releases/latest` 가 방금 태그, 에셋 2개(apk, manifest) 확인
- [ ] (선택) 테스트 기기 재기동 후 자동 업데이트 확인
