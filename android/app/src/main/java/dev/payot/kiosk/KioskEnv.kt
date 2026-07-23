package dev.payot.kiosk

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONObject

/**
 * 시스템 진단 정보 수집 — 윈도우 사이드카 /sysinfo(SysInfo.cs)의 안드로이드 대응.
 * getInfo() 의 축소된 정보(버전/URL)를 넘어 메모리·저장소·기기·업타임까지 담는다.
 * window.android.getEnv() 로 노출한다.
 */
object KioskEnv {
    fun collect(context: Context, easyCardInstalled: Boolean): JSONObject {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)

        val stat = StatFs(context.filesDir.absolutePath)
        val diskTotal = stat.blockCountLong * stat.blockSizeLong
        val diskFree = stat.availableBlocksLong * stat.blockSizeLong

        return JSONObject().apply {
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("packageName", context.packageName)
            put("easyCardInstalled", easyCardInstalled)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidRelease", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("memoryTotal", memory.totalMem)
            put("memoryAvailable", memory.availMem)
            put("lowMemory", memory.lowMemory)
            put("diskTotal", diskTotal)
            put("diskFree", diskFree)
            put("uptimeSec", SystemClock.elapsedRealtime() / 1000)
        }
    }
}
