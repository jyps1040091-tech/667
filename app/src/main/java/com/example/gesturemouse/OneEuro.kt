package com.example.gesturemouse

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max

/**
 * One Euro Filter：低延遲的平滑濾波器，慢速時很穩、快速時很跟手。
 * （從電腦版 gesture_mouse.py 的 OneEuro 直接翻譯；時間單位＝秒）
 */
class OneEuro(
    private val minCutoff: Double = 1.2,
    private val beta: Double = 0.012,   // 手機螢幕像素比電腦少，比電腦版（0.008）稍微調大
    private val dCutoff: Double = 1.0,
) {
    private var t = Double.NaN
    private var x = 0.0
    private var dx = 0.0

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    fun filter(value: Double, now: Double): Double {
        if (t.isNaN() || now - t > 0.3) {  // 中斷太久就重新開始
            t = now; x = value; dx = 0.0
            return value
        }
        val dt = max(now - t, 1e-3)
        val aD = alpha(dCutoff, dt)
        dx = aD * ((value - x) / dt) + (1 - aD) * dx
        val a = alpha(minCutoff + beta * abs(dx), dt)
        x = a * value + (1 - a) * x
        t = now
        return x
    }

    fun reset() {
        t = Double.NaN
    }
}
