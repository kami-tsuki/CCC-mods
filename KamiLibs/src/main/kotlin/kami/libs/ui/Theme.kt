package kami.libs.ui

import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.max
import kotlin.math.min

object Theme {
    val BG get() = Palette.canvas
    val PANEL get() = Palette.surface
    val HEADER get() = Palette.raised
    val ROW get() = Palette.sunken
    val ROW_SEL get() = Palette.selected
    val BORDER get() = Palette.border
    val LINE get() = Palette.borderSubtle
    val TEXT get() = Palette.text
    val DIM get() = Palette.textSecondary
    val GOOD get() = Palette.success
    val BAD get() = Palette.danger
    val WARN get() = Palette.warning
    val ACCENT get() = Palette.brass

    fun fmt(n: Int): String = Format.number(n)
    fun fmt(n: Long): String = Format.number(n)

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
        g.fill(x, y, x + w, y + h, PANEL)
        g.renderOutline(x - 1, y - 1, w + 2, h + 2, BORDER)
    }

    fun header(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int) = g.fill(x, y, x + w, y + h, HEADER)

    fun bar(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, fraction: Double, color: Int) {
        g.fill(x, y, x + w, y + h, ROW)
        g.fill(x, y, x + (w * min(1.0, max(0.0, fraction))).toInt(), y + h, color)
    }

    fun ago(ms: Long): String = Format.ago(ms)

    fun span(ms: Long): String = Format.duration(ms)

    fun fit(text: String, width: Int): String {
        val font = Minecraft.getInstance().font
        if (font.width(text) <= width) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }
}
