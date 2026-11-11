package kami.libs.ui.widget

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Metrics
import kami.libs.ui.text.tr

class MasterDetail(private val share: Double, private val min: Int, private val max: Int) {
    private var open = false
    private var seen: Set<Any> = emptySet()

    fun width(total: Int) = (total * share).toInt().coerceIn(min, max)

    internal fun track(compact: Boolean, selection: Set<Any>): Boolean {
        val changed = selection != seen
        if (!compact || selection.isEmpty()) open = false
        else if (changed) open = true
        if (changed) seen = selection.toSet()
        return open
    }

    fun draw(ui: Ui, r: Rect, compact: Boolean, selection: MutableSet<Any>, master: (Rect) -> Unit, detail: (Rect) -> Unit) {
        val showing = track(compact, selection)
        if (!compact) {
            val w = width(r.w)
            master(r.dropRight(w, 8))
            detail(r.right(w))
            return
        }
        if (!showing) return master(r)
        if (ui.button(r.top(CONTROL_H), tr("kami_libs.common.back"), Icons.BACK, ButtonStyle.SECONDARY, key = "detail-back")) {
            open = false
            selection.clear()
            seen = emptySet()
            return
        }
        detail(r.dropTop(CONTROL_H + 6))
    }

    companion object {
        const val LIST_MIN_W = 260

        fun fits(areaW: Int, panelW: Int, gap: Int = Metrics.gap) = areaW >= panelW + gap + LIST_MIN_W
    }
}

class Split(val list: Rect?, val detail: Rect?)

fun Ui.masterDetail(area: Rect, panelW: Int, open: Boolean, close: () -> Unit): Split {
    val gap = Metrics.gap
    if (!open) return Split(area, null)
    if (MasterDetail.fits(area.w, panelW, gap)) return Split(area.dropRight(panelW, gap), area.right(panelW))
    val label = tr("kami_libs.common.back")
    if (button(area.top(SMALL_H).left(buttonWidth(label)), label, key = "detail-back")) close()
    return Split(null, area.dropTop(SMALL_H, gap))
}
