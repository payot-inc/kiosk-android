package dev.payot.kiosk

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 원격 진단용 파일 로그 — 앱의 주요 이벤트(시작/크래시/업데이트/크론)를 filesDir/logs/
 * 에 하루 한 파일(kiosk-yyyy-MM-dd.log)로 남긴다. 매일 자동 회전하며 RETENTION_DAYS 일이
 * 지난 파일은 삭제한다(기본 15일). 폭주(크래시 루프 등)로 하루치가 커지는 것을 막기 위해
 * 파일당 MAX_BYTES_PER_DAY 를 넘으면 앞쪽(오래된) 절반을 잘라낸다.
 * 안드로이드는 HTTP 서버가 없으므로 웹은 window.android.getLogs() 브리지로 최근 로그를
 * 받아 화면 표시/서버 업로드에 쓴다. (윈도우는 사이드카 GET /logs 가 같은 역할)
 */
object RemoteLog {
    private const val RETENTION_DAYS = 15
    private const val MAX_BYTES_PER_DAY = 1024 * 1024L // 하루치 상한(폭주 방지)
    private const val PREFIX = "kiosk-"
    private const val SUFFIX = ".log"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val lock = Any()

    @Volatile private var dir: File? = null
    private var file: File? = null
    private var currentDay: String? = null

    /** Application.onCreate 에서 1회 호출 */
    fun init(context: Context) {
        if (dir != null) return
        runCatching {
            dir = File(context.filesDir, "logs").apply { mkdirs() }
        }
        synchronized(lock) { rollIfNeeded() }
    }

    fun i(message: String) = write("INFO", message)
    fun w(message: String) = write("WARN", message)
    fun e(message: String) = write("ERROR", message)

    private fun write(level: String, message: String) {
        if (dir == null) return
        synchronized(lock) {
            runCatching {
                rollIfNeeded()
                val f = file ?: return
                f.appendText("${tsFmt.format(Date())} [$level] $message\n")
                if (f.length() > MAX_BYTES_PER_DAY) trimFront(f)
            }
        }
    }

    /** 날짜가 바뀌었으면 오늘 파일로 교체하고 오래된 파일을 정리한다. (lock 안에서 호출) */
    private fun rollIfNeeded() {
        val d = dir ?: return
        val today = dayFmt.format(Date())
        if (today == currentDay && file != null) return
        currentDay = today
        file = File(d, "$PREFIX$today$SUFFIX")
        cleanup(d)
    }

    /** RETENTION_DAYS 이전 로그 파일 삭제 (파일명 날짜 기준) + 구버전(크기회전) 잔여 파일 정리 */
    private fun cleanup(d: File) {
        for (legacy in listOf("kiosk.log", "kiosk.log.1")) {
            runCatching { File(d, legacy).takeIf { it.exists() }?.delete() }
        }
        val cutoff = Date().time - RETENTION_DAYS * DAY_MS
        d.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.forEach { f ->
                val day = f.name.removePrefix(PREFIX).removeSuffix(SUFFIX)
                val t = runCatching { dayFmt.parse(day)?.time }.getOrNull()
                if (t != null && t < cutoff) runCatching { f.delete() }
            }
    }

    /** 하루치 파일이 상한을 넘으면 앞(오래된) 절반을 버려 최근 절반만 남긴다. */
    private fun trimFront(f: File) {
        runCatching {
            val lines = f.readLines()
            if (lines.size < 2) return
            val kept = lines.subList(lines.size / 2, lines.size)
            f.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * 최근 maxLines 줄 (오래된 → 최신 순으로 이어서). 최신 파일부터 역순으로 필요한 만큼만
     * 읽어 15일치 전체를 메모리에 올리지 않는다.
     */
    fun tail(maxLines: Int): String {
        val d = dir ?: return ""
        val files = d.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name } // kiosk-yyyy-MM-dd.log 는 이름 정렬 = 날짜 정렬
            ?: return ""
        val collected = ArrayDeque<String>()
        for (f in files) {
            val fileLines = runCatching { f.readLines() }.getOrNull() ?: continue
            for (line in fileLines.asReversed()) {
                collected.addFirst(line)
                if (maxLines > 0 && collected.size >= maxLines) return collected.joinToString("\n")
            }
        }
        return collected.joinToString("\n")
    }
}
