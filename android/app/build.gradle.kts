plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// WebView 기본 URL. 빌드 시 -PkioskUrl=... 로 변경 가능.
//  - "http://localhost:5090" : PC의 로컬 웹 개발서버 (adb reverse tcp:5090 tcp:5090 필요)
//  - "assets"                : 앱에 내장된 정적 빌드 (android/app/src/main/assets/www)
//  - 그 외                    : 임의의 원격 URL
val kioskUrl: String = (project.findProperty("kioskUrl") as String?) ?: "assets"

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
        buildConfigField("String", "KIOSK_URL", "\"$kioskUrl\"")
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
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
