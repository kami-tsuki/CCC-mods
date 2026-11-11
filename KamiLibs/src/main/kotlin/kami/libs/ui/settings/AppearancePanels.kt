package kami.libs.ui.settings

import kami.libs.ui.UiPrefs
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.core.UiScale
import kami.libs.ui.style.Density
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Metrics
import kami.libs.ui.style.Mode
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Theme
import kami.libs.ui.style.ThemeFamily
import kami.libs.ui.style.Variant
import kami.libs.ui.text.label
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

private const val THEME_W = 112
private const val THEME_H = 34
private const val THEME_GAP = 6
private const val GAP = 4
private const val CARD_PAD = 10

class AppearancePanels {
    private var dirty = false
    private var sample = true
    private var progress = 0.62

    private val line get() = Metrics.px(14)
    private val label get() = Metrics.px(9)

    private fun cardHeight(vararg parts: Int) = Metrics.cardHeaderH + CARD_PAD + parts.sum() + GAP * (parts.size - 1)

    fun begin(ui: Ui) {
        if (!ui.isDown()) flush()
    }

    fun flush() {
        if (!dirty) return
        dirty = false
        UiPrefs.save()
    }

    fun themeColumns(width: Int) = maxOf(1, (width - CARD_PAD + THEME_GAP) / (THEME_W + THEME_GAP))

    private fun themeRows(columns: Int) = (Theme.families().size + columns - 1) / columns

    fun lookHeight(columns: Int) = cardHeight(label, themeRows(columns) * (THEME_H + THEME_GAP) - THEME_GAP, label, CONTROL_H, label, CONTROL_H, label, CONTROL_H, label, CONTROL_H)

    fun look(ui: Ui, box: Rect, columns: Int) {
        val p = UiPrefs.prefs
        ui.anchor("appearance:look", box)
        val f = Flow(ui.card(box, tr("kami_libs.appearance.look"), Icons.BRUSH), GAP)
        ui.fieldLabel(f.take(label), tr("kami_libs.appearance.theme"))
        val families = Theme.families()
        val cells = f.take(themeRows(columns) * (THEME_H + THEME_GAP) - THEME_GAP).grid(columns, THEME_H, families.size, THEME_GAP)
        val current = Theme.current().family
        families.forEachIndexed { i, fam -> if (themeCard(ui, cells[i], fam, fam.id == current)) UiPrefs.change { it.theme = fam.id } }
        ui.fieldLabel(f.take(label), tr("kami_libs.appearance.mode"))
        ui.segmented(f.take(CONTROL_H), Mode.entries.map { Option(it, tr("kami_libs.appearance.mode.${it.name.lowercase()}")) }, UiPrefs.mode, key = "appearance-mode")
            ?.let { m -> UiPrefs.change { it.mode = m.name.lowercase() } }
        ui.fieldLabel(f.take(label), tr("kami_libs.appearance.density"))
        ui.segmented(f.take(CONTROL_H), Density.entries.map { Option(it, tr("kami_libs.appearance.density.${it.key}")) }, Density.parse(p.density), key = "appearance-density")
            ?.let { d -> UiPrefs.change { it.density = d.key } }
        ui.fieldLabel(f.take(label), tr("kami_libs.appearance.scale"))
        ui.segmented(f.take(CONTROL_H), UiScale.choices.map { Option(it, Format.percent(it.toDouble())) }, UiScale.snap(p.uiScale), key = "appearance-scale")
            ?.let { s -> UiPrefs.change { it.uiScale = s } }
        ui.fieldLabel(f.take(label), tr("kami_libs.appearance.colours"))
        ui.select(f.take(CONTROL_H), Palette.Vision.entries.map { Option(it.name, tr("kami_libs.appearance.vision.${it.name.lowercase()}"), description = tr("kami_libs.appearance.vision.${it.name.lowercase()}.desc")) }, p.vision, key = "appearance-vision")
            ?.let { v -> UiPrefs.change { it.vision = v } }
    }

    private fun themeCard(ui: Ui, r: Rect, family: ThemeFamily, selected: Boolean): Boolean {
        val values = Theme.resolvedOf(family.defFor(Theme.current().variant == Variant.DARK).id)?.values
        fun color(name: String, fallback: Int) = values?.get(name) ?: fallback
        val hit = ui.clickable("theme:${family.id}", r)
        val edge = if (selected) color("border.brass", Palette.brass) else if (ui.hovering(r)) Palette.textSecondary else color("border.default", Palette.textMuted)
        Draw.fill(ui.g, r, edge)
        Draw.fill(ui.g, r.inset(if (selected) 2 else 1), color("bg.surface", Palette.surface))
        Draw.text(ui.g, Draw.fit(family.label, r.w - 12), r.x + 6, r.y + 6, color("text.primary", Palette.text))
        Draw.fill(ui.g, Rect(r.x + 6, r.bottom - 9, r.w - 12, 3), color("border.brass", Palette.brass))
        return hit && !selected
    }

    fun feelHeight() = cardHeight(line, line, line, line, line, label, CONTROL_H, label, CONTROL_H)

    fun feel(ui: Ui, box: Rect) {
        val p = UiPrefs.prefs
        ui.anchor("appearance:feel", box)
        val f = Flow(ui.card(box, tr("kami_libs.appearance.feel"), Icons.EYE), GAP)
        ui.toggle(f.take(line), !p.reduceMotion, tr("kami_libs.appearance.motion"), tip = tr("kami_libs.appearance.motion.tip"), key = "appearance-motion")?.let { on -> UiPrefs.change { it.reduceMotion = !on } }
        ui.toggle(f.take(line), p.sounds > 0f, tr("kami_libs.appearance.sound"), tip = tr("kami_libs.appearance.sound.tip"), key = "appearance-sound")?.let { on -> UiPrefs.change { it.sounds = if (on) 1f else 0f } }
        ui.toggle(f.take(line), p.detailedTooltips, tr("kami_libs.appearance.tooltips"), tip = tr("kami_libs.appearance.tooltips.tip"), key = "appearance-tooltips")?.let { on -> UiPrefs.change { it.detailedTooltips = on } }
        ui.toggle(f.take(line), p.autoRefresh, tr("kami_libs.appearance.refresh"), tip = tr("kami_libs.appearance.refresh.tip"), key = "appearance-refresh")?.let { on -> UiPrefs.change { it.autoRefresh = on } }
        ui.toggle(f.take(line), p.windowLarge, tr("kami_libs.appearance.window"), tip = tr("kami_libs.appearance.window.tip"), key = "appearance-window")?.let { on -> UiPrefs.change { it.windowLarge = on } }
        ui.fieldLabel(f.take(label), tr("kami_libs.appearance.volume"), Format.percent(p.sounds.toDouble()))
        ui.slider(f.take(CONTROL_H), p.sounds.toDouble(), 0.0, 1.0, 0.05, format = { Format.percent(it) }, key = "appearance-volume")
            ?.let { v -> dirty = true; UiPrefs.change(save = false) { it.sounds = v.toFloat() } }
        ui.fieldLabel(f.take(label), tr("kami_libs.appearance.delay"), "${Format.number(p.tooltipDelay)} ms")
        ui.slider(f.take(CONTROL_H), p.tooltipDelay.toDouble(), 0.0, 1500.0, 50.0, format = { "${Format.number(it.toLong())} ms" }, key = "appearance-delay")
            ?.let { v -> dirty = true; UiPrefs.change(save = false) { it.tooltipDelay = v.toInt() } }
    }

    fun previewHeight() = cardHeight(CONTROL_H, Metrics.px(8))

    fun preview(ui: Ui, box: Rect) {
        val f = Flow(ui.card(box, tr("kami_libs.appearance.preview"), Icons.SETTINGS), GAP)
        val row = f.take(CONTROL_H).columns(2, 8)
        if (ui.button(row[0], tr("kami_libs.appearance.preview.button"), style = ButtonStyle.PRIMARY, key = "appearance-sample")) progress = (progress + 0.17) % 1.0
        ui.toggle(row[1], sample, tr("kami_libs.appearance.preview.toggle"), key = "appearance-sample-toggle")?.let { sample = it }
        ui.progress(f.take(Metrics.px(8)), progress)
    }
}
