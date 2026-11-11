package kami.libs.ui.widget

import kotlin.math.max

object ScrollMath {
    fun wheelStep(content: Int, view: Int) = (view / 6 + content / 40).coerceIn(18, 64)

    fun thumbLength(track: Int, view: Int, content: Int, min: Int = 14) = (track * view / max(1, content)).coerceIn(min(min, track), track)

    fun thumbPos(track: Int, thumb: Int, offset: Double, max: Int) = if (max <= 0) 0 else ((track - thumb) * (offset / max)).toInt()

    fun offsetAt(pos: Double, track: Int, thumb: Int, max: Int) = (pos / max(1, track - thumb)).coerceIn(0.0, 1.0) * max

    private fun min(a: Int, b: Int) = if (a < b) a else b
}
