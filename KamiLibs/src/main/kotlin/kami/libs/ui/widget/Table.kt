package kami.libs.ui.widget

import kami.libs.ui.text.tr
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import net.minecraft.client.gui.GuiGraphics
import org.lwjgl.glfw.GLFW

const val TABLE_ROW_H = 14
const val TABLE_HEADER_H = 14
private const val CELL_PAD = 6

enum class Align { LEFT, RIGHT, CENTER }

class Column<T>(
    val title: String,
    val width: Int,
    val align: Align = Align.LEFT,
    val sort: Comparator<T>? = null,
    val tip: String? = null,
    val cell: Ui.(GuiGraphics, Rect, T) -> Unit
) {
    companion object {
        fun <T> text(title: String, width: Int, align: Align = Align.LEFT, sortable: Boolean = true, tip: String? = null, color: (T) -> Int = { Palette.text }, value: (T) -> String) =
            Column(title, width, align, if (sortable) compareBy<T> { value(it).lowercase() } else null, tip) { _, r, row ->
                val full = value(row)
                val s = Draw.fit(full, r.w)
                if (s != full) tooltip("cell:${r.x},${r.y}", r, full)
                val x = when (align) {
                    Align.LEFT -> r.x
                    Align.RIGHT -> r.right - Draw.width(s)
                    Align.CENTER -> r.x + (r.w - Draw.width(s)) / 2
                }
                Draw.text(g, s, x, r.y + (r.h - 8) / 2, color(row))
            }

        fun <T> number(title: String, width: Int, tip: String? = null, color: (T) -> Int = { Palette.text }, format: (Long) -> String = { kami.libs.ui.style.Format.number(it) }, value: (T) -> Long) =
            Column(title, width, Align.RIGHT, compareBy(value), tip) { _, r, row ->
                val s = format(value(row))
                Draw.text(g, s, r.right - Draw.width(s), r.y + (r.h - 8) / 2, color(row))
            }
    }
}

class TableState<T> {
    var sortColumn = -1
    var descending = false
    val selected = LinkedHashSet<Any>()
    var lastClicked: Any? = null
    var lastClickAt = 0L
    var focusKey: Any? = null

    fun clear() { selected.clear() }
}

class TableEvents<T>(val opened: T?, val contextOn: T?, val selectionChanged: Boolean)

fun <T> Ui.table(
    r: Rect, columns: List<Column<T>>, rows: List<T>, state: TableState<T>, keyOf: (T) -> Any,
    multi: Boolean = false, rowHeight: Int = TABLE_ROW_H, severity: (T) -> Severity? = { null }, emptyText: String = tr("kami_libs.table.empty"),
    highlight: (T) -> Boolean = { false }, key: Any = "table"
): TableEvents<T> {
    val header = r.top(TABLE_HEADER_H)
    val body = r.dropTop(TABLE_HEADER_H)
    val checkW = if (multi) 12 else 0
    val sorted = state.sortColumn.takeIf { it in columns.indices }?.let { c ->
        columns[c].sort?.let { cmp -> rows.sortedWith(if (state.descending) cmp.reversed() else cmp) }
    } ?: rows
    val flexible = columns.count { it.width < 0 }
    val fixed = columns.filter { it.width >= 0 }.sumOf { it.width } + checkW + (columns.size + 1) * CELL_PAD + 4
    val flexW = if (flexible > 0) (r.w - fixed).coerceAtLeast(20) / flexible else 0
    val widths = columns.map { if (it.width < 0) flexW else it.width }
    Draw.fill(g, header, Palette.raised)
    Draw.hline(g, header.x, header.bottom - 1, header.w, Palette.border)
    var x = header.x + CELL_PAD
    if (multi) {
        val all = rows.isNotEmpty() && rows.all { keyOf(it) in state.selected }
        val some = rows.any { keyOf(it) in state.selected }
        checkbox(Rect(x, header.y, 10, header.h), "", if (all) true else if (some) null else false, key = "$key:all")?.let { v ->
            if (v) rows.forEach { state.selected += keyOf(it) } else state.clear()
        }
        x += checkW + CELL_PAD
    }
    columns.forEachIndexed { i, c ->
        val cell = Rect(x, header.y, widths[i], header.h)
        if (i > 0) Draw.vline(g, x - CELL_PAD / 2 - 1, header.y + 3, header.h - 6, Palette.borderSubtle)
        val sortable = c.sort != null
        val over = hovering(cell) && sortable
        if (over) { Draw.fill(g, cell, Palette.hover); cursor = Cursor.HAND }
        val sortW = if (i == state.sortColumn) 10 else 0
        val title = Draw.fit(c.title.uppercase(), cell.w - sortW)
        val tx = if (c.align == Align.RIGHT) cell.right - Draw.width(title) - sortW else cell.x
        Draw.text(g, title, tx, cell.y + (cell.h - 8) / 2, if (i == state.sortColumn) Palette.text else Palette.textMuted)
        if (i == state.sortColumn) Draw.tintedIcon(g, if (state.descending) Icons.SORT_DOWN else Icons.SORT_UP, tx + Draw.width(title) - 3, cell.centerY - Draw.ICON / 2, Draw.ICON, Palette.textSecondary)
        (c.tip ?: c.title.uppercase().takeIf { it != title }?.let { c.title })?.let { tip -> tooltip("$key:h$i", cell, tip) }
        if (sortable && pressed(cell) != null) {
            if (state.sortColumn == i) state.descending = !state.descending else { state.sortColumn = i; state.descending = false }
        }
        x += widths[i] + CELL_PAD
    }
    var opened: T? = null
    var context: T? = null
    var changed = false
    if (sorted.isEmpty()) {
        Draw.textCentered(g, emptyText, body.top(40), Palette.textMuted)
        return TableEvents(null, null, false)
    }
    val focusIndex = sorted.indexOfFirst { keyOf(it) == state.focusKey }
    if (focused(key) || hovering(body)) {
        val down = input.takeKey(GLFW.GLFW_KEY_DOWN)
        val up = input.takeKey(GLFW.GLFW_KEY_UP)
        val step = (if (down != null) 1 else 0) - (if (up != null) 1 else 0)
        if (step != 0) {
            val next = sorted[(focusIndex + step).coerceIn(0, sorted.lastIndex)]
            state.focusKey = keyOf(next)
            if (!multi) { state.selected.clear(); state.selected += keyOf(next); changed = true }
            remember("scroll:$key:rows") { ScrollState() }.scrollTo(sorted.indexOf(next) * rowHeight)
        }
        if (input.takeKey(GLFW.GLFW_KEY_ENTER) != null && focusIndex >= 0) opened = sorted[focusIndex]
    }
    focusable(key)
    scroll("$key:rows", body, sorted.size * rowHeight) { content ->
        val first = ((body.y - content.y) / rowHeight).coerceAtLeast(0)
        val last = ((body.bottom - content.y) / rowHeight).coerceAtMost(sorted.lastIndex)
        for (index in first..last) {
            val row = sorted[index]
            val k = keyOf(row)
            val rr = Rect(content.x, content.y + index * rowHeight, content.w, rowHeight)
            val chosen = k in state.selected
            val over = hovering(rr)
            Draw.fill(g, rr, when {
                chosen -> Palette.selected
                over -> Palette.hover
                index % 2 == 1 -> Palette.alpha(0xFFFFFF, 0x04)
                else -> 0
            })
            Draw.hline(g, rr.x, rr.bottom - 1, rr.w, Palette.alpha(Palette.borderSubtle, 0xA0))
            if (chosen) Draw.fill(g, rr.left(2), Palette.brass)
            severity(row)?.let { Draw.fill(g, Rect(rr.x, rr.y + 2, 2, rr.h - 4), it.color) }
            if (highlight(row)) attention(rr, true)
            var cx = rr.x + CELL_PAD
            if (multi) {
                checkbox(Rect(cx, rr.y, 10, rr.h), "", chosen, key = "$key:c:$k")?.let { v -> if (v) state.selected += k else state.selected -= k; changed = true }
                cx += checkW + CELL_PAD
            }
            columns.forEachIndexed { i, c ->
                val cell = Rect(cx, rr.y, widths[i], rr.h)
                c.cell(this@table, g, cell, row)
                cx += widths[i] + CELL_PAD
            }
            if (over) cursor = Cursor.HAND
            pressed(rr)?.let {
                val double = state.lastClicked == k && now - state.lastClickAt < 350
                state.lastClicked = k
                state.lastClickAt = now
                state.focusKey = k
                focus = id(key)
                if (double) opened = row
                when {
                    multi && input.ctrl -> if (!state.selected.remove(k)) state.selected += k
                    multi && input.shift && state.selected.isNotEmpty() -> {
                        val anchor = sorted.indexOfFirst { keyOf(it) in state.selected }
                        (minOf(anchor, index)..maxOf(anchor, index)).forEach { j -> state.selected += keyOf(sorted[j]) }
                    }
                    else -> { state.selected.clear(); state.selected += k }
                }
                changed = true
            }
            pressed(rr, 1)?.let { context = row; if (k !in state.selected) { state.selected.clear(); state.selected += k; changed = true } }
        }
    }
    return TableEvents(opened, context, changed)
}
