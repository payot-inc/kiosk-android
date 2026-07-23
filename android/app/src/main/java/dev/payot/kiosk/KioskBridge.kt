package dev.payot.kiosk

import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebView(JS) <-> 네이티브 브리지. JS에서는 window.android 로 접근한다.
 *
 * 네이티브 -> JS 이벤트는 window 의 CustomEvent 로 전달:
 *   kiosk:card-result   카드결제 최종 응답 (EasyCardA 응답 extras 전체)
 *   kiosk:card-event    카드결제 진행 이벤트 (EVENT_CODE/EVENT_MSG)
 *   kiosk:bill-line     지폐기 수신 원본 라인 { line }
 *   kiosk:bill-inserted 지폐 투입 { amount }
 *   kiosk:bill-status   투입구 상태 { running }
 *   kiosk:bill-connection 연결 상태 { connected, device? }
 *   kiosk:bill-error    지폐기 오류 { message }
 *   kiosk:cron          예약 작업 발화 { id, data, firedAt }
 */
class KioskBridge(private val activity: MainActivity, private val webView: WebView) {

    private val card = EasyCardA(activity, ::emit)
    private val bill = BillAcceptor(activity, ::emit)

    companion object {
        /** 현재 살아있는 브리지. 크론 알람이 포그라운드 WebView 로 즉시 전달할 때 사용. */
        @Volatile
        var active: KioskBridge? = null
            private set

        /** <img src> 로 바로 로드 가능한 배너 URL 접두사. MainActivity 의 /banner/ 핸들러와 짝. */
        private const val BANNER_BASE = "https://appassets.androidplatform.net/banner/"

        /** 배너로 허용하는 확장자(소문자). */
        private val BANNER_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp")

        /** 파일명 자연 정렬: "2.gif" < "10.gif" (숫자 구간은 수치 비교). */
        private fun naturalCompare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ni = i; while (ni < a.length && a[ni].isDigit()) ni++
                    var nj = j; while (nj < b.length && b[nj].isDigit()) nj++
                    val sa = a.substring(i, ni).trimStart('0').ifEmpty { "0" }
                    val sb = b.substring(j, nj).trimStart('0').ifEmpty { "0" }
                    if (sa.length != sb.length) return sa.length - sb.length
                    val cmp = sa.compareTo(sb)
                    if (cmp != 0) return cmp
                    i = ni; j = nj
                } else {
                    val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (cmp != 0) return cmp
                    i++; j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }

    init {
        card.register()
        bill.register()
        active = this
    }

    fun destroy() {
        if (active === this) active = null
        card.unregister()
        bill.unregister()
        bill.disconnect()
    }

    private fun emit(event: String, detail: JSONObject) {
        webView.post {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('$event',{detail:$detail}));",
                null
            )
        }
    }

    // ---------- 공통 ----------

    @JavascriptInterface
    fun getInfo(): String = JSONObject().apply {
        put("appVersion", BuildConfig.VERSION_NAME)
        put("packageName", activity.packageName)
        put("url", activity.currentUrl())
        put("easyCardInstalled", card.isInstalled())
    }.toString()

    /** 시스템 진단 정보 (getInfo + 메모리·저장소·기기·업타임). 반환: JSON 문자열 */
    @JavascriptInterface
    fun getEnv(): String = KioskEnv.collect(activity, card.isInstalled())
        .put("url", activity.currentUrl())
        .toString()

    /**
     * 홈 배너 이미지 목록. 앱 외부 저장소 banner/ 폴더의 이미지를
     * <img src> 로 바로 로드 가능한 URL(string[]) 의 JSON 으로 즉시 동기 반환한다.
     * png/jpg/jpeg/gif/webp 만, 파일명 자연 정렬(오름차순). 폴더 없음/0장 → "[]".
     * URL 은 WebViewAssetLoader 로 스트리밍(예: .../banner/1.gif) — GIF 가 커도 메모리 부담 없음.
     */
    @JavascriptInterface
    fun getBannerImages(): String {
        val files = activity.bannerDir().listFiles { f ->
            f.isFile && f.extension.lowercase() in BANNER_EXTS
        } ?: return "[]"
        val arr = JSONArray()
        files.sortedWith { x, y -> naturalCompare(x.name, y.name) }
            .forEach { arr.put(BANNER_BASE + Uri.encode(it.name)) }
        return arr.toString()
    }

    /** 원격 진단용 파일 로그 최근 줄 (기본 500줄) 반환 */
    @JavascriptInterface
    fun getLogs(): String = RemoteLog.tail(500)

    /** 웹에서 진단 로그에 한 줄 남긴다 (장애 재현 흐름 기록용) */
    @JavascriptInterface
    fun logWrite(message: String) = RemoteLog.i("[web] $message")

    @JavascriptInterface
    fun reload() {
        webView.post { webView.loadUrl(activity.currentUrl()) }
    }

    /** 앱 프로세스 재시작 (1.5초 뒤 자동으로 다시 뜬다) — 메모리 누수/이상 상태 복구용 */
    @JavascriptInterface
    fun restartApp() {
        KioskApp.restart(activity)
    }

    /**
     * 앱 종료 — 관리자 숨은 제스처(약관 헤더 10회 터치)용 이탈 진입점.
     * 화면고정/Lock Task Mode 로 잠겨 있어도 먼저 풀고 나가며, 프로세스까지 완전 종료한다.
     */
    @JavascriptInterface
    fun exitApp() {
        activity.runOnUiThread {
            try { activity.stopLockTask() } catch (_: Exception) {}  // 화면고정/락태스크면 먼저 해제
            activity.finishAndRemoveTask()                           // 액티비티 + 태스크 제거
            System.exit(0)                                           // 프로세스까지 완전 종료
        }
    }

    /** WebView가 띄울 URL 변경(영구 저장). "assets" 를 주면 내장 정적 빌드 모드. */
    @JavascriptInterface
    fun setUrl(url: String) {
        activity.runOnUiThread { activity.applyUrl(url) }
    }

    // ---------- 카드결제 (KICC EasyCardA) ----------

    /**
     * EasyCardA 로 거래 요청을 브로드캐스트한다.
     * paramsJson 은 매뉴얼의 요청 파라미터 그대로 (TRAN_TYPE, TOTAL_AMOUNT, ...).
     * 응답은 kiosk:card-result / kiosk:card-event 이벤트로 돌아온다.
     */
    @JavascriptInterface
    fun cardRequest(paramsJson: String) {
        card.request(JSONObject(paramsJson))
    }

    // ---------- 지폐기 (USB 시리얼) ----------

    @JavascriptInterface
    fun billConnect() = bill.connect()

    @JavascriptInterface
    fun billDisconnect() = bill.disconnect()

    /** 투입구 개방 — 이후 지폐가 들어올 때마다 kiosk:bill-inserted 이벤트 발생 */
    @JavascriptInterface
    fun billRun() = bill.write("RUN")

    /** 투입구 차폐 */
    @JavascriptInterface
    fun billStop() = bill.write("STOP")

    @JavascriptInterface
    fun billWrite(command: String) = bill.write(command)

    @JavascriptInterface
    fun billStatus(): String = bill.status().toString()

    // ---------- 크론 (예약 작업) ----------

    /**
     * 예약 등록. json = {id?, at(epoch ms), repeat?, data?}.
     * repeat: 생략/"none"=일회성, "daily"=매일, 숫자=해당 밀리초 간격. 반환: 등록 id.
     * 발화 시 kiosk:cron 이벤트({id, data, firedAt})로 전달.
     */
    @JavascriptInterface
    fun cronSchedule(json: String): String =
        CronScheduler.schedule(activity, JSONObject(json))

    @JavascriptInterface
    fun cronCancel(id: String) = CronScheduler.cancel(activity, id)

    @JavascriptInterface
    fun cronList(): String = CronScheduler.list(activity).toString()

    /** 보류된 크론 이벤트를 웹으로 모두 전달한다 (MainActivity 생명주기/알람 수신 시 호출). */
    fun deliverPendingCron() {
        val pending = CronScheduler.takePending(activity)
        for (i in 0 until pending.length()) emit("kiosk:cron", pending.getJSONObject(i))
    }
}
