package kami.libs.ui.widget

import kami.libs.ui.anim.reveal
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette

const val CARD_GAP = 4
const val TEASER_H = 150
private const val STAGGER_MS = 30L
private const val MAX_STAGGER = 12
private const val SLIDE = 4

fun cardColumns(width: Int, minCardWidth: Int, maxColumns: Int) = ((width + CARD_GAP) / (minCardWidth + CARD_GAP)).coerceIn(1, maxColumns)

fun cardWidth(width: Int, columns: Int) = (width - (columns - 1) * CARD_GAP) / columns

fun Ui.staggered(r: Rect, key: Any, index: Int, draw: (Rect) -> Unit) {
    val appear = reveal(key, 0L, delayMs = index.coerceAtMost(MAX_STAGGER) * STAGGER_MS)
    draw(r.slideIn(appear, 0, SLIDE))
    if (appear < 1f) Draw.veilBox(g, r, appear, Palette.surface)
}

fun Stack.cardGrid(count: Int, columns: Int, height: (Int) -> Int, draw: (Rect, Int) -> Unit) {
    val cardW = cardWidth(w, columns)
    for (first in 0 until count step columns) {
        val last = minOf(first + columns, count)
        val rowH = (first until last).maxOf(height)
        val row = take(rowH)
        for (i in first until last) draw(Rect(row.x + (i - first) * (cardW + CARD_GAP), row.y, cardW, rowH), i)
    }
}
