package com.example.gesturemouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 無障礙服務：Android 上唯一合法的「替使用者點螢幕」管道。
 *  - dispatchGesture() 負責點擊、長按、拖曳、捲動（對應電腦版的 pyautogui）
 *  - TYPE_ACCESSIBILITY_OVERLAY 畫游標（對應電腦版的 Overlay）
 *  - performGlobalAction() 負責返回、桌面、截圖
 *
 * GestureOutput 的方法會從相機的分析執行緒呼叫，所以內部一律 post 到主執行緒。
 */
class GestureAccessibilityService : AccessibilityService(), GestureOutput {

    companion object {
        private const val TAG = "GestureMouse"
        @Volatile var instance: GestureAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var cursorView: CursorView? = null

    // ---- 拖曳狀態（只在主執行緒存取）----
    private var dragActive = false
    private var dragEnding = false
    private var dragInFlight = false
    private var dragStroke: StrokeDescription? = null
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var dragPendX = 0f
    private var dragPendY = 0f
    private var dragHasPending = false

    private var swipeInFlight = false

    // ================= 生命週期 =================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val view = CursorView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or  // 用 GPU 繪製游標層
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or  // 觸控穿透：不會擋到底下的 App
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            manager.addView(view, lp)
            cursorView = view
        } catch (e: Exception) {
            Log.e(TAG, "無法加入游標層", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        instance = null
        resetDrag()
        cursorView?.let { v ->
            try { wm?.removeView(v) } catch (_: Exception) {}
        }
        cursorView = null
    }

    fun screenSize(): Point {
        val manager = wm ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = manager.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(p)
            p
        }
    }

    // ================= GestureOutput =================

    override fun cursor(x: Float, y: Float, visible: Boolean, pressed: Boolean) {
        main.post { cursorView?.setTarget(x, y, visible, pressed) }
    }

    override fun snapCursor(x: Float, y: Float) {
        main.post { cursorView?.snap(x, y) }
    }

    override fun tap(x: Float, y: Float) {
        main.post { stroke(x, y, 50) }
    }

    override fun longPress(x: Float, y: Float) {
        main.post { stroke(x, y, ViewConfiguration.getLongPressTimeout() + 150L) }
    }

    private fun stroke(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(StrokeDescription(path, 0, durationMs)).build()
        dispatchGesture(g, null, null)
    }

    override fun swipeVertical(dyPx: Float) {
        main.post {
            if (swipeInFlight) return@post
            val s = screenSize()
            val x = s.x / 2f
            val y0 = s.y * 0.6f
            val y1 = (y0 + dyPx).coerceIn(s.y * 0.12f, s.y * 0.92f)
            if (kotlin.math.abs(y1 - y0) < 8f) return@post
            val path = Path().apply { moveTo(x, y0); lineTo(x, y1) }
            val g = GestureDescription.Builder().addStroke(StrokeDescription(path, 0, 140)).build()
            swipeInFlight = true
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { swipeInFlight = false }
                override fun onCancelled(gestureDescription: GestureDescription?) { swipeInFlight = false }
            }, null)
            if (!ok) swipeInFlight = false
        }
    }

    // ---- 拖曳：用 continued gesture（API 26+）把一連串短線段接成一次不放手的觸控 ----

    override fun dragStart(x: Float, y: Float) {
        main.post {
            resetDrag()
            dragActive = true
            dragLastX = x
            dragLastY = y
            sendDragSegment(x, y, false)  // 先按下去
        }
    }

    override fun dragMove(x: Float, y: Float) {
        main.post {
            if (!dragActive || dragEnding) return@post
            dragPendX = x; dragPendY = y; dragHasPending = true
            if (!dragInFlight) pumpDrag()
        }
    }

    override fun dragEnd(x: Float, y: Float) {
        main.post {
            if (!dragActive) return@post
            dragEnding = true
            dragPendX = x; dragPendY = y; dragHasPending = true
            if (!dragInFlight) pumpDrag()
        }
    }

    private fun pumpDrag() {
        if (!dragActive || dragInFlight || !dragHasPending) return
        dragHasPending = false
        sendDragSegment(dragPendX, dragPendY, dragEnding)
    }

    private fun sendDragSegment(x: Float, y: Float, end: Boolean) {
        // 線段長度不能是 0；而且下一段必須從上一段「實際的終點」開始
        val ex = if (x == dragLastX && y == dragLastY) x + 0.1f else x
        val path = Path().apply { moveTo(dragLastX, dragLastY); lineTo(ex, y) }
        val prev = dragStroke
        val stroke = try {
            if (prev == null) StrokeDescription(path, 0, 40, !end)
            else prev.continueStroke(path, 0, 40, !end)
        } catch (e: Exception) {
            Log.e(TAG, "拖曳線段建立失敗", e)
            resetDrag()
            return
        }
        dragStroke = stroke
        dragLastX = ex
        dragLastY = y
        dragInFlight = true
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    dragInFlight = false
                    if (end) resetDrag() else pumpDrag()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    resetDrag()
                }
            },
            null,
        )
        if (!ok) resetDrag()
    }

    private fun resetDrag() {
        dragActive = false
        dragEnding = false
        dragInFlight = false
        dragStroke = null
        dragHasPending = false
    }

    // ---- 全域動作 ----

    override fun home() {
        main.post { performGlobalAction(GLOBAL_ACTION_HOME) }
    }

    override fun back() {
        main.post { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    override fun screenshot() {
        main.post {
            if (Build.VERSION.SDK_INT >= 28) {
                // 先把游標藏起來，免得拍進畫面
                cursorView?.setTarget(0f, 0f, false, false)
                main.postDelayed({ performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) }, 250)
            } else {
                Toast.makeText(this, "截圖需要 Android 9 以上", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun openChrome() {
        main.post {
            val url = Uri.parse("https://www.google.com")
            val intent = Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(Intent(intent).setPackage("com.android.chrome"))
            } catch (_: ActivityNotFoundException) {
                try { startActivity(intent) } catch (e: Exception) { Log.e(TAG, "開啟瀏覽器失敗", e) }
            } catch (e: Exception) {
                Log.e(TAG, "開啟 Chrome 失敗", e)
            }
        }
    }

    override fun hud(armed: Boolean, text: String) {
        main.post { cursorView?.showHud(armed, text) }
    }

    /** 左上角鏡頭預覽（從分析執行緒呼叫）。bmp 為 null＝關閉預覽。done 在主執行緒畫完後呼叫。 */
    fun preview(bmp: Bitmap?, hands: List<Array<Lm>>, w: Float, h: Float, info: String, done: () -> Unit) {
        main.post {
            cursorView?.setPreview(bmp, hands, w, h, info)
            done()
        }
    }
}
