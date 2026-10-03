package kami.claims.client.app

import kami.claims.client.app.pages.ResearchLook
import kami.claims.client.app.pages.barFor
import kami.claims.client.app.pages.levelRequirements
import kami.claims.client.store.ClientResearch
import kami.claims.client.store.NodeStatus
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.pin.PinWindow
import kami.libs.ui.pin.Pins
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.PROGRESS_LABELLED_H
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.progressBar

/** Country level and research pins for the HUD. */
object ResearchPins {
    const val LEVEL = "kami_claims:level"
    const val RESEARCH = "kami_claims:research"

    fun register() {
        Pins.register(Level)
        Pins.register(Research)
    }

    private val inCountry get() = ClientResearch.state.country.isNotEmpty()

    /** Shows the pinned level, or the next one once the country got there, so the pin keeps pointing ahead. */
    private object Level : PinWindow(LEVEL, width = 150, height = 74) {
        private fun target(items: List<String>): Int {
            val pinned = items.firstOrNull()?.toIntOrNull() ?: 0
            return maxOf(pinned, ClientResearch.state.level + 1).coerceAtMost(ClientResearch.maxLevel)
        }

        override fun shown(items: List<String>) = items.isNotEmpty() && inCountry && ClientResearch.defs.levels.isNotEmpty()

        override fun title(items: List<String>) = tr("kami_libs.lock.ui.level", target(items))

        override fun draw(ui: Ui, r: Rect, items: List<String>, editing: Boolean) {
            val stack = Stack(r.x, r.y + 1, r.w, 1)
            if (ClientResearch.atMaxLevel) {
                Draw.text(ui.g, tr("kami_claims.research.levels.max"), r.x, stack.take(Draw.LINE).y, Palette.success)
                return
            }
            val level = target(items)
            val need = ClientResearch.defs.levels.firstOrNull { it.level == level }?.xp ?: 0L
            val xp = ClientResearch.state.xp
            if (need > 0) {
                val line = stack.take(Draw.LINE)
                Draw.text(ui.g, "XP", line.x, line.y, Palette.textMuted)
                Draw.textRight(ui.g, "${Format.number(xp.coerceAtMost(need))}/${Format.number(need)}", line.right, line.y, Palette.textMuted)
                Draw.thinBar(ui.g, stack.take(2), xp.toFloat() / need, Palette.brass)
            }
            ui.levelRequirements(stack, level)
        }
    }

    /** Up to five research nodes with their queue progress or status. */
    private object Research : PinWindow(RESEARCH, maxItems = 5, width = 150, height = 96) {
        override fun shown(items: List<String>) = items.isNotEmpty() && inCountry

        override fun title(items: List<String>) = tr("kami_claims.pin.research")

        override fun draw(ui: Ui, r: Rect, items: List<String>, editing: Boolean) {
            val stack = Stack(r.x, r.y + 1, r.w, 2)
            items.forEach { key ->
                val node = ClientResearch.node(key) ?: return@forEach
                var row = stack.take(PROGRESS_LABELLED_H)
                if (editing) {
                    if (ui.iconButton(row.right(ROW_BUTTON).withHeight(ROW_BUTTON), Icons.CLOSE, tr("kami_libs.pin.unpin"), key = "unpin:$key")) Pins.unpin(RESEARCH, key)
                    row = row.dropRight(ROW_BUTTON, 2)
                }
                val status = ClientResearch.status(key)
                val queue = ClientResearch.queued(key)
                if (queue != null) {
                    val spec = barFor(node, queue, false, ui.wallMillis)
                    ui.progressBar(row, spec.value, spec.max, node.label().resolve(), spec.remaining, ResearchLook.color(status), paused = spec.stalled == true, key = "pin-bar:$key")
                    return@forEach
                }
                val label = ResearchLook.label(status)
                Draw.textRight(ui.g, label, row.right, row.y, ResearchLook.color(status))
                Draw.text(ui.g, Draw.fit(node.label().resolve(), row.w - Draw.width(label) - 4), row.x, row.y, if (status == NodeStatus.DONE) Palette.textMuted else Palette.text)
            }
        }
    }

    private const val ROW_BUTTON = 12
}
