package kami.libs.ui.widget

import kami.libs.ui.core.Rect
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

object Flags {
    val patterns = listOf("plain", "stripes", "columns", "nordic_cross", "diagonal", "border", "quarters", "top_half", "left_half", "saltire", "chevron", "disc")
    val emblems = listOf(null, Icons.CROWN, Icons.STAR, Icons.SHIELD, Icons.TREE, Icons.PICKAXE, Icons.WHEAT, Icons.GEAR, Icons.SCALES, Icons.HOUSE, Icons.FIRE, Icons.WATER, Icons.GLOBE, Icons.SCROLL, Icons.HANDSHAKE, Icons.TOWN)

    fun draw(g: GuiGraphics, r: Rect, color: Int, pattern: Int, emblem: Int, secondary: Int) {
        val a = Palette.opaque(color)
        val b = Palette.opaque(secondary)
        Draw.fill(g, r, a)
        val (x, y, w, h) = r
        when (pattern) {
            1 -> Draw.fill(g, Rect(x, y + h / 3, w, h - 2 * (h / 3)), b)
            2 -> Draw.fill(g, Rect(x + w / 3, y, w - 2 * (w / 3), h), b)
            3 -> { Draw.fill(g, Rect(x, y + h * 2 / 5, w, (h / 5).coerceAtLeast(1)), b); Draw.fill(g, Rect(x + w / 3, y, (w / 6).coerceAtLeast(1), h), b) }
            4 -> for (i in 0 until w) g.fill(x + i, y + i * h / w, x + i + 1, y + h, b)
            5 -> { Draw.fill(g, r, b); Draw.fill(g, r.inset((w / 8).coerceAtLeast(1), (h / 6).coerceAtLeast(1)), a) }
            6 -> { Draw.fill(g, Rect(x + w / 2, y, w - w / 2, h / 2), b); Draw.fill(g, Rect(x, y + h / 2, w / 2, h - h / 2), b) }
            7 -> Draw.fill(g, Rect(x, y, w, h / 2), b)
            8 -> Draw.fill(g, Rect(x, y, w / 2, h), b)
            9 -> {
                val t = (h / 6).coerceAtLeast(1)
                for (i in 0 until w) {
                    val c = i * h / w
                    g.fill(x + i, y + c - t / 2, x + i + 1, y + c + (t + 1) / 2, b)
                    g.fill(x + i, y + h - c - t / 2 - 1, x + i + 1, y + h - c + (t + 1) / 2 - 1, b)
                }
            }
            10 -> for (i in 0 until w / 2) { val c = i * h / w; g.fill(x + i, y + c, x + i + 1, y + h - c, b) }
            11 -> {
                val cx = x + w / 2.0; val cy = y + h / 2.0; val rad = h / 3.0
                for (py in 0 until h) {
                    val dy = py + y + 0.5 - cy
                    val rest = rad * rad - dy * dy
                    if (rest < 0) continue
                    val half = sqrt(rest)
                    val from = ceil(cx - half - x - 0.5).toInt().coerceAtLeast(0)
                    val to = floor(cx + half - x - 0.5).toInt().coerceAtMost(w - 1)
                    if (to >= from) g.fill(x + from, y + py, x + to + 1, y + py + 1, b)
                }
            }
        }
        emblems.getOrNull(emblem)?.let { icon ->
            when {
                h >= 24 -> Draw.icon(g, icon, x + (w - Draw.ICON) / 2, y + (h - Draw.ICON) / 2)
                h >= 12 -> Draw.marker(g, icon, x + w / 2, y + h / 2)
            }
        }
        Draw.outline(g, r, Palette.alpha(0, 0xA0))
    }
}
