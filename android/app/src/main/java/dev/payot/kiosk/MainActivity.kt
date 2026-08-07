package dev.payot.kiosk

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
        // CSS 회전 각도(0/90/180/270). 기본 0 = 회전 없음(가로 그대로).
        // 세로 장착 기기는 90 또는 270 으로 지정해야 콘텐츠가 바로 선다(어느 쪽인지는 장착 방향에 따라 다름).
        // 웹에서 window.android.setRotation(270), 또는 설치 시 intent extra
        // (--ei rotation 270) 로 기기별 지정 → SharedPreferences 영구 저장.
        const val EXTRA_ROTATION = "rotation"
        private const val PREF_ROTATION = "kiosk_rotation"
        private const val DEFAULT_ROTATION_DEG = 0
    }

    private lateinit var webView: WebView
    private lateinit var touchOverlay: TouchOverlayView
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
        // 터치 피드백 오버레이를 WebView 위에 겹친다. 오버레이는 터치를 받지 않고
        // (isClickable=false) 그리기만 하므로 웹 입력에는 영향이 없다.
        touchOverlay = TouchOverlayView(this)
        setContentView(
            FrameLayout(this).apply {
                addView(webView)
                addView(touchOverlay)
            }
        )

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
                injectPortraitRotation()
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
        // -1 = extra 없음. 0 을 넘겨 회전 보정을 끌 수 있어야 하므로 0 을 sentinel 로 쓰지 않는다.
        intent.getIntExtra(EXTRA_ROTATION, -1).takeIf { it >= 0 }
            ?.let { prefs().edit().putInt(PREF_ROTATION, normalizeRotation(it)).apply() }
        webView.loadUrl(currentUrl())

        // device owner 가 아닌 기기에서는 무음 설치가 안 되므로, 업데이트가 준비되면
        // 이 팝업으로 사용자에게 확인을 받는다. (owner 기기는 이 콜백 없이 무음 설치)
        Updater.setUpdatePrompt { versionName, proceed -> showUpdatePrompt(versionName, proceed) }
        Updater.start(this) // 사이드로드 자가 업데이트 (UPDATE_URL 설정 시)
    }

    /**
     * 새 버전 감지 시 표시하는 업데이트 확인 팝업. [proceed] 를 호출하면 그 시점(포그라운드)에
     * 설치가 커밋되어 시스템 설치 확인창이 뜬다. 항상 메인 스레드에서 호출된다.
     */
    private fun showUpdatePrompt(versionName: String, proceed: () -> Unit) {
        if (isFinishing || isDestroyed) return
        runCatching {
            android.app.AlertDialog.Builder(this)
                .setTitle("업데이트 안내")
                .setMessage("새 버전(${versionName})이 있습니다.\n지금 업데이트하시겠습니까?")
                .setCancelable(false)
                .setPositiveButton("업데이트") { _, _ -> proceed() }
                .setNegativeButton("나중에", null)
                .show()
        }.onFailure { RemoteLog.w("업데이트 팝업 표시 실패: ${it.message}") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_URL)?.let {
            saveUrl(it)
            webView.loadUrl(currentUrl())
        }
        intent.getIntExtra(EXTRA_ROTATION, -1).takeIf { it >= 0 }?.let(::applyRotation)
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

    /** CSS 회전 각도(0/90/180/270). prefs 저장값 우선, 없으면 기본 0. */
    fun currentRotation(): Int = prefs().getInt(PREF_ROTATION, DEFAULT_ROTATION_DEG)

    /** 90도 단위로 정규화(0/90/180/270). 그 외 값은 가장 가까운 90의 배수로 스냅 후 [0,360) 로 감싼다. */
    private fun normalizeRotation(deg: Int): Int =
        ((Math.round(deg / 90.0).toInt() * 90) % 360 + 360) % 360

    /** 회전 각도 변경(영구 저장) 후 현재 페이지에 즉시 재적용. 90도 단위로 정규화. */
    fun applyRotation(deg: Int) {
        prefs().edit().putInt(PREF_ROTATION, normalizeRotation(deg)).apply()
        injectPortraitRotation()
    }

    /**
     * 누른 지점을 오버레이에 넘겨 피드백을 그린다. 이벤트는 그대로 흘려보내므로
     * (반환값을 가로채지 않음) 웹 입력 동작에는 영향이 없다.
     *
     * 손가락이 닿는 순간(DOWN)만 찍는다 — 이동 궤적까지 그리면 스크롤할 때 잔상이 남는다.
     * 좌표는 창 좌표계라 CSS 회전 각도와 무관하게 실제 손가락 위치에 찍힌다.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val down = ev.actionMasked == MotionEvent.ACTION_DOWN ||
            ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        if (down && ::touchOverlay.isInitialized) {
            touchOverlay.addTouch(ev.getX(ev.actionIndex), ev.getY(ev.actionIndex))
        }
        return super.dispatchTouchEvent(ev)
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

    /**
     * 회전 보정. 기본(0)에서는 아무것도 하지 않는다 — 가로 화면 그대로 쓴다.
     *
     * 액티비티는 landscape 고정이다. 대상 보드가 앱의 세로 요청을 무시하고 어차피 가로 창을
     * 주기 때문에, 매니페스트로 세로를 만드는 건 불가능하다. 세로 장착 기기에서는 WebView 를
     * 창 크기(가로) 그대로 둬 하드웨어 surface 잘림을 피하고, 고정 1080×1920 웹 콘텐츠를
     * CSS 로 회전시켜 세로로 채운다(90 또는 270 — 어느 쪽이 바로 서는지는 물리 장착 방향에 달렸다).
     * viewport 가 width=device-width(밀도 160 → CSS 1px = 물리 1px)라 좌표가 1:1 로 맞는다.
     * SPA 라 한 번 주입한 <style> 은 클라이언트 라우팅에도 유지된다.
     */
    private fun injectPortraitRotation() {
        val js = """
            (function(){
              var deg = ${currentRotation()}, W = 1080, H = 1920;
              var s = document.getElementById('__kioskRot');
              if(!s){ s = document.createElement('style'); s.id='__kioskRot';
                      document.documentElement.appendChild(s); }
              if(deg === 0){ s.textContent = ''; return; }
              // 회전 후 콘텐츠가 1사분면 밖으로 나가므로 각도별로 되돌려 놓는다.
              var tx = deg === 90 ? H : deg === 180 ? W : 0;
              var ty = deg === 90 ? 0 : deg === 180 ? H : W;
              s.textContent =
                'html,body{margin:0!important;padding:0!important;overflow:hidden!important;background:#000}' +
                'body{position:fixed!important;top:0;left:0;width:'+W+'px!important;height:'+H+'px!important;' +
                'transform-origin:0 0!important;transform:translate('+tx+'px,'+ty+'px) rotate('+deg+'deg)!important;}';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
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
