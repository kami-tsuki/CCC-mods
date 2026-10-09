package kami.libs.ui.widget

import kami.libs.ui.anim.shimmer
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kotlin.math.max

private const val SKELETON_MAX_ROWS = 12
private const val SKELETON_BAR_H = 6
private const val LOADING_PERIOD = 1.2

fun skeletonRowCount(height: Int, rowHeight: Int = TABLE_ROW_H) = if (rowHeight <= 0) 0 else (height / rowHeight).coerceIn(0, SKELETON_MAX_ROWS)

fun Ui.skeleton(r: Rect) {
    if (r.isEmpty) return
    Draw.fill(g, r, Palette.alpha(Palette.border, 0x50))
    shimmer(r, Palette.alpha(0xFFFFFF, 0x40))
}

fun Ui.skeletonForm(r: Rect, fields: Int = 4) {
    var y = r.y
    for (i in 0 until fields) {
        if (y + 9 + CONTROL_H > r.bottom) break
        skeleton(Rect(r.x, y, r.w / 3, SKELETON_BAR_H + 1))
        skeleton(Rect(r.x, y + 9, r.w, CONTROL_H))
        y += 9 + CONTROL_H + 6
    }
}

fun Ui.skeletonLines(r: Rect, n: Int) {
    for (i in 0 until n) {
        val y = r.y + i * Draw.LINE + 1
        if (y + SKELETON_BAR_H + 1 > r.bottom) break
        skeleton(Rect(r.x, y, if (i == n - 1 && n > 1) r.w * 3 / 5 else r.w, SKELETON_BAR_H + 1))
    }
}

fun Ui.skeletonTable(r: Rect, columns: Int, rows: Int = SKELETON_MAX_ROWS) {
    val header = r.top(TABLE_HEADER_H)
    Draw.fill(g, header, Palette.raised)
    Draw.hline(g, header.x, header.bottom - 1, header.w, Palette.border)
    val widths = Rect(header.x + CELL_PAD, 0, header.w - CELL_PAD * 2, 0).columns(columns.coerceAtLeast(1), CELL_PAD).map { it.w }
    var x = header.x + CELL_PAD
    widths.forEach { w -> skeleton(Rect(x, header.y + (header.h - SKELETON_BAR_H) / 2, w / 2, SKELETON_BAR_H)); x += w + CELL_PAD }
    val body = r.dropTop(TABLE_HEADER_H)
    skeletonRows(body.withHeight(minOf(body.h, rows * TABLE_ROW_H)), widths, TABLE_ROW_H)
}

internal fun Ui.skeletonRows(body: Rect, widths: List<Int>, rowHeight: Int, x0: Int = body.x + CELL_PAD) {
    for (i in 0 until skeletonRowCount(body.h, rowHeight)) {
        val rr = Rect(body.x, body.y + i * rowHeight, body.w, rowHeight)
        Draw.hline(g, rr.x, rr.bottom - 1, rr.w, Palette.alpha(Palette.borderSubtle, 0xA0))
        var x = x0
        widths.forEachIndexed { c, w ->
            val bar = w * (if ((i + c) % 3 == 0) 3 else 4) / 5
            skeleton(Rect(x, rr.y + (rowHeight - SKELETON_BAR_H) / 2, bar.coerceAtMost(rr.right - x), SKELETON_BAR_H))
            x += w + CELL_PAD
        }
    }
}

fun Ui.skeletonTile(r: Rect) {
    Draw.sprite(g, Sprites.CARD, r)
    skeleton(Rect(r.x + 5, r.y + 5, (r.w - 10) / 2, SKELETON_BAR_H))
    skeletonValue(r)
}

internal fun Ui.skeletonValue(r: Rect) = skeleton(Rect(r.x + 5, tileValueY(r), (r.w - 10) * 2 / 3, 9))

fun Ui.skeletonKpis(r: Rect, n: Int, gap: Int = 6) = r.columns(n.coerceAtLeast(1), gap).forEach { skeletonTile(it) }

fun Ui.loadingBar(r: Rect, color: Int = Palette.brass) {
    if (r.isEmpty) return
    if (reduceMotion) return Draw.fill(g, r, Palette.alpha(color, 0x80))
    val seg = max(12, r.w / 4)
    val head = r.x - seg + ((time % LOADING_PERIOD) / LOADING_PERIOD * (r.w + seg)).toInt()
    val from = max(r.x, head)
    val to = minOf(r.right, head + seg)
    if (to > from) Draw.fill(g, Rect(from, r.y, to - from, r.h), color)
}
