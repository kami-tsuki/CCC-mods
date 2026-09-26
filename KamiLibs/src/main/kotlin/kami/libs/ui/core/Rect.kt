package kami.libs.ui.core

import kotlin.math.max
import kotlin.math.min

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
    val centerX get() = x + w / 2
    val centerY get() = y + h / 2
    val isEmpty get() = w <= 0 || h <= 0

    operator fun contains(p: Pair<Int, Int>) = p.first >= x && p.first < right && p.second >= y && p.second < bottom
    fun contains(px: Int, py: Int) = px >= x && px < right && py >= y && py < bottom

    fun inset(all: Int) = inset(all, all)
    fun inset(horizontal: Int, vertical: Int) = Rect(x + horizontal, y + vertical, max(0, w - horizontal * 2), max(0, h - vertical * 2))
    fun inset(left: Int, top: Int, right: Int, bottom: Int) = Rect(x + left, y + top, max(0, w - left - right), max(0, h - top - bottom))
    fun offset(dx: Int, dy: Int) = Rect(x + dx, y + dy, w, h)
    fun grow(all: Int) = Rect(x - all, y - all, w + all * 2, h + all * 2)
    fun withHeight(height: Int) = Rect(x, y, w, height)
    fun withWidth(width: Int) = Rect(x, y, width, h)

    fun top(height: Int) = Rect(x, y, w, min(height, h))
    fun bottom(height: Int) = Rect(x, bottom - min(height, h), w, min(height, h))
    fun left(width: Int) = Rect(x, y, min(width, w), h)
    fun right(width: Int) = Rect(right - min(width, w), y, min(width, w), h)

    fun dropTop(height: Int, gap: Int = 0) = inset(0, min(h, height + gap), 0, 0)
    fun dropBottom(height: Int, gap: Int = 0) = inset(0, 0, 0, min(h, height + gap))
    fun dropLeft(width: Int, gap: Int = 0) = inset(min(w, width + gap), 0, 0, 0)
    fun dropRight(width: Int, gap: Int = 0) = inset(0, 0, min(w, width + gap), 0)

    fun centered(width: Int, height: Int) = Rect(x + (w - width) / 2, y + (h - height) / 2, width, height)

    fun intersect(o: Rect): Rect {
        val nx = max(x, o.x)
        val ny = max(y, o.y)
        return Rect(nx, ny, max(0, min(right, o.right) - nx), max(0, min(bottom, o.bottom) - ny))
    }

    fun columns(count: Int, gap: Int = 0): List<Rect> = split(List(count) { 1f }, gap, horizontal = true)
    fun rows(count: Int, gap: Int = 0): List<Rect> = split(List(count) { 1f }, gap, horizontal = false)
    fun columns(weights: List<Float>, gap: Int = 0): List<Rect> = split(weights, gap, horizontal = true)
    fun rows(weights: List<Float>, gap: Int = 0): List<Rect> = split(weights, gap, horizontal = false)

    fun columnsFixed(vararg widths: Int, gap: Int = 0): List<Rect> = fixed(widths.toList(), gap, horizontal = true)
    fun rowsFixed(vararg heights: Int, gap: Int = 0): List<Rect> = fixed(heights.toList(), gap, horizontal = false)

    fun grid(columns: Int, rowHeight: Int, count: Int, gap: Int = 0): List<Rect> {
        val cellW = (w - gap * (columns - 1)) / max(1, columns)
        return List(count) { i -> Rect(x + (i % columns) * (cellW + gap), y + (i / columns) * (rowHeight + gap), cellW, rowHeight) }
    }

    private fun split(weights: List<Float>, gap: Int, horizontal: Boolean): List<Rect> {
        val total = if (horizontal) w else h
        val space = max(0, total - gap * (weights.size - 1))
        val sum = weights.sum().takeIf { it > 0f } ?: 1f
        var cursor = if (horizontal) x else y
        return weights.mapIndexed { i, weight ->
            val size = if (i == weights.lastIndex) (if (horizontal) right else bottom) - cursor else (space * weight / sum).toInt()
            val r = if (horizontal) Rect(cursor, y, size, h) else Rect(x, cursor, w, size)
            cursor += size + gap
            r
        }
    }

    private fun fixed(sizes: List<Int>, gap: Int, horizontal: Boolean): List<Rect> {
        val total = if (horizontal) w else h
        val flexible = sizes.count { it < 0 }
        val rest = max(0, total - sizes.filter { it >= 0 }.sum() - gap * (sizes.size - 1))
        var cursor = if (horizontal) x else y
        return sizes.map { size ->
            val s = if (size < 0) rest / max(1, flexible) else size
            val r = if (horizontal) Rect(cursor, y, s, h) else Rect(x, cursor, w, s)
            cursor += s + gap
            r
        }
    }

    companion object {
        val ZERO = Rect(0, 0, 0, 0)
        const val FILL = -1
    }
}

class Flow(private var area: Rect, private val gap: Int = 4) {
    val rest get() = area

    fun take(height: Int): Rect = area.top(height).also { area = area.dropTop(height, gap) }
    fun takeFromBottom(height: Int): Rect = area.bottom(height).also { area = area.dropBottom(height, gap) }
    fun skip(height: Int) { area = area.dropTop(height) }
    fun remaining(): Rect = area.also { area = Rect(area.x, area.bottom, area.w, 0) }
}

class Row(private var area: Rect, private val gap: Int = 4) {
    val rest get() = area

    fun take(width: Int): Rect = area.left(width).also { area = area.dropLeft(width, gap) }
    fun takeFromRight(width: Int): Rect = area.right(width).also { area = area.dropRight(width, gap) }
    fun remaining(): Rect = area.also { area = Rect(area.right, area.y, 0, area.h) }
}
