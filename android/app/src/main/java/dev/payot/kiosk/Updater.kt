package dev.payot.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 사이드로드 자가 업데이트 — GitHub Releases(비공개 저장소) 기반. 스토어 미사용.
 *
 * BuildConfig.UPDATE_URL 은 GitHub 릴리스 API(.../releases/latest)를 가리키고,
 * 비공개 저장소이므로 BuildConfig.UPDATE_TOKEN(읽기 전용 PAT)으로 인증한다.
 *
 * 흐름:
 *   1) 최신 릴리스 JSON 조회
 *   2) manifest.json 에셋 다운로드 → android.versionCode 확인
 *   3) 현재(BuildConfig.VERSION_CODE)보다 높으면 APK 에셋 다운로드(+sha256 검증) 후 설치
 *
 * 매니페스트 형식: deploy/manifest.example.json
 * 릴리스는 .github/workflows/release.yml 이 태그(v*) push 시 자동 생성한다.
 *
 * 완전 무인 설치는 디바이스 오너(또는 시스템앱) 권한이 필요하다.
 * 일반 사이드로드에서는 REQUEST_INSTALL_PACKAGES 가 있어도 마지막 확인 화면이 뜰 수 있다.
 * (디바이스 오너 지정: adb shell dpm set-device-owner dev.payot.kiosk/.DeviceAdmin)
 */
object Updater {
    private const val TAG = "Updater"
    private const val INSTALL_ACTION = "dev.payot.kiosk.INSTALL_RESULT"

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "kiosk-updater").apply { isDaemon = true }
    }
    @Volatile private var started = false

    /**
     * 앱 시작 시 1회만 확인한다(부팅/재기동 직후 10초 뒤). 주기 재확인을 하지 않으므로
     * 손님 이용 중(거래 중)에 갑자기 업데이트가 끼어들지 않는다. 새 버전은 다음 앱
     * 재시작 때 반영된다.
     */
    fun start(context: Context) {
        if (BuildConfig.UPDATE_URL.isBlank() || started) return
        started = true
        val app = context.applicationContext
        registerInstallReceiver(app)
        scheduler.schedule(
            { runCatching { checkAndInstall(app) }.onFailure { Log.w(TAG, "업데이트 확인 실패", it) } },
            10, TimeUnit.SECONDS
        )
    }

    /**
     * PackageInstaller 세션 결과 수신. device owner 면 무음 설치되어 STATUS_SUCCESS 가 오고,
     * 아니면 STATUS_PENDING_USER_ACTION 이 와서 시스템 설치 확인창을 띄운다(폴백).
     */
    private fun registerInstallReceiver(app: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(confirm) }
                            .onFailure { Log.w(TAG, "설치 확인창 실행 실패", it) }
                    }
                    PackageInstaller.STATUS_SUCCESS ->
                        Log.i(TAG, "설치 성공 (무음)")
                    else ->
                        Log.w(TAG, "설치 실패 status=$status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                }
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun checkAndInstall(context: Context) {
        val release = JSONObject(apiGet(BuildConfig.UPDATE_URL))
        val assets = release.optJSONArray("assets") ?: return

        // manifest.json 에셋에서 버전 정보를 읽는다
        val manifestUrl = assetApiUrl(assets, "manifest.json") ?: run {
            Log.w(TAG, "릴리스에 manifest.json 에셋이 없다"); return
        }
        val manifest = JSONObject(String(downloadAsset(manifestUrl)))
            .optJSONObject("android") ?: return
        val latest = manifest.optInt("versionCode", 0)
        if (latest <= BuildConfig.VERSION_CODE) return

        val apkName = manifest.optString("apk").ifBlank { return }
        val apkUrl = assetApiUrl(assets, apkName) ?: run {
            Log.w(TAG, "릴리스에 APK 에셋($apkName)이 없다"); return
        }
        Log.i(TAG, "새 버전 ${manifest.optString("versionName")} (code $latest) 다운로드")
        val apk = downloadAsset(apkUrl)

        val expected = manifest.optString("sha256")
        if (expected.isNotBlank() && !sha256Hex(apk).equals(expected, ignoreCase = true)) {
            Log.w(TAG, "sha256 불일치 — 설치를 취소한다"); return
        }
        installApk(context, apk, latest)
    }

    /** 릴리스 assets 배열에서 이름이 일치하는 에셋의 API 다운로드 URL 을 찾는다 */
    private fun assetApiUrl(assets: JSONArray, name: String): String? {
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            if (a.optString("name") == name) return a.optString("url").ifBlank { null }
        }
        return null
    }

    private fun installApk(context: Context, apkBytes: ByteArray, versionCode: Int) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("kiosk-$versionCode.apk", 0, apkBytes.size.toLong()).use { out ->
                out.write(apkBytes)
                session.fsync(out)
            }
            val intent = Intent(INSTALL_ACTION).setPackage(context.packageName)
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_MUTABLE
            val pending = android.app.PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "설치 커밋 완료 (session $sessionId) — 시스템이 설치를 진행한다")
    }

    /** GitHub API JSON 조회 (토큰이 있으면 인증). 리다이렉트 없음 */
    private fun apiGet(url: String): String = openApiConn(url).run {
        try {
            setRequestProperty("Accept", "application/vnd.github+json")
            inputStream.bufferedReader().use { it.readText() }
        } finally {
            disconnect()
        }
    }

    /**
     * 릴리스 에셋(바이너리) 다운로드.
     * 비공개 저장소에서는 Accept: application/octet-stream + 토큰으로 요청하면
     * 서명된 스토리지(S3)로 302 리다이렉트된다. 이때 Authorization 을 다시 보내면
     * 거부되므로 리다이렉트는 인증 헤더 없이 직접 따라간다.
     */
    private fun downloadAsset(assetApiUrl: String): ByteArray {
        val conn = openApiConn(assetApiUrl).apply {
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/octet-stream")
        }
        return try {
            if (conn.responseCode in 300..399) {
                val loc = conn.getHeaderField("Location") ?: error("리다이렉트 Location 없음")
                (URL(loc).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 120000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "kiosk-updater")
                }.run { try { inputStream.readBytes() } finally { disconnect() } }
            } else {
                conn.inputStream.readBytes()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openApiConn(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 120000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "kiosk-updater")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            BuildConfig.UPDATE_TOKEN.takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
