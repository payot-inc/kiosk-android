package dev.payot.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 크론 알람 수신. 보류 큐에 적재한 뒤,
 *  - 살아있는 WebView 가 있으면 즉시 kiosk:cron 전달
 *  - 앱이 죽어 있으면 MainActivity 를 띄워 onPageFinished/onResume 에서 flush 되게 한다.
 */
class CronReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CronScheduler.ACTION_FIRE) return
        val id = intent.getStringExtra(CronScheduler.EXTRA_ID) ?: return
        CronScheduler.onFired(context, id)

        val bridge = KioskBridge.active
        if (bridge != null) {
            bridge.deliverPendingCron()
        } else {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
