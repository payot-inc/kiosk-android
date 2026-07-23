package dev.payot.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 웹(JS)이 등록하는 예약 작업(크론). 지정한 시각에 알람이 울리면 WebView 로
 * kiosk:cron 이벤트를 보낸다. 앱이 죽어 있어도 알람이 앱을 띄운 뒤 전달하고,
 * 재부팅 시에는 BootReceiver 가 저장된 예약을 다시 등록한다.
 *
 * 저장: SharedPreferences("kiosk_cron")
 *   schedules : { id: {id, at, period, data} }   등록된 예약
 *   pending   : [ {id, data, firedAt} ]           울렸지만 아직 웹에 전달 못한 이벤트 큐
 */
object CronScheduler {
    const val ACTION_FIRE = "dev.payot.kiosk.CRON_FIRE"
    const val EXTRA_ID = "cronId"
    private const val PREFS = "kiosk_cron"
    private const val KEY_SCHEDULES = "schedules"
    private const val KEY_PENDING = "pending"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun schedules(ctx: Context) =
        JSONObject(prefs(ctx).getString(KEY_SCHEDULES, "{}") ?: "{}")

    private fun saveSchedules(ctx: Context, obj: JSONObject) =
        prefs(ctx).edit().putString(KEY_SCHEDULES, obj.toString()).apply()

    /**
     * JS: cronSchedule(json). json = {id?, at(epoch ms), repeat?, data?}
     *   repeat: 생략/"none"=일회성, "daily"=매일, 숫자=해당 밀리초 간격 반복
     * 반환: 등록된 id (id 미지정 시 자동 생성)
     */
    fun schedule(ctx: Context, json: JSONObject): String {
        val id = json.optString("id").ifEmpty { UUID.randomUUID().toString() }
        val at = json.optLong("at")
        require(at > 0L) { "cronSchedule: 'at'(epoch ms) 필수" }
        val period = when (val r = json.opt("repeat")) {
            is Number -> r.toLong()
            "daily" -> 86_400_000L
            else -> 0L
        }
        val entry = JSONObject()
            .put("id", id)
            .put("at", at)
            .put("period", period)
            .put("data", json.opt("data") ?: JSONObject.NULL)

        schedules(ctx).also { it.put(id, entry); saveSchedules(ctx, it) }
        arm(ctx, id, at)
        return id
    }

    fun cancel(ctx: Context, id: String) {
        schedules(ctx).also { if (it.has(id)) { it.remove(id); saveSchedules(ctx, it) } }
        alarm(ctx).cancel(pendingIntent(ctx, id))
    }

    fun list(ctx: Context): JSONArray {
        val all = schedules(ctx)
        return JSONArray().apply { for (k in all.keys()) put(all.getJSONObject(k)) }
    }

    /** 부팅 후 알람이 모두 사라지므로 저장된 예약을 다시 등록한다. */
    fun rescheduleAll(ctx: Context) {
        val all = schedules(ctx)
        val now = System.currentTimeMillis()
        var changed = false
        for (k in all.keys().asSequence().toList()) {
            val e = all.getJSONObject(k)
            var at = e.getLong("at")
            val period = e.getLong("period")
            when {
                at > now -> arm(ctx, k, at)
                period > 0L -> {                       // 지나간 반복: 다음 시각으로 당겨 재등록
                    while (at <= now) at += period
                    e.put("at", at); changed = true
                    arm(ctx, k, at)
                }
                else -> {                              // 지나간 일회성: 보류 큐로 옮기고 제거
                    enqueuePending(ctx, e, now); all.remove(k); changed = true
                }
            }
        }
        if (changed) saveSchedules(ctx, all)
    }

    /** 알람 수신 시: 보류 큐 적재 + 반복이면 다음 알람 재등록, 일회성이면 제거. */
    fun onFired(ctx: Context, id: String) {
        val all = schedules(ctx)
        val e = all.optJSONObject(id) ?: return
        enqueuePending(ctx, e, System.currentTimeMillis())
        val period = e.getLong("period")
        if (period > 0L) {
            var next = e.getLong("at") + period
            val now = System.currentTimeMillis()
            while (next <= now) next += period
            e.put("at", next); saveSchedules(ctx, all)
            arm(ctx, id, next)
        } else {
            all.remove(id); saveSchedules(ctx, all)
        }
    }

    private fun enqueuePending(ctx: Context, entry: JSONObject, firedAt: Long) {
        val arr = JSONArray(prefs(ctx).getString(KEY_PENDING, "[]") ?: "[]")
        arr.put(
            JSONObject()
                .put("id", entry.getString("id"))
                .put("data", entry.opt("data") ?: JSONObject.NULL)
                .put("firedAt", firedAt)
        )
        prefs(ctx).edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    /** 보류 이벤트를 모두 꺼내고 큐를 비운다 (웹 전달용). */
    fun takePending(ctx: Context): JSONArray {
        val arr = JSONArray(prefs(ctx).getString(KEY_PENDING, "[]") ?: "[]")
        if (arr.length() > 0) prefs(ctx).edit().putString(KEY_PENDING, "[]").apply()
        return arr
    }

    private fun arm(ctx: Context, id: String, at: Long) =
        alarm(ctx).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(ctx, id))

    private fun alarm(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(ctx: Context, id: String): PendingIntent {
        val intent = Intent(ctx, CronReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
