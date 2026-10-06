package kami.libs.ui.widget

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette

fun Ui.staleVeil(r: Rect, loading: Boolean = true) {
    if (r.isEmpty) return
    Draw.fill(g, r, Palette.alpha(Palette.surface, 0x70))
    if (loading) loadingBar(r.top(2))
}

fun Ui.staleRows(table: Rect, stale: Boolean, loading: Boolean = false) {
    if (stale) staleVeil(table.dropTop(TABLE_HEADER_H), loading)
}
