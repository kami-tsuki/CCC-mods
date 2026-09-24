package kami.claims.client.ui

import kami.libs.ui.RowScroll as LibRowScroll
import kami.libs.ui.ScrollList as LibScrollList
import kami.libs.ui.Theme

typealias ScrollList<T> = LibScrollList<T>
typealias RowScroll = LibRowScroll

object Ui {
    val BG = Theme.BG
    val PANEL = Theme.PANEL
    val ROW = Theme.ROW
    val ROW_SEL = Theme.ROW_SEL
    val LINE = Theme.LINE
    val TEXT = Theme.TEXT
    val DIM = Theme.DIM
    val GOOD = Theme.GOOD
    val BAD = Theme.BAD
    val WARN = Theme.WARN
    val GOLD = Theme.GOLD
    val ACCENT = Theme.ACCENT

    const val PLUS = "+"
    const val MINUS = "-"
    const val CHECK = "v"
    const val CROSS = "x"
    const val STAR = "*"

    private val typePalette = intArrayOf(0xFFB0B0B0.toInt(), 0xFFE0A050.toInt(), 0xFF7CC576.toInt(), 0xFF4FA36B.toInt(), 0xFF6AA9FF.toInt(), 0xFFE07B9B.toInt(), 0xFFC9A0F0.toInt(), 0xFF7A7A8C.toInt(), 0xFF50C8C8.toInt(), 0xFFD9D96A.toInt())
    private val typeOrder = listOf("civic", "mining", "farming", "forestry", "factory", "market", "residential", "infrastructure", "wilderness")

    fun typeColor(type: String): Int {
        val i = typeOrder.indexOf(type)
        return typePalette[if (i >= 0) i else kotlin.math.abs(type.hashCode()) % typePalette.size]
    }

    fun rankColor(rank: String) = when (rank) {
        "president" -> GOLD
        "chancellor" -> 0xFFC9A0F0.toInt()
        "officer" -> ACCENT
        "citizen" -> GOOD
        "allied" -> 0xFF50C8C8.toInt()
        "banished" -> BAD
        else -> DIM
    }

    fun rgb(c: Int) = Theme.rgb(c)
    fun alpha(c: Int, a: Int) = Theme.alpha(c, a)
    fun ago(ms: Long) = Theme.ago(ms)
    fun span(ms: Long) = Theme.span(ms)
    fun fit(text: String, width: Int) = Theme.fit(text, width)
    fun bar(g: net.minecraft.client.gui.GuiGraphics, x: Int, y: Int, w: Int, h: Int, fraction: Double, color: Int) = Theme.bar(g, x, y, w, h, fraction, color)
}
