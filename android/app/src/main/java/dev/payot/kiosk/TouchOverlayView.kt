package dev.payot.kiosk

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * 터치 피드백 오버레이 — 이용자가 "내가 누른 곳이 여기"임을 바로 알 수 있게 한다.
 * 상시 동작하며 별도 설정이 없다.
 *
 * WebView 위에 얹지만 터치를 가로채지 않는다. 좌표는 MainActivity.dispatchTouchEvent 가
 * 창 좌표계 그대로 넘겨주므로, 웹 콘텐츠에 CSS 회전이 걸려 있어도 실제 손가락 위치에 찍힌다.
 *
 * 배경색을 가리지 않게 채움은 옅게 두고, 밝은 배경에서도 보이도록 테두리를 함께 그린다.
 */
class TouchOverlayView(context: Context) : View(context) {

    companion object {
        private const val DURATION_MS = 350f
        private const val MAX_RADIUS_DP = 30f
        // 멀티터치가 겹쳐도 리스트가 커지지 않도록 상한 (DOWN 만 받으므로 실제로는 손가락 수)
        private const val RIPPLE_LIMIT = 10
    }

    private class Ripple(val x: Float, val y: Float, val startMs: Long)

    private val ripples = ArrayList<Ripple>()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val maxRadiusPx = MAX_RADIUS_DP * resources.displayMetrics.density

    init {
        // 터치를 먹지 않는다 — 이벤트는 전부 아래 WebView 로 내려간다.
        isClickable = false
        isFocusable = false
    }

    /** 손가락이 닿은 지점 추가. */
    fun addTouch(x: Float, y: Float) {
        if (ripples.size >= RIPPLE_LIMIT) ripples.removeAt(0)
        ripples.add(Ripple(x, y, SystemClock.uptimeMillis()))
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val iter = ripples.iterator()
        var animating = false
        while (iter.hasNext()) {
            val r = iter.next()
            val t = (now - r.startMs) / DURATION_MS
            if (t >= 1f) {
                iter.remove()
                continue
            }
            animating = true
            // 퍼지면서 흐려진다.
            val radius = maxRadiusPx * (0.4f + 0.6f * t)
            val fade = 1f - t
            fill.color = Color.argb((70 * fade).toInt(), 255, 255, 255)
            ring.color = Color.argb((200 * fade).toInt(), 255, 255, 255)
            canvas.drawCircle(r.x, r.y, radius, fill)
            canvas.drawCircle(r.x, r.y, radius, ring)
        }
        // 남은 리플이 있는 동안만 다음 프레임을 요청한다(유휴 시 재그리기 없음).
        if (animating) postInvalidateOnAnimation()
    }
}
