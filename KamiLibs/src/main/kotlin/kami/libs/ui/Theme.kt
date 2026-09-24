package kami.libs.ui

import kami.libs.economy.Coins
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.max
import kotlin.math.min

object Theme {
    val BG = 0xFF1B1B21.toInt()
    val PANEL = 0xFF181820.toInt()
    val HEADER = 0xFF23232C.toInt()
    val ROW = 0xFF2E2E38.toInt()
    val ROW_SEL = 0xFF41415A.toInt()
    val HOVER = 0x40FFFFFF
    val BORDER = 0xFF3A3A46.toInt()
    val LINE = 0xFF3F3F4C.toInt()
    val TEXT = 0xFFE6E6EA.toInt()
    val DIM = 0xFFB4B4C4.toInt()
    val GOOD = 0xFF7CE38B.toInt()
    val BAD = 0xFFFF6B6B.toInt()
    val WARN = 0xFFFFC857.toInt()
    val GOLD = 0xFFFFD86B.toInt()
    val ACCENT = 0xFF6AA9FF.toInt()

    fun fmt(n: Int): String = "%,d".format(n)
    fun fmt(n: Long): String = "%,d".format(n)

    fun compact(n: Long): String {
        val neg = n < 0
        val v = kotlin.math.abs(n)
        if (v < 1000) return if (neg) "-$v" else "$v"
        val units = charArrayOf('K', 'M', 'B', 'T')
        var scaled = v.toDouble()
        var unit = -1
        while (scaled >= 1000.0 && unit < units.lastIndex) { scaled /= 1000.0; unit++ }
        val text = if (scaled < 10.0) "%.1f".format(scaled) else scaled.toLong().toString()
        return (if (neg) "-" else "") + text + units[unit]
    }
    fun compact(n: Int): String = compact(n.toLong())

    fun rgb(c: Int) = 0xFF000000.toInt() or (c and 0xFFFFFF)
    fun alpha(c: Int, a: Int) = (a shl 24) or (c and 0xFFFFFF)

    fun panel(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, BORDER)
        g.fill(x, y, x + w, y + h, PANEL)
    }

    fun header(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int) = g.fill(x, y, x + w, y + h, HEADER)

    fun bar(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, fraction: Double, color: Int) {
        g.fill(x, y, x + w, y + h, ROW)
        g.fill(x, y, x + (w * min(1.0, max(0.0, fraction))).toInt(), y + h, color)
    }

    fun ago(ms: Long): String {
        if (ms <= 0) return "never"
        val s = (System.currentTimeMillis() - ms) / 1000
        return when {
            s < 90 -> "now"
            s < 3600 -> "${s / 60}m ago"
            s < 86400 -> "${s / 3600}h ago"
            else -> "${s / 86400}d ago"
        }
    }

    fun span(ms: Long): String {
        val m = max(0, ms / 60000)
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    fun drawCoin(g: GuiGraphics, font: Font, x: Int, y: Int, amount: Coins.Compact, color: Int, shadow: Boolean = false): Int {
        g.drawString(font, amount.amount, x, y, color, shadow)
        val aw = font.width(amount.amount)
        g.drawString(font, amount.glyph.toString(), x + aw, y, 0xFFFFFFFF.toInt(), shadow)
        return aw + font.width(amount.glyph.toString())
    }

    fun coinWidth(font: Font, amount: Coins.Compact): Int = font.width(amount.amount) + font.width(amount.glyph.toString())

    fun fit(text: String, width: Int): String {
        val font = Minecraft.getInstance().font
        if (font.width(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }
}
