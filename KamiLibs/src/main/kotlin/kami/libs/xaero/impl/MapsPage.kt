package kami.libs.xaero.impl

import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.PersonalSection
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.segmented
import kami.libs.ui.widget.toggle
import kami.libs.xaero.MapChoice
import kami.libs.xaero.MapToggle

private const val PAGE = "maps.xaero"
private const val ROW = 16

private class MapsPage : Page() {
    override val title get() = tr("kami_libs.maps.xaero")

    override fun draw(ui: Ui, r: Rect) {
        val toggles = Registry.toggles().filter { it.visible() }
        val choices = Registry.choices()
        val groups = (toggles.map { it.group } + choices.map { it.group }).distinct()
        val detected = listOfNotNull(
            "Xaero's Minimap".takeIf { Detect.minimap },
            "Xaero's World Map".takeIf { Detect.worldMap },
            "XaeroPlus".takeIf { Detect.plus }
        )
        val height = ROW + groups.sumOf { g -> ROW + (toggles.count { it.group == g } + choices.count { it.group == g }) * (ROW + 2) + 6 }
        ui.scroll("maps-xaero", r, height) { a ->
            var y = a.y
            val line = if (detected.isEmpty()) tr("kami_libs.maps.none") else tr("kami_libs.maps.detected", detected.joinToString(", "))
            Draw.text(ui.g, line, a.x, y + 4, Palette.textMuted)
            y += ROW
            groups.forEach { g ->
                Draw.text(ui.g, tr(g), a.x, y + 4, Palette.text)
                y += ROW
                toggles.filter { it.group == g }.forEach { t ->
                    toggleRow(ui, Rect(a.x, y, a.w, ROW), t)
                    y += ROW + 2
                }
                choices.filter { it.group == g }.forEach { c ->
                    choiceRow(ui, Rect(a.x, y, a.w, ROW), c)
                    y += ROW + 2
                }
                y += 6
            }
        }
    }

    private fun toggleRow(ui: Ui, row: Rect, t: MapToggle) {
        ui.toggle(row, Registry.on(t.id), tr(t.label), tip = tr(t.tip), key = "maps:t:${t.id}")?.let { Registry.setToggle(t.id, it) }
    }

    private fun choiceRow(ui: Ui, row: Rect, c: MapChoice) {
        val label = Draw.fit(tr(c.label), row.w / 2 - 4)
        Draw.text(ui.g, label, row.x, row.y + (row.h - 8) / 2, Palette.textSecondary)
        val options = c.options.map { (id, key) -> Option(id, tr(key)) }
        ui.segmented(row.right(row.w / 2), options, Registry.choice(c.id), key = "maps:c:${c.id}")?.let { Registry.setChoice(c.id, it) }
    }
}

object MapsSection {
    val section = PersonalSection(
        "maps", 90, { "kami_libs.maps.group" },
        { if (!Detect.any || Registry.toggles().none { it.visible() } && Registry.choices().isEmpty()) emptyList() else listOf(NavItem(PAGE, tr("kami_libs.maps.xaero"), Icons.MAP)) },
        { _, id -> if (id == PAGE) MapsPage() else null }
    )
}
