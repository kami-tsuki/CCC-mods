package kami.libs.ui.app

import kotlin.math.min

object WindowSize {
    fun of(screenW: Int, screenH: Int, large: Boolean): Pair<Int, Int> {
        val big = large && screenW >= 1100 && screenH >= 680
        val capW = if (big) 1180 else 900
        val capH = if (big) 720 else 560
        val w = min(screenW - 8, (screenW * 0.92).toInt().coerceIn(400, capW))
        val h = min(screenH - 8, (screenH * 0.92).toInt().coerceIn(260, capH))
        return w to h
    }
}
