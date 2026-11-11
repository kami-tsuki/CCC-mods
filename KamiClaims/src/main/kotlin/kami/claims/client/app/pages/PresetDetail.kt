package kami.claims.client.app.pages

import kami.claims.gov.PresetId
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Option

object PresetDetail {
    private const val LABEL_H = 11
    private const val GAP = 4
    private val FULL = listOf("history", "core", "game", "strength", "tradeoff", "ranks")
    private val BRIEF = listOf("game", "ranks")

    fun options() = PresetId.entries.map { Option(it, name(it), null, tagline(it)) }

    fun name(preset: PresetId) = tr("kami_claims.preset.${preset.key}")

    fun tagline(preset: PresetId) = tr("kami_claims.preset.${preset.key}.tagline")

    private fun parts(preset: PresetId, full: Boolean) = (if (full) FULL else BRIEF).map { tr("kami_claims.preset.field.$it") to tr("kami_claims.preset.${preset.key}.$it") }

    fun height(w: Int, preset: PresetId, full: Boolean) =
        Draw.paragraphHeight(tagline(preset), w) + GAP + parts(preset, full).sumOf { LABEL_H + Draw.paragraphHeight(it.second, w) + GAP }

    fun draw(ui: Ui, x: Int, y: Int, w: Int, preset: PresetId, full: Boolean): Int {
        var cy = y + Draw.paragraph(ui.g, tagline(preset), x, y, w, Palette.brass) + GAP
        parts(preset, full).forEach { (label, text) ->
            Draw.text(ui.g, label, x, cy, TextStyle.LABEL)
            cy += LABEL_H + Draw.paragraph(ui.g, text, x, cy + LABEL_H, w, Palette.textSecondary) + GAP
        }
        return cy - y
    }
}
