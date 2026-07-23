package dev.payot.kiosk

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * 키오스크 무인 운영용 Application.
 * 처리되지 않은 예외로 앱이 죽으면 AlarmManager로 1.5초 뒤 자동 재실행한다.
 * (크래시 루프가 되더라도 키오스크에서는 "계속 다시 뜨는 것"이 맞는 동작)
 */
class KioskApp : Application() {

    companion object {
        private const val TAG = "KioskApp"
        private const val RESTART_DELAY_MS = 1500L

        /**
         * delayMs 후 MainActivity를 다시 띄우는 알람 등록.
         * launchMode=singleTask 라 이미 떠 있으면 onNewIntent만 불리고 화면은 유지된다
         * (부팅 시 즉시실행+알람이 겹쳐도 무해).
         */
        fun scheduleRestart(context: Context, delayMs: Long = RESTART_DELAY_MS) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pendingIntent = PendingIntent.getActivity(
                context, 9900, intent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + delayMs,
                pendingIntent
            )
        }

        /** 즉시 종료 + 자동 재실행 (JS restartApp / 크래시 핸들러 공용) */
        fun restart(context: Context, delayMs: Long = RESTART_DELAY_MS) {
            scheduleRestart(context, delayMs)
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RemoteLog.init(this)
        RemoteLog.i("앱 시작 (v${BuildConfig.VERSION_NAME})")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "uncaught exception on ${thread.name} — 재시작 예약", throwable)
            RemoteLog.e("uncaught on ${thread.name}: ${Log.getStackTraceString(throwable)}")
            restart(this)
        }

        if (BuildConfig.DEBUG) registerDebugCrashReceiver()
    }

    /**
     * 크래시 자동복구 테스트용 (debug 빌드 전용):
     *   adb shell am broadcast -a dev.payot.kiosk.DEBUG_CRASH
     */
    private fun registerDebugCrashReceiver() {
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    android.os.Handler(mainLooper).post {
                        throw RuntimeException("DEBUG_CRASH 테스트 크래시")
                    }
                }
            },
            android.content.IntentFilter("dev.payot.kiosk.DEBUG_CRASH"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
    }
}
