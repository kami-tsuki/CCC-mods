package kami.claims.client.admin

import kami.claims.admin.CountryDetail
import kami.claims.admin.CountryRequests
import kami.claims.net.SectorLine
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.COLOR_PICKER_H
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.colorPicker
import kami.libs.ui.widget.select
import kami.libs.ui.widget.textField

private const val NEW = "new"
private const val NO_MANAGER = ""
private const val CHUNK_TEXT_MAX = 1024
private const val WARNING_H = 10

internal class SectorsTab(kit: GovernanceKit) : GovernanceTab(kit, "gov-sectors") {
    private var selected = NEW
    private val name = TextState()
    private var color = 0x43A047
    private val chunks = TextState()
    private var manager = NO_MANAGER

    override fun revision(d: CountryDetail): Any? = d.governance.sectors.map { listOf(it.id, it.name, it.color, it.chunks, it.manager, it.fragmented) }

    override fun load(d: CountryDetail) {
        select(d.governance.sectors.firstOrNull { it.id == selected })
    }

    private fun select(sector: SectorLine?) {
        selected = sector?.id ?: NEW
        name.set(sector?.name.orEmpty())
        color = sector?.color ?: color
        chunks.set(sector?.chunks?.joinToString(",").orEmpty())
        manager = sector?.manager ?: NO_MANAGER
    }

    override fun lines(ui: Ui, f: Flow, d: CountryDetail) {
        val sectors = d.governance.sectors
        val sector = sectors.firstOrNull { it.id == selected }
        val options = sectors.map { Option(it.id, it.name) } + Option(NEW, tr("kami_claims.admin.sectors.new"))
        ui.select(ui.labeled(f.take(LABELED_H), tr("kami_claims.admin.sectors.list")), options, selected, key = "gov-sector-pick")?.let { id -> select(sectors.firstOrNull { it.id == id }) }
        if (sector?.fragmented == true) f.take(WARNING_H).let { Draw.text(ui.g, tr("kami_claims.admin.sectors.fragmented"), it.x, it.y, Palette.warning) }
        ui.textField(ui.labeled(f.take(LABELED_H), tr("kami_claims.admin.sectors.name")), name, maxLength = 32, key = "gov-sector-name")
        ui.colorPicker(f.take(COLOR_PICKER_H), color, "gov-sector-color")?.let { color = it }
        ui.textField(ui.labeled(f.take(LABELED_H), tr("kami_claims.admin.sectors.chunks")), chunks, tr("kami_claims.admin.sectors.chunks.placeholder"), maxLength = CHUNK_TEXT_MAX, key = "gov-sector-chunks")
        val managers = listOf(Option(NO_MANAGER, tr("kami_claims.admin.sectors.no_manager"))) + d.members.map { Option(it.uuid, it.name) }
        ui.select(ui.labeled(f.take(LABELED_H), tr("kami_claims.admin.sectors.manager")), managers, manager, key = "gov-sector-manager")?.let { manager = it }
        actions(ui, f, d, sector)
    }

    private fun actions(ui: Ui, f: Flow, d: CountryDetail, sector: SectorLine?) {
        val invalid = tr("kami_claims.admin.input_missing").takeIf { name.text.isBlank() || chunks.text.isBlank() }
        val row = Row(f.take(CONTROL_H))
        kit.button(ui, row, d, tr("kami_claims.admin.sectors.save"), Icons.EDIT, CountryRequests.SECTOR_SAVE, listOf(selected, name.text.trim(), color.toString(), chunks.text.trim(), manager),
            tr("kami_claims.admin.sectors.save.title", name.text.trim(), d.row.name), invalid)
        sector?.let { kit.button(ui, row, d, tr("kami_claims.admin.sectors.delete"), Icons.REMOVE, CountryRequests.SECTOR_DELETE, listOf(it.id), tr("kami_claims.admin.sectors.delete.title", it.name, d.row.name), danger = true) }
    }
}
