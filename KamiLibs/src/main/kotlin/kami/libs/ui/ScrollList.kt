package kami.libs.ui

import net.minecraft.client.gui.GuiGraphics
import kotlin.math.max
import kotlin.math.min

class ScrollList<T>(var x: Int, var y: Int, var w: Int, var h: Int, private val rowH: Int = 13, private val paint: (GuiGraphics, T, Int, Int, Int, Int) -> Unit) {
    var items: List<T> = emptyList()
        set(value) {
            field = value
            offset = offset.coerceIn(0, max(0, value.size - visible))
            if (selected >= value.size) selected = -1
        }
    var selected = -1
    private var offset = 0
    private val visible get() = max(1, h / rowH)

    val current: T? get() = items.getOrNull(selected)

    fun draw(g: GuiGraphics, mx: Int, my: Int) {
        g.fill(x, y, x + w, y + h, Theme.PANEL)
        g.enableScissor(x, y, x + w, y + h)
        for (i in 0 until visible) {
            val index = offset + i
            val item = items.getOrNull(index) ?: break
            val ry = y + i * rowH
            val hover = mx in x until x + w - 5 && my in ry until ry + rowH
            g.fill(x, ry, x + w - 5, ry + rowH - 1, if (index == selected) Theme.ROW_SEL else if (hover) Theme.LINE else Theme.ROW)
            paint(g, item, x + 3, ry, w - 8, index)
        }
        g.disableScissor()
        if (items.size > visible) {
            val track = h - 2
            val thumb = max(8, track * visible / items.size)
            val ty = y + 1 + (track - thumb) * offset / max(1, items.size - visible)
            g.fill(x + w - 4, y + 1, x + w - 1, y + h - 1, Theme.LINE)
            g.fill(x + w - 4, ty, x + w - 1, ty + thumb, Theme.DIM)
        }
    }

    fun click(mx: Double, my: Double): Boolean {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false
        val index = offset + ((my - y) / rowH).toInt()
        if (index in items.indices) selected = index
        return true
    }

    fun scroll(mx: Double, my: Double, dy: Double): Boolean {
        if (mx < x || mx >= x + w || my < y || my >= y + h) return false
        offset = (offset - dy.toInt()).coerceIn(0, max(0, items.size - visible))
        return true
    }
}

class RowScroll(private val rowH: Int) {
    var offset = 0
        private set

    fun visible(ah: Int) = max(1, ah / rowH)
    fun clamp(count: Int, ah: Int) { offset = offset.coerceIn(0, max(0, count - visible(ah))) }
    fun range(count: Int, ah: Int) = offset until min(count, offset + visible(ah))

    fun scroll(x0: Int, y0: Int, w: Int, h: Int, mx: Double, my: Double, dy: Double, count: Int): Boolean {
        if (mx < x0 || mx >= x0 + w || my < y0 || my >= y0 + h) return false
        offset = (offset - dy.toInt()).coerceIn(0, max(0, count - visible(h)))
        return true
    }

    fun bar(g: GuiGraphics, x: Int, y: Int, h: Int, count: Int) {
        val visible = visible(h)
        if (count <= visible) return
        val thumb = max(10, h * visible / count)
        val ty = y + (h - thumb) * offset / max(1, count - visible)
        g.fill(x, y, x + 2, y + h, Theme.LINE)
        g.fill(x, ty, x + 2, ty + thumb, Theme.DIM)
    }
}
