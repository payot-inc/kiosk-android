package dev.payot.kiosk

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.FileInputStream

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "url"
        private const val PREFS = "kiosk"
        private const val PREF_URL = "kiosk_url"
        // "assets" 모드: 앱 내장 정적 웹 빌드(assets/www)를 로드
        private const val ASSETS_URL = "https://appassets.androidplatform.net/www/index.html"
    }

    private lateinit var webView: WebView
    private lateinit var bridge: KioskBridge
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/www/", WebViewAssetLoader.AssetsPathHandler(this))
            // 홈 배너: 매장 운영자가 파일관리자로 넣은 이미지를 스트리밍 로드
            // (base64 인라인 대신). getBannerImages() 가 만드는 URL 과 짝을 이룬다.
            // 앱 외부 저장소는 InternalStoragePathHandler 가 거부하므로 커스텀 핸들러로 직접 연다.
            .addPathHandler("/banner/", bannerPathHandler())
            .build()
    }

    /**
     * /banner/<파일명> → 외부 배너 폴더의 파일을 스트리밍하는 커스텀 핸들러.
     * 폴더/파일 없으면 null(404) 반환 — 디렉터리 존재를 강제하지 않아 크래시하지 않는다.
     * 경로 탈출(..) 은 정규화 경로가 배너 폴더 안인지 검사해 차단한다.
     */
    private fun bannerPathHandler() = WebViewAssetLoader.PathHandler { path ->
        try {
            val base = bannerDir().canonicalFile
            val file = File(base, path).canonicalFile
            if (file != base && !file.path.startsWith(base.path + File.separator)) return@PathHandler null
            if (!file.isFile) return@PathHandler null
            WebResourceResponse(bannerMime(file.name), null, FileInputStream(file))
        } catch (_: Exception) {
            null
        }
    }

    private fun bannerMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 상시 켜짐: 앱이 떠 있는 동안 화면 타임아웃 무시
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 부팅/알람으로 실행될 때 잠금화면 위로 올라오고 꺼진 화면을 깨운다
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)
                ?.requestDismissKeyguard(this, null)
        }
        // 키오스크는 화면 전체를 차지한다: 시스템바/디스플레이 컷아웃(펀치홀)을 피하지 않고
        // 그 영역까지 WebView 로 그린다. (실기기엔 컷아웃이 없고, 에뮬레이터의 펀치홀 여백 제거)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        webView = WebView(this)
        setContentView(webView)

        // 배너 폴더(/sdcard/KioskBanner)를 미리 만들어 둔다 — 운영자가 파일관리자에서
        // 위치를 찾아 이미지를 넣을 수 있도록. 전체 파일 접근 권한이 있어야 생성/읽기가 된다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            RemoteLog.w("배너 폴더 접근 권한 없음 — appops set $packageName MANAGE_EXTERNAL_STORAGE allow 필요")
        }
        runCatching { bannerDir().mkdirs() }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            textZoom = 100
        }
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            // 페이지 로드 완료(리스너 등록 끝) 시점에 밀린 크론 이벤트 전달
            override fun onPageFinished(view: WebView, url: String) {
                if (::bridge.isInitialized) bridge.deliverPendingCron()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) showErrorPage(request.url.toString(), error.description.toString())
            }

            // WebView 렌더러 죽으면 액티비티 재생성으로 복구 (키오스크 무인 운영 대비)
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                webView.destroy()
                recreate()
                return true
            }
        }

        bridge = KioskBridge(this, webView)
        webView.addJavascriptInterface(bridge, "android")

        onBackPressedDispatcher.addCallback(this) { /* 키오스크: 뒤로가기 차단 */ }

        intent.getStringExtra(EXTRA_URL)?.let(::saveUrl)
        webView.loadUrl(currentUrl())

        Updater.start(this) // 사이드로드 자가 업데이트 (UPDATE_URL 설정 시)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_URL)?.let {
            saveUrl(it)
            webView.loadUrl(currentUrl())
        }
    }

    override fun onResume() {
        super.onResume()
        // 백그라운드에서 알람이 울려 큐에 쌓인 크론 이벤트를 복귀 시 전달
        if (::bridge.isInitialized) bridge.deliverPendingCron()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        if (::bridge.isInitialized) bridge.destroy()
        super.onDestroy()
    }

    /** 우선순위: SharedPreferences 저장값 > BuildConfig.KIOSK_URL */
    fun currentUrl(): String {
        val saved = prefs().getString(PREF_URL, null) ?: BuildConfig.KIOSK_URL
        return if (saved == "assets") ASSETS_URL else saved
    }

    fun applyUrl(url: String) {
        saveUrl(url)
        webView.loadUrl(currentUrl())
    }

    private fun saveUrl(url: String) {
        prefs().edit().putString(PREF_URL, url).apply()
    }

    /**
     * 홈 배너 이미지 폴더. 매장 운영자가 파일관리자로 이미지를 넣을 수 있도록
     * 내부 저장소 최상위의 공용 폴더(/sdcard/KioskBanner)를 쓴다.
     * (Android 11+ 에선 Android/data 하위가 파일앱에서 막혀 앱전용 폴더는 운영자가 못 넣음.
     *  → 공용 폴더 + MANAGE_EXTERNAL_STORAGE 조합. install.sh 가 appops 로 자동 허용.)
     * assetLoader 의 /banner/ 핸들러와 KioskBridge.getBannerImages() 가 공유한다.
     */
    fun bannerDir(): File = File(Environment.getExternalStorageDirectory(), "KioskBanner")

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, webView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showErrorPage(failedUrl: String, reason: String) {
        val html = """
            <!DOCTYPE html><html lang="ko"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{font-family:sans-serif;display:flex;flex-direction:column;align-items:center;
                   justify-content:center;height:100vh;margin:0;background:#0f172a;color:#e2e8f0}
              code{color:#94a3b8;font-size:12px;margin-top:8px;word-break:break-all;max-width:80%}
            </style></head><body>
            <h2>화면을 불러올 수 없습니다</h2>
            <p>5초 후 자동으로 다시 시도합니다…</p>
            <code>$failedUrl</code><code>$reason</code>
            <script>setTimeout(function(){ window.android.reload(); }, 5000);</script>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
