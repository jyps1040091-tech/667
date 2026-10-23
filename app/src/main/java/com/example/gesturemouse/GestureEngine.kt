package com.example.gesturemouse

import java.util.concurrent.atomic.AtomicBoolean

/** 引擎對外的輸出介面，由無障礙服務實作（點擊、拖曳、捲動、全域動作、游標）。 */
interface GestureOutput {
    fun cursor(x: Float, y: Float, visible: Boolean, pressed: Boolean)
    fun snapCursor(x: Float, y: Float)
    fun tap(x: Float, y: Float)
    fun longPress(x: Float, y: Float)
    fun dragStart(x: Float, y: Float)
    fun dragMove(x: Float, y: Float)
    fun dragEnd(x: Float, y: Float)
    /** 在螢幕中央垂直滑動 dyPx 像素（手往上移＝負值＝內容往下捲到後面的內容）。 */
    fun swipeVertical(dyPx: Float)
    fun home()
    fun back()
    fun screenshot()
    fun openChrome()
    fun hud(armed: Boolean, text: String)
}

/**
 * 手勢引擎：電腦版 main() 迴圈的 Android 版。
 * 每個鏡頭畫面呼叫一次 [process]；本身不碰任何 Android API，方便單元測試。
 */
class GestureEngine(private val sw: Int, private val sh: Int, private val out: GestureOutput) {

    companion object {
        // ===== 可調整設定（和電腦版同名）=====
        const val MARGIN_X = 0.20f
        const val MARGIN_TOP = 0.15f
        const val MARGIN_BOTTOM = 0.30f

        const val DRAG_HOLD = 0.35
        const val MIN_CLICK = 0.04
        const val RIGHT_CLICK_COOLDOWN = 0.5
        const val TOGGLE_HOLD = 1.0       // 雙手攤開：啟動 / 停用
        const val HOME_HOLD = 2.0         // 雙手握拳：回桌面
        const val BACK_HOLD = 0.6         // 比讚：返回
        const val BACK_COOLDOWN = 1.2
        const val C_HOLD = 0.8            // C 形：開 Chrome
        const val C_COOLDOWN = 5.0
        const val SHOT_HOLD = 1.0         // 雙手 L 形取景框：截圖
        const val SHOT_COOLDOWN = 3.0
        const val LOST_TIMEOUT = 0.5

        // 手機上每次捲動是一次「滑動」，門檻比電腦版大很多，不然會一直觸發
        const val SCROLL_DEADZONE = 0.05f
        const val SCROLL_GAIN = 2.0f
    }

    @Volatile var armed = false
        private set

    private val toggleReq = AtomicBoolean(false)
    fun requestToggle() = toggleReq.set(true)

    private val fx = OneEuro()
    private val fy = OneEuro()

    private var pinchState: Pinch? = null
    private var prevGesture = Gesture.NONE
    private var lastSeen = 0.0
    private var lastRclick = -100.0

    // 左鍵 / 拖曳
    private var pinchStart: Double? = null
    private var dragging = false

    // 游標（pinch 期間凍結在捏下去的位置）
    private var tgtX = 0f
    private var tgtY = 0f
    private var cursorVisible = false

    // 捲動
    private var scrollPrevY: Float? = null
    private var scrollAcc = 0f

    // 雙手手勢
    private var twoKind: Pose? = null
    private var twoSince = 0.0
    private var twoFired = false

    private var cSince: Double? = null
    private var cFired = false
    private var lastChrome = -100.0

    private var thumbSince: Double? = null
    private var lastBack = -100.0

    private var shotSince: Double? = null
    private var shotFired = false
    private var lastShot = -100.0

    private fun setArmed(v: Boolean, now: Double) {
        armed = v
        releaseAll()
        out.hud(v, if (v) "已啟動" else "已停用")
    }

    /** 服務結束時呼叫：放開拖曳、隱藏游標。 */
    fun release() = releaseAll()

    private fun releaseAll() {
        if (dragging) out.dragEnd(tgtX, tgtY)
        pinchStart = null
        dragging = false
        pinchState = null
        scrollPrevY = null
        scrollAcc = 0f
        if (cursorVisible) {
            cursorVisible = false
            out.cursor(tgtX, tgtY, false, false)
        }
    }

    /** 沒偵測到手的畫面也要呼叫（hands 為空），才能計時放開按鍵。 */
    fun process(hands: List<Array<Lm>>, frameW: Float, frameH: Float, now: Double) {
        var gesture = Gesture.NONE
        var lm: Array<Lm>? = null
        if (hands.size == 1) {
            lm = hands[0]
            lastSeen = now
            val c = classify(lm, pinchState)
            gesture = c.gesture
            pinchState = c.pinch
        }

        // ---- 雙手：攤開 / 握拳 ----
        var kind: Pose? = null
        if (hands.size == 2) {
            val p0 = handPose(hands[0])
            val p1 = handPose(hands[1])
            if (p0 == p1 && (p0 == Pose.OPEN || p0 == Pose.FIST)) kind = p0
        }
        if (kind == Pose.FIST && !armed) kind = null  // 停用時握拳不動作

        // ---- 全域切換（通知列按鈕）----
        if (toggleReq.getAndSet(false)) setArmed(!armed, now)

        // ---- 比讚 → 返回 ----
        if (gesture == Gesture.THUMBS_UP && armed) {
            val s = thumbSince ?: now.also { thumbSince = it }
            if (now - s >= BACK_HOLD && now - lastBack >= BACK_COOLDOWN) {
                lastBack = now
                out.back()
                out.hud(true, "返回")
            }
        } else thumbSince = null

        // ---- C 形 → 開 Chrome ----
        if (gesture == Gesture.C && armed) {
            val s = cSince ?: now.also { cSince = it }
            if (now - s >= C_HOLD && !cFired && now - lastChrome >= C_COOLDOWN) {
                cFired = true
                lastChrome = now
                releaseAll()
                out.openChrome()
                out.hud(true, "開啟 Chrome")
            }
        } else {
            cSince = null; cFired = false
        }

        // ---- 雙手 L 形取景框 → 截圖 ----
        val bothL = hands.size == 2 && hands.all { isLShape(it) }
        if (bothL && armed) {
            val s = shotSince ?: now.also { shotSince = it }
            if (now - s >= SHOT_HOLD && !shotFired && now - lastShot >= SHOT_COOLDOWN) {
                shotFired = true
                lastShot = now
                releaseAll()
                out.screenshot()
                out.hud(true, "截圖")
            }
        } else {
            shotSince = null; shotFired = false
        }

        // ---- 雙手手勢計時 ----
        if (kind != twoKind) {
            twoKind = kind
            twoFired = false
            twoSince = now
        } else if (kind != null && !twoFired) {
            val hold = if (kind == Pose.OPEN) TOGGLE_HOLD else HOME_HOLD
            if (now - twoSince >= hold) {
                twoFired = true
                if (kind == Pose.OPEN) {
                    setArmed(!armed, now)
                } else {
                    releaseAll()
                    out.home()
                    out.hud(true, "桌面")
                }
            }
        }

        // ---- 操作邏輯 ----
        if (!armed || (gesture == Gesture.NONE && now - lastSeen > LOST_TIMEOUT)) {
            releaseAll()
        } else if (gesture != Gesture.NONE && lm != null) {
            // 手 → 螢幕座標（取食指根部關節 5 號，捏合時最穩定）
            val nx = lm[5].x / frameW
            val ny = lm[5].y / frameH
            val px = ((nx - MARGIN_X) / (1 - 2 * MARGIN_X)).coerceIn(0f, 1f)
            val py = ((ny - MARGIN_TOP) / (1 - MARGIN_TOP - MARGIN_BOTTOM)).coerceIn(0f, 1f)
            val curX = fx.filter((px * (sw - 1)).toDouble(), now).toFloat()
            val curY = fy.filter((py * (sh - 1)).toDouble(), now).toFloat()

            // 游標移動：食指伸出，或拖曳中
            if (gesture == Gesture.POINT || (gesture == Gesture.PINCH && dragging)) {
                tgtX = curX; tgtY = curY
                cursorVisible = true
                if (dragging) out.dragMove(tgtX, tgtY)
            }
            out.cursor(tgtX, tgtY, cursorVisible, gesture == Gesture.PINCH)

            // 左鍵：快速捏合＝點擊，捏住不放＝拖曳
            if (gesture == Gesture.PINCH) {
                val s = pinchStart
                if (s == null) {
                    pinchStart = now
                } else if (!dragging && now - s >= DRAG_HOLD) {
                    out.snapCursor(tgtX, tgtY)
                    out.dragStart(tgtX, tgtY)
                    dragging = true
                    out.hud(true, "拖曳")
                }
            } else if (pinchStart != null) {
                val s = pinchStart!!
                if (dragging) {
                    out.dragEnd(tgtX, tgtY)
                    out.hud(true, "放開")
                } else if (now - s >= MIN_CLICK) {
                    out.snapCursor(tgtX, tgtY)  // 游標在捏合期間是凍結的，點在捏下去的位置
                    out.tap(tgtX, tgtY)
                    out.hud(true, "點擊")
                }
                pinchStart = null
                dragging = false
            }

            // 右鍵（Android 對應長按）：拇指＋中指，捏下去的瞬間觸發一次
            if (gesture == Gesture.RCLICK && prevGesture != Gesture.RCLICK &&
                now - lastRclick > RIGHT_CLICK_COOLDOWN
            ) {
                lastRclick = now
                out.snapCursor(tgtX, tgtY)
                out.longPress(tgtX, tgtY)
                out.hud(true, "長按")
            }

            // 捲動：比 YA 上下移動
            if (gesture == Gesture.SCROLL) {
                val y = lm[9].y / frameH
                val prev = scrollPrevY
                if (prev != null) {
                    scrollAcc += prev - y  // 手往上 → 正值
                    if (kotlin.math.abs(scrollAcc) >= SCROLL_DEADZONE) {
                        // 手往上＝手指往上滑（觸控的自然方向）；要反向就把 SCROLL_GAIN 改負值
                        out.swipeVertical(-scrollAcc * sh * SCROLL_GAIN)
                        scrollAcc = 0f
                    }
                }
                scrollPrevY = y
            } else {
                scrollPrevY = null
                scrollAcc = 0f
            }
        }

        if (gesture != Gesture.NONE) prevGesture = gesture
    }
}
