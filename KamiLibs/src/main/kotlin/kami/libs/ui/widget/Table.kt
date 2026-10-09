package kami.libs.ui.widget

import kami.libs.ui.text.tr
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Format
import kami.libs.ui.style.Severity
import net.minecraft.client.gui.GuiGraphics
import org.lwjgl.glfw.GLFW

const val TABLE_ROW_H = 14
const val TABLE_HEADER_H = 14
internal const val CELL_PAD = 6

enum class Align { LEFT, RIGHT, CENTER }

fun <T> textOrder(value: (T) -> String): Comparator<T> = Comparator { a, b -> String.CASE_INSENSITIVE_ORDER.compare(value(a), value(b)) }

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
            Column(title, width, align, if (sortable) textOrder(value) else null, tip) { _, r, row ->
                val full = value(row)
                cellText(r, full, color(row), align, "cell:$full:${r.x}:${r.y}")
            }

        fun <T> number(title: String, width: Int, tip: String? = null, color: (T) -> Int = { Palette.text }, format: (Long) -> String = { Format.number(it) }, value: (T) -> Long) =
            Column(title, width, Align.RIGHT, compareBy(value), tip) { _, r, row ->
                val full = format(value(row))
                cellText(r, full, color(row), Align.RIGHT, "cell:$full:${r.x}:${r.y}")
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
    private var sortedFrom: List<T>? = null
    private var sortedSize = -1
    private var sortedColumn = -2
    private var sortedDescending = false
    private var sortedRows: List<T> = emptyList()

    fun sorted(rows: List<T>, build: () -> List<T>): List<T> {
        if (sortedFrom !== rows || sortedSize != rows.size || sortedColumn != sortColumn || sortedDescending != descending) {
            sortedRows = build()
            sortedFrom = rows
            sortedSize = rows.size
            sortedColumn = sortColumn
            sortedDescending = descending
        }
        return sortedRows
    }

    private var groupedFrom: List<T>? = null
    private var groupedRows: List<T> = emptyList()

    fun grouped(rows: List<T>, build: (List<T>) -> List<T>): List<T> {
        if (groupedFrom !== rows) { groupedRows = build(rows); groupedFrom = rows }
        return groupedRows
    }

    private var widthsColumns: List<Column<T>>? = null
    private var widthsW = -1
    private var widthsCheck = -1
    private var widths: List<Int> = emptyList()

    fun widths(columns: List<Column<T>>, w: Int, checkW: Int): List<Int> {
        if (widthsColumns !== columns || widthsW != w || widthsCheck != checkW || widths.size != columns.size) {
            val flexible = columns.count { it.width < 0 }
            val fixed = columns.sumOf { if (it.width >= 0) it.width else 0 } + checkW + (columns.size + 1) * CELL_PAD + 4
            val flexW = if (flexible > 0) (w - fixed).coerceAtLeast(20) / flexible else 0
            widths = columns.map { if (it.width < 0) flexW else it.width }
            widthsColumns = columns
            widthsW = w
            widthsCheck = checkW
        }
        return widths
    }

    private var prunedFrom: List<T>? = null

    fun prune(rows: List<T>, keyOf: (T) -> Any) {
        if (prunedFrom === rows || rows.isEmpty()) return
        prunedFrom = rows
        if (selected.isEmpty()) return
        val keys = HashSet<Any>(rows.size * 2)
        rows.forEach { keys += keyOf(it) }
        selected.retainAll(keys)
    }

    fun clear() { selected.clear() }
}

class TableEvents<T>(val opened: T?, val contextOn: T?, val selectionChanged: Boolean)

fun <T> Ui.table(
    r: Rect, columns: List<Column<T>>, rows: List<T>, state: TableState<T>, keyOf: (T) -> Any,
    multi: Boolean = false, rowHeight: Int = TABLE_ROW_H, severity: (T) -> Severity? = { null }, emptyText: String = tr("kami_libs.table.empty"),
    highlight: (T) -> Boolean = { false }, key: Any = "table", loading: Boolean = false,
    rowHeightOf: ((T) -> Int)? = null, regroup: ((List<T>) -> List<T>)? = null
): TableEvents<T> {
    val header = r.top(TABLE_HEADER_H)
    val body = r.dropTop(TABLE_HEADER_H)
    val checkW = if (multi) 12 else 0
    val sorted = state.sorted(rows) {
        state.sortColumn.takeIf { it in columns.indices }?.let { c ->
            columns[c].sort?.let { cmp -> rows.sortedWith(if (state.descending) cmp.reversed() else cmp) }
        } ?: rows
    }.let { list -> regroup?.let { state.grouped(list, it) } ?: list }
    state.prune(rows, keyOf)
    val widths = state.widths(columns, r.w, checkW)
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
    clip(header) { columns.forEachIndexed { i, c ->
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
    } }
    var opened: T? = null
    var context: T? = null
    var changed = false
    if (loading && sorted.isNotEmpty()) loadingBar(header.bottom(2))
    if (sorted.isEmpty()) {
        if (loading) skeletonRows(body, widths, rowHeight, header.x + CELL_PAD + if (multi) checkW + CELL_PAD else 0)
        else {
            val shown = Draw.fit(emptyText, body.w - 12)
            Draw.textCentered(g, shown, body.top(40), Palette.textMuted)
            if (shown != emptyText) tooltip("$key:empty", body.top(40), emptyText)
        }
        return TableEvents(null, null, false)
    }
    val tops = rowHeightOf?.let { h -> IntArray(sorted.size + 1).also { t -> sorted.forEachIndexed { i, row -> t[i + 1] = t[i] + h(row) } } }
    fun topOf(i: Int) = tops?.get(i) ?: i * rowHeight
    if (focused(key) || hovering(body)) {
        val down = input.takeKey(GLFW.GLFW_KEY_DOWN)
        val up = input.takeKey(GLFW.GLFW_KEY_UP)
        val step = (if (down != null) 1 else 0) - (if (up != null) 1 else 0)
        val enter = input.takeKey(GLFW.GLFW_KEY_ENTER)
        val focusIndex = if (step != 0 || enter != null) sorted.indexOfFirst { keyOf(it) == state.focusKey } else -1
        if (step != 0) {
            val next = sorted[(focusIndex + step).coerceIn(0, sorted.lastIndex)]
            state.focusKey = keyOf(next)
            if (!multi) { state.selected.clear(); state.selected += keyOf(next); changed = true }
            remember("scroll:$key:rows") { ScrollState() }.scrollTo(topOf(sorted.indexOf(next)))
        }
        if (enter != null && focusIndex >= 0) opened = sorted[focusIndex]
    }
    focusable(key)
    scroll("$key:rows", body, topOf(sorted.size)) { content ->
        val first = if (tops == null) ((body.y - content.y) / rowHeight).coerceAtLeast(0) else (0..sorted.lastIndex).firstOrNull { tops[it + 1] > body.y - content.y } ?: sorted.lastIndex
        val last = if (tops == null) ((body.bottom - content.y) / rowHeight).coerceAtMost(sorted.lastIndex) else ((first..sorted.lastIndex).firstOrNull { tops[it] >= body.bottom - content.y }?.minus(1) ?: sorted.lastIndex)
        for (index in first..last) {
            val row = sorted[index]
            val k = keyOf(row)
            val rr = Rect(content.x, content.y + topOf(index), content.w, tops?.let { it[index + 1] - it[index] } ?: rowHeight)
            val chosen = k in state.selected
            val over = hovering(rr)
            Draw.fill(g, rr, when {
                chosen -> Palette.selected
                over -> Palette.hover
                index % 2 == 1 -> Palette.alpha(0x000000, 0x0C)
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
                val double = state.lastClicked == k && wallMillis - state.lastClickAt < 350
                state.lastClicked = k
                state.lastClickAt = wallMillis
                state.focusKey = k
                focus = id(key)
                if (double) opened = row
                when {
                    multi && input.ctrl -> if (!state.selected.remove(k)) state.selected += k
                    multi && input.shift && state.selected.isNotEmpty() && sorted.indexOfFirst { keyOf(it) in state.selected } >= 0 -> {
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
