plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// WebView 기본 URL. 우선순위: -PkioskUrl=... > 빌드타입별 기본값.
//   debug   → 개발 서버(devKioskUrl)
//   release → releaseKioskUrl("assets"). 운영은 CI(release.yml)가 vars.KIOSK_URL 을
//             -PkioskUrl 로 주입해 https://app.coin-machine.com 이 박힌다.
//  값 종류: "assets"(내장 정적빌드 assets/www) | "http://..."(원격 URL)
//  ※ 이 값은 '기본값'일 뿐이며, 기기에 저장된 URL(SharedPreferences)이 있으면 그게 우선.
val kioskUrlOverride: String? = project.findProperty("kioskUrl") as String?
val devKioskUrl = "http://192.168.199.63:4050"        // 개발(debug) 기본
val releaseKioskUrl = "https://app.coin-machine.com"  // 운영(release) 기본

// 자동 업데이트: GitHub 릴리스 API(.../releases/latest) URL. 빌드 시 -PupdateUrl=... 로 설정.
// 비우면 자동 업데이트 비활성. (릴리스 워크플로가 자동으로 주입한다)
val updateUrl: String = (project.findProperty("updateUrl") as String?) ?: ""

// 비공개 저장소 릴리스를 받기 위한 읽기 전용 PAT. -PupdateToken=... (CI Secret 으로 주입).
// APK 에 내장되므로 반드시 fine-grained · Contents:Read-only · 해당 저장소 한정 토큰만 쓴다.
val updateToken: String = (project.findProperty("updateToken") as String?) ?: ""

// 버전. 릴리스 워크플로가 태그/실행번호로 주입한다. 로컬 빌드는 기본값 사용.
val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName: String = (project.findProperty("appVersionName") as String?) ?: "0.1.0"

// 릴리스 서명: 저장소에 키/비밀번호를 두지 않는다. CI 가 GitHub Secret 에서 프로퍼티로 주입하고
// (release.yml), 로컬 릴리스 빌드 시엔 ~/.gradle/gradle.properties 나 -P 로 지정한다.
//   RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
// 프로퍼티가 없으면(예: 로컬 debug 빌드) release 는 서명되지 않는다.
val releaseStoreFile = (project.findProperty("RELEASE_STORE_FILE") as String?)?.let { file(it) }
val hasReleaseSigning = releaseStoreFile?.exists() == true

android {
    namespace = "dev.payot.kiosk"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.payot.kiosk"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        // KIOSK_URL 은 빌드타입별로 지정(아래 buildTypes)
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
            }
        }
    }

    buildTypes {
        debug {
            // 기본 Android 디버그 키로 서명 (로컬/시크릿 불필요). adb install -r 는 같은 PC에서 유지됨.
            buildConfigField("String", "KIOSK_URL", "\"${kioskUrlOverride ?: devKioskUrl}\"")
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "KIOSK_URL", "\"${kioskUrlOverride ?: releaseKioskUrl}\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.11.0")
    // 지폐기 USB 시리얼 (CH340 지원)
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
