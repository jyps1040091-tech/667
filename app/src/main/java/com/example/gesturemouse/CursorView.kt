package com.example.gesturemouse

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.exp

/**
 * 全螢幕透明、觸控可穿透的游標層（電腦版 Overlay 的對應）。
 * 辨識執行緒只更新「目標位置」，實際顯示位置由 Choreographer 每個螢幕幀向目標插值，
 * 所以游標的流暢度跟螢幕更新率一致，不受鏡頭幀率限制。
 *
 * 效能：只重繪「游標 / HUD / 預覽」所在的小區域（局部 invalidate），
 * 不再每幀重繪整個全螢幕（電視晶片弱，全螢幕透明層很吃力）。
 * 左上角有鏡頭預覽（含手部關節點、有效操作範圍框、fps）。
 * 所有方法都必須在主執行緒呼叫。
 */
class CursorView(context: Context) : View(context) {

    private val tau = 0.025f  // 插值時間常數（秒），對應電腦版 CURSOR_SMOOTH_TAU

    private var tx = 0f
    private var ty = 0f
    private var px = 0f
    private var py = 0f
    private var visibleCursor = false
    private var pressed = false
    private var hasPos = false

    private var armed = false
    private var hudText = ""
    private var hudUntil = 0L
    private var hudWasOn = false

    private var running = false
    private var lastNs = 0L
    private val loc = IntArray(2)
    private var ox = 0f
    private var oy = 0f

    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * dp
        color = Color.BLACK
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202124.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * dp
        textAlign = Paint.Align.CENTER
    }

    // ---- 鏡頭預覽 ----
    private var pBmp: Bitmap? = null
    private var pHands: List<Array<Lm>> = emptyList()
    private var pW = 4f
    private var pH = 3f
    private var pInfo = ""
    private val pRect = RectF()    // 影像本體
    private val pOuter = RectF()   // 含邊框與文字列（重繪範圍）
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pBg = Paint().apply { color = 0xCC000000.toInt() }
    private val pBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        color = 0xFFFFFFFF.toInt()
    }
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * dp
        color = 0xFF34C759.toInt()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    private val anchorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD60A.toInt() }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * dp
        textAlign = Paint.Align.LEFT
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dt = if (lastNs == 0L) 0.016f else ((frameTimeNanos - lastNs) / 1e9f).coerceIn(0.001f, 0.1f)
            lastNs = frameTimeNanos
            val a = 1f - exp(-dt / tau)
            invalidateCursorAt(px, py)
            px += (tx - px) * a
            py += (ty - py) * a
            invalidateCursorAt(px, py)

            val hudOn = SystemClock.uptimeMillis() < hudUntil
            if (hudOn || hudWasOn) invalidateHud()
            hudWasOn = hudOn

            val moving = abs(tx - px) > 0.5f || abs(ty - py) > 0.5f
            if (visibleCursor || hudOn || moving) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                lastNs = 0L
            }
        }
    }

    private fun kick() {
        if (!running) {
            running = true
            lastNs = 0L
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    // ================= 局部重繪輔助 =================

    private fun invalidateArea(l: Float, t: Float, r: Float, b: Float) {
        invalidate(l.toInt() - 1, t.toInt() - 1, r.toInt() + 2, b.toInt() + 2)
    }

    private fun invalidateCursorAt(x: Float, y: Float) {
        val m = 24f * dp
        invalidateArea(x - ox - m, y - oy - m, x - ox + m, y - oy + m)
    }

    private fun hudRect(): RectF {
        val w = text.measureText(hudText) + 40f * dp
        val h = 36f * dp
        return RectF(width / 2f - w / 2, 60f * dp, width / 2f + w / 2, 60f * dp + h)
    }

    private fun invalidateHud() {
        if (hudText.isEmpty()) return
        val r = hudRect()
        invalidateArea(r.left, r.top, r.right, r.bottom)
    }

    private fun invalidateDot() {
        invalidateArea(width - 24f * dp, 24f * dp, width.toFloat(), 44f * dp)
    }

    // ================= 對外介面 =================

    fun setTarget(x: Float, y: Float, visible: Boolean, isPressed: Boolean) {
        tx = x; ty = y
        pressed = isPressed
        if (visible && (!visibleCursor || !hasPos)) {  // 剛出現：直接出現在目標，不要從舊位置飛過去
            invalidateCursorAt(px, py)
            px = x; py = y; hasPos = true
        }
        if (!visible) hasPos = false
        visibleCursor = visible
        kick()
    }

    /** 立刻跳到目標位置（點擊前呼叫，確保點在游標指的地方）。 */
    fun snap(x: Float, y: Float) {
        invalidateCursorAt(px, py)
        tx = x; ty = y; px = x; py = y
        invalidateCursorAt(px, py)
    }

    fun showHud(isArmed: Boolean, message: String) {
        invalidateHud()  // 先擦掉舊的
        armed = isArmed
        hudText = message
        hudUntil = SystemClock.uptimeMillis() + 1200
        invalidateHud()
        invalidateDot()
        kick()
    }

    /** 更新左上角鏡頭預覽。bmp 為 null 代表關閉預覽。hands 的座標是「已鏡像的畫面像素」。 */
    fun setPreview(bmp: Bitmap?, hands: List<Array<Lm>>, w: Float, h: Float, info: String) {
        val oldOuter = RectF(pOuter)
        pBmp = bmp
        pHands = hands
        pInfo = info
        if (bmp != null) {
            pW = w; pH = h
            val left = 28f * dp
            val top = 28f * dp
            val pw = 176f * dp
            pRect.set(left, top, left + pw, top + pw * h / w)
            pOuter.set(left - 4f * dp, top - 4f * dp, left + pw + 4f * dp, top + pw * h / w + 26f * dp)
        }
        invalidateArea(oldOuter.left, oldOuter.top, oldOuter.right, oldOuter.bottom)
        invalidateArea(pOuter.left, pOuter.top, pOuter.right, pOuter.bottom)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frame)
        running = false
        super.onDetachedFromWindow()
    }

    // ================= 繪製 =================

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)  // 補償狀態列 / 瀏海造成的位移，讓座標等於螢幕絕對座標
        ox = loc[0].toFloat()
        oy = loc[1].toFloat()

        // 右上角小圓點：啟動中＝綠，停用＝灰
        fill.color = if (armed) 0xFF34C759.toInt() else 0x88999999.toInt()
        canvas.drawCircle(width - 14f * dp, 14f * dp + 20f * dp, 5f * dp, fill)

        pBmp?.let { drawPreview(canvas, it) }

        if (hudText.isNotEmpty() && SystemClock.uptimeMillis() < hudUntil) {
            val r = hudRect()
            val h = r.height()
            canvas.drawRoundRect(r, h / 2, h / 2, pill)
            canvas.drawText(hudText, r.centerX(), r.centerY() + 6f * dp, text)
        }

        if (visibleCursor) {
            val x = px - ox
            val y = py - oy
            val rad = (if (pressed) 11f else 17f) * dp
            fill.color = if (pressed) 0xFF0A84FF.toInt() else Color.WHITE
            canvas.drawCircle(x, y, rad, fill)
            canvas.drawCircle(x, y, rad, stroke)
        }
    }

    private fun drawPreview(canvas: Canvas, b: Bitmap) {
        val r = pRect
        canvas.drawRect(pOuter, pBg)

        // 鏡頭畫面沒有鏡像，這裡畫成鏡像（跟關節點座標一致，手往右移畫面也往右）
        canvas.save()
        canvas.scale(-1f, 1f, r.centerX(), r.centerY())
        canvas.drawBitmap(b, null, r, bmpPaint)
        canvas.restore()

        // 有效操作範圍：手要在綠框內移動，才能掃過整個螢幕
        canvas.drawRect(
            r.left + GestureEngine.MARGIN_X * r.width(),
            r.top + GestureEngine.MARGIN_TOP * r.height(),
            r.right - GestureEngine.MARGIN_X * r.width(),
            r.bottom - GestureEngine.MARGIN_BOTTOM * r.height(),
            zonePaint,
        )

        // 手部關節點（黃色大點＝控制游標的 5 號關節）
        val sx = r.width() / pW
        val sy = r.height() / pH
        for (hand in pHands) {
            for (i in hand.indices) {
                val cx = r.left + hand[i].x * sx
                val cy = r.top + hand[i].y * sy
                if (i == 5) canvas.drawCircle(cx, cy, 4f * dp, anchorPaint)
                else canvas.drawCircle(cx, cy, 2f * dp, dotPaint)
            }
        }

        canvas.drawRect(r, pBorder)
        canvas.drawText(pInfo, r.left, r.bottom + 19f * dp, infoPaint)
    }
}
