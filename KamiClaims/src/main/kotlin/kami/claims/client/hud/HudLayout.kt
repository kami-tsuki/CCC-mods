package kami.claims.client.hud

import kami.libs.ui.core.Rect
import kotlin.math.roundToInt

object HudLayout {
    const val MARGIN = 4
    const val BOX_H = 15

    private fun span(screen: Int, size: Int) = (screen - size - 2 * MARGIN).coerceAtLeast(0)

    fun place(sw: Int, sh: Int, w: Int, fx: Float, fy: Float) =
        Rect(MARGIN + (fx.coerceIn(0f, 1f) * span(sw, w)).roundToInt(), MARGIN + (fy.coerceIn(0f, 1f) * span(sh, BOX_H)).roundToInt(), w, BOX_H)

    fun fraction(screen: Int, size: Int, pos: Int) = span(screen, size).let { if (it == 0) 0f else ((pos - MARGIN).toFloat() / it).coerceIn(0f, 1f) }
}
