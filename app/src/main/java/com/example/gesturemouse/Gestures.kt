package com.example.gesturemouse

import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 手勢判斷：從電腦版 gesture_mouse.py 逐一翻譯。
 *
 * 關節點編號和 MediaPipe Hands 完全一樣（0＝手腕、4＝拇指尖、8＝食指尖、12＝中指尖……）。
 * 座標單位是「已轉正的鏡頭畫面像素」，x 已經左右鏡像，所以長寬比不會讓距離失真。
 */
class Lm(@JvmField val x: Float, @JvmField val y: Float)

enum class Gesture { NONE, PINCH, RCLICK, SCROLL, POINT, FIST, THUMBS_UP, C, OTHER }
enum class Pinch { LEFT, RIGHT }
enum class Pose { OPEN, FIST, THUMBS_UP, OTHER }

class Classified(val gesture: Gesture, val pinch: Pinch?)

object Cfg {
    // 捏合（拇指與指尖距離 / 手掌大小）
    const val PINCH_ON = 0.28f
    const val PINCH_OFF = 0.40f

    // C 形手指彎曲角度範圍（度）
    const val C_BEND_MIN = 35f
    const val C_BEND_MAX = 120f
}

fun dist(a: Lm, b: Lm): Float = hypot(a.x - b.x, a.y - b.y)

/** 手指是否伸直：指尖離手腕的距離明顯大於第二關節。 */
fun extended(lm: Array<Lm>, tip: Int, pip: Int): Boolean =
    dist(lm[tip], lm[0]) > dist(lm[pip], lm[0]) * 1.15f

fun handSize(lm: Array<Lm>): Float = max(dist(lm[0], lm[9]), 1e-6f)

/** 比讚：四指收起、拇指朝上伸出。 */
fun isThumbsUp(lm: Array<Lm>): Boolean {
    val hs = handSize(lm)
    val curled = !(extended(lm, 8, 6) || extended(lm, 12, 10) ||
            extended(lm, 16, 14) || extended(lm, 20, 18))
    val up = lm[4].y < lm[3].y && lm[3].y < lm[2].y && lm[4].y < lm[5].y - 0.35f * hs
    val apart = dist(lm[4], lm[8]) > 0.6f * hs  // 拇指遠離食指（不是捏合）
    return curled && up && apart
}

/** L 形：拇指與食指伸直且約成直角，其餘三指收起（取景框的一個角）。 */
fun isLShape(lm: Array<Lm>): Boolean {
    if (extended(lm, 12, 10) || extended(lm, 16, 14) || extended(lm, 20, 18)) return false
    if (!extended(lm, 8, 6)) return false
    val hs = handSize(lm)
    val thumbOut = dist(lm[4], lm[17]) > dist(lm[3], lm[17]) * 1.1f
    if (!thumbOut || dist(lm[4], lm[2]) / hs < 0.4f) return false
    val tx = lm[4].x - lm[2].x
    val ty = lm[4].y - lm[2].y
    val ix = lm[8].x - lm[5].x
    val iy = lm[8].y - lm[5].y
    val n = hypot(tx, ty) * hypot(ix, iy)
    if (n < 1e-9f) return false
    val cos = (tx * ix + ty * iy) / n
    return cos > -0.4f && cos < 0.5f  // 夾角約 60°~115°
}

/** L 形的轉角（拇指與食指交會處）。 */
fun frameCorner(lm: Array<Lm>): Pair<Float, Float> =
    Pair((lm[2].x + lm[5].x) / 2f, (lm[2].y + lm[5].y) / 2f)

/** 一根手指的彎曲角度：近端指節（a→b）與遠端指節（c→d）方向的夾角，直的約 0°。 */
fun bendDeg(lm: Array<Lm>, a: Int, b: Int, c: Int, d: Int): Float {
    val v1x = lm[b].x - lm[a].x
    val v1y = lm[b].y - lm[a].y
    val v2x = lm[d].x - lm[c].x
    val v2y = lm[d].y - lm[c].y
    val n = hypot(v1x, v1y) * hypot(v2x, v2y)
    if (n < 1e-9f) return 0f
    val cos = max(-1f, min(1f, (v1x * v2x + v1y * v2y) / n))
    return Math.toDegrees(acos(cos).toDouble()).toFloat()
}

/** C 形：至少三指適度彎曲，拇指向外，拇指與食指尖之間留有開口。 */
fun isCShape(lm: Array<Lm>): Boolean {
    val hs = handSize(lm)
    val fingers = arrayOf(
        intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12),
        intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20),
    )
    val bent = fingers.count {
        bendDeg(lm, it[0], it[1], it[2], it[3]) in Cfg.C_BEND_MIN..Cfg.C_BEND_MAX
    }
    if (bent < 3) return false
    val gap = dist(lm[4], lm[8]) / hs
    if (gap < 0.45f || gap > 1.4f) return false
    return dist(lm[4], lm[17]) > dist(lm[3], lm[17])
}

/** 單手姿勢，用於雙手手勢。 */
fun handPose(lm: Array<Lm>): Pose {
    if (isThumbsUp(lm)) return Pose.THUMBS_UP  // 比讚不算握拳
    val fingers = booleanArrayOf(
        extended(lm, 8, 6), extended(lm, 12, 10), extended(lm, 16, 14), extended(lm, 20, 18),
    )
    val thumb = dist(lm[4], lm[17]) > dist(lm[3], lm[17]) * 1.1f
    if (fingers.all { it } && thumb) return Pose.OPEN
    if (fingers.none { it }) return Pose.FIST
    return Pose.OTHER
}

/** 單手手勢分類（捏合用遲滯判斷，避免在邊界抖動）。 */
fun classify(lm: Array<Lm>, pinchIn: Pinch?): Classified {
    val hs = handSize(lm)
    val rIdx = dist(lm[4], lm[8]) / hs   // 拇指—食指
    val rMid = dist(lm[4], lm[12]) / hs  // 拇指—中指

    var pinch = pinchIn
    if (pinch == Pinch.LEFT && rIdx > Cfg.PINCH_OFF) pinch = null
    else if (pinch == Pinch.RIGHT && rMid > Cfg.PINCH_OFF) pinch = null
    if (pinch == null) {
        if (rIdx < Cfg.PINCH_ON && rIdx <= rMid) pinch = Pinch.LEFT
        else if (rMid < Cfg.PINCH_ON) pinch = Pinch.RIGHT
    }
    if (pinch == Pinch.LEFT) return Classified(Gesture.PINCH, pinch)
    if (pinch == Pinch.RIGHT) return Classified(Gesture.RCLICK, pinch)

    if (isThumbsUp(lm)) return Classified(Gesture.THUMBS_UP, null)
    if (isCShape(lm)) return Classified(Gesture.C, null)  // 放在指向 / 捲動之前，比 C 時游標才不會亂動

    val idx = extended(lm, 8, 6)
    val mid = extended(lm, 12, 10)
    val ring = extended(lm, 16, 14)
    val pinky = extended(lm, 20, 18)
    if (idx && mid && !ring && !pinky) return Classified(Gesture.SCROLL, null)
    if (idx && !(mid || ring || pinky)) return Classified(Gesture.POINT, null)
    if (!(idx || mid || ring || pinky)) return Classified(Gesture.FIST, null)
    return Classified(Gesture.OTHER, null)
}
