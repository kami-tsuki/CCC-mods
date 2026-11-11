package kami.claims.client.admin

import kami.claims.admin.CountryDetail
import kami.claims.admin.CountryRequests
import kami.claims.client.app.GovernmentLevers
import kami.claims.client.app.wireEnum
import kami.claims.gov.PresetId
import kami.claims.gov.RESET_RANKS
import kami.claims.gov.Presets
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.DiffLine
import kami.libs.ui.widget.button
import kami.libs.ui.widget.checkbox
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.select

private const val PRESET_COLUMNS = 4
private const val CHECK_H = 12
private val PRESET_ROWS = (PresetId.entries.size + PRESET_COLUMNS - 1) / PRESET_COLUMNS

internal class GovernmentTab(kit: GovernanceKit) : GovernanceTab(kit, "gov-government") {
    private var current: Pair<PresetId, List<String>> = PresetId.MIXED to GovernmentLevers.values(Presets.levers(PresetId.MIXED))
    private var preset = current.first
    private var values = current.second
    private var reset = false

    override fun revision(d: CountryDetail): Any? = d.governance.government.let { listOf(it.preset, GovernmentLevers.values(it)) }

    override fun load(d: CountryDetail) {
        val gov = d.governance.government
        current = wireEnum(gov.preset, PresetId.CUSTOM) to GovernmentLevers.values(gov)
        discard()
    }

    private fun discard() {
        preset = current.first
        values = current.second
        reset = false
    }

    override fun lines(ui: Ui, f: Flow, d: CountryDetail) {
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.gov.preset"))
        presets(ui, f.take(PRESET_ROWS * CONTROL_H + (PRESET_ROWS - 1) * 4))
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.gov.levers"))
        levers(ui, f)
        ui.checkbox(f.take(CHECK_H), tr("kami_claims.admin.gov.reset_ranks"), reset, key = "gov-reset")?.let { reset = it }
        val diff = diff()
        ui.diffBlock(f, diff, "gov-diff")
        val row = Row(f.take(CONTROL_H), 4)
        val invalid = tr("kami_claims.admin.gov.nochange").takeIf { diff.isEmpty() && !reset }
        kit.button(ui, row, d, tr("kami_claims.admin.gov.apply"), Icons.SCALES, CountryRequests.GOV_SET, args(), tr("kami_claims.admin.gov.apply.title", d.row.name), invalid)
        if (ui.button(row.take(CONTROL_H * 4), tr("kami_claims.admin.gov.discard"), style = ButtonStyle.GHOST, enabled = diff.isNotEmpty() || reset, key = "gov-discard")) discard()
    }

    private fun presets(ui: Ui, r: Rect) {
        PresetId.entries.zip(r.grid(PRESET_COLUMNS, CONTROL_H, PresetId.entries.size, 4)).forEach { (id, cell) ->
            val style = if (id == preset) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY
            if (ui.button(cell, tr("kami_claims.preset.${id.key}"), style = style, key = "gov-preset:${id.key}")) {
                preset = id
                values = GovernmentLevers.values(Presets.levers(id))
            }
        }
    }

    private fun levers(ui: Ui, f: Flow) {
        GovernmentLevers.all.withIndex().chunked(2).forEach { pair ->
            f.take(LABELED_H).columns(2, FIELD_GAP).zip(pair).forEach { (cell, lever) -> pick(ui, cell, lever.index, lever.value.first) }
        }
    }

    private fun pick(ui: Ui, r: Rect, index: Int, lever: String) {
        ui.select(ui.labeled(r, GovernmentLevers.label(lever)), GovernmentLevers.options(lever), values[index], key = "gov-lever:$lever")?.let { values = GovernmentLevers.with(values, index, it) }
    }

    private fun diff(): List<DiffLine> {
        val presetLines = if (preset != current.first) DiffLine.change(tr("kami_claims.admin.gov.preset"), tr("kami_claims.preset.${current.first.key}"), tr("kami_claims.preset.${preset.key}")) else emptyList()
        val leverLines = GovernmentLevers.all.indices.filter { current.second[it] != values[it] }.flatMap {
            val lever = GovernmentLevers.all[it].first
            DiffLine.change(GovernmentLevers.label(lever), GovernmentLevers.valueLabel(lever, current.second[it]), GovernmentLevers.valueLabel(lever, values[it]))
        }
        return presetLines + leverLines
    }

    private fun args() = listOf(preset.key) + values + listOfNotNull(RESET_RANKS.takeIf { reset })
}
