package dev.payot.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * KICC EasyCardA 카드결제 연동 (Broadcast 방식, EasyCardA v1.1.0.0+).
 *
 * 요청: "kr.co.kicc.easycarda.ACTION_REQ_BROADCAST" 액션으로 sendBroadcast
 * 응답: "kr.co.kicc.easycarda.broadcast" 액션 수신
 *   - EVENT_CODE 가 있으면 진행 이벤트(E001 카드를 리딩해주세요 ...)
 *   - 없으면 최종 응답 (RESULT_CODE "0000" = 정상승인)
 *
 * 파라미터 상세: guide/EasyCardA 연동개발 메뉴얼.xlsx (Parameter 시트)
 *
 * 실기기 확인된 버그: TIMEOUT 파라미터를 줘도 카드 대기(E001) 상태에서 응답 없이
 * 무한정 멈추고, 그동안 이후 요청은 전부 E009("진행중인 거래가 있습니다")로 막힌다.
 * 그래서 TIMEOUT 을 자체 워치독으로 다시 강제한다 (아래 armWatchdog).
 */
class EasyCardA(
    private val context: Context,
    private val emit: (String, JSONObject) -> Unit
) {
    companion object {
        const val PACKAGE = "kr.co.kicc.easycarda"
        const val REQ_ACTION = "kr.co.kicc.easycarda.ACTION_REQ_BROADCAST"
        const val RES_ACTION = "kr.co.kicc.easycarda.broadcast"
        // EasyCardA 통신 지연분 여유 — TIMEOUT 만료 직후 바로 끊지 않고 살짝 더 기다린다.
        private const val WATCHDOG_BUFFER_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var watchdog: Runnable? = null
    private var lastEventCode: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val json = JSONObject()
            intent.extras?.let { extras ->
                for (key in extras.keySet()) {
                    when (val value = @Suppress("DEPRECATION") extras.get(key)) {
                        is Array<*> -> json.put(key, JSONArray(value.toList()))
                        else -> json.put(key, value?.toString())
                    }
                }
            }
            val eventCode = intent.getStringExtra("EVENT_CODE")
            val isEvent = !eventCode.isNullOrEmpty()
            if (isEvent) lastEventCode = eventCode else clearWatchdog()
            emit(if (isEvent) "kiosk:card-event" else "kiosk:card-result", json)
        }
    }

    fun register() {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(RES_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun unregister() {
        clearWatchdog()
        runCatching { context.unregisterReceiver(receiver) }
    }

    /**
     * EasyCardA TIMEOUT 무한대기 버그 우회.
     * 요청 파라미터에 TIMEOUT(초)이 있으면, (TIMEOUT + 버퍼) 가 지나도록 최종 응답이
     * 오지 않을 때 스스로 타임아웃 결과를 emit 하고 상태를 정리한다.
     * 최종 응답(비-이벤트)이 도착하면 receiver 에서 clearWatchdog() 으로 취소된다.
     */
    private fun armWatchdog(params: JSONObject) {
        val timeoutSec = when (val t = params.opt("TIMEOUT")) {
            is Number -> t.toInt()
            is String -> t.toIntOrNull() ?: 0
            else -> 0
        }
        if (timeoutSec <= 0) return
        clearWatchdog()
        val r = Runnable {
            watchdog = null
            emit(
                "kiosk:card-result",
                JSONObject()
                    .put("RESULT_CODE", "TIMEOUT")
                    .put("RESULT_MSG", "카드 결제 응답 시간 초과")
                    .put("LAST_EVENT_CODE", lastEventCode ?: JSONObject.NULL)
            )
            lastEventCode = null
        }
        watchdog = r
        handler.postDelayed(r, timeoutSec * 1000L + WATCHDOG_BUFFER_MS)
    }

    private fun clearWatchdog() {
        watchdog?.let { handler.removeCallbacks(it) }
        watchdog = null
    }

    /** 카드 UI 활성화 판단용 — 실제 EasyCardA 앱 설치 여부. */
    fun isInstalled(): Boolean = isRealInstalled()

    private fun isRealInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun request(params: JSONObject) {
        if (isRealInstalled()) {
            val intent = Intent(REQ_ACTION).setPackage(PACKAGE)
            for (key in params.keys()) {
                when (val value = params.get(key)) {
                    is Boolean -> intent.putExtra(key, value)
                    is Int -> intent.putExtra(key, value)
                    else -> intent.putExtra(key, value.toString())
                }
            }
            lastEventCode = null
            context.sendBroadcast(intent)
            armWatchdog(params)
            return
        }
        // 실제 EasyCardA 앱이 없으면 오류로 알린다.
        emit(
            "kiosk:card-result",
            JSONObject()
                .put("RESULT_CODE", "XXXX")
                .put("RESULT_MSG", "EasyCardA 앱이 설치되어 있지 않습니다")
        )
    }
}
