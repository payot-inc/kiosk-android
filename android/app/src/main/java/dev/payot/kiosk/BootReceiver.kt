package dev.payot.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 부팅 완료 / 자가 업데이트 후 키오스크 자동 실행.
 * Android 10+ 백그라운드 액티비티 제한은 SYSTEM_ALERT_WINDOW(appops) 허용으로 우회하고,
 * 즉시 실행이 막히는 펌웨어 대비로 알람 재실행도 함께 예약한다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> {
                launch(context)
                // 부팅 시 사라진 크론 알람을 저장된 예약대로 재등록
                runCatching { CronScheduler.rescheduleAll(context) }
                KioskApp.scheduleRestart(context, delayMs = 3000)
            }
            // 자가 업데이트로 APK 가 교체되면 프로세스가 종료된다 → 다시 띄운다
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                launch(context)
                KioskApp.scheduleRestart(context, delayMs = 3000)
            }
        }
    }

    private fun launch(context: Context) {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
