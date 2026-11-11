package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.map.ChunkCells
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.store.ClaimsStore
import kami.claims.gov.Perm
import kami.claims.gov.SectorRules
import kami.claims.net.Info
import kami.claims.net.SectorLine
import kami.claims.service.View
import kami.claims.war.ChunkRef
import kami.libs.ui.app.Consequence
import kami.libs.ui.core.Dim
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

class SectorEditor(private val page: ClaimsPage, private val planner: MapPlanner) {
    private class Check(val refs: List<String>, val issue: String?)

    private var editing: SectorLine? = null
    private val nameField = TextState()
    private var color = DEFAULT_COLORS.first()
    private var manager = ""
    private val checkMemo = Memo()
    private val managerMemo = Memo()

    private val dim get() = Dim.current()

    init { page.onSuccess(SAVE_KEY, planner::end) }

    fun startNew() {
        editing = null
        nameField.set("")
        color = DEFAULT_COLORS[(page.info?.sectors?.size ?: 0) % DEFAULT_COLORS.size]
        manager = ""
    }

    fun load(line: SectorLine) {
        editing = line
        nameField.set(line.name)
        color = line.color
        manager = line.manager
    }

    private fun workChunk(key: Long) = ChunkCells.cell(key).let { (x, z) ->
        ClientClaims.at(dim, x, z)?.let { it.flags and View.OWN != 0 && ClientClaims.typeName(it) in SectorRules.workTypes } == true
    }

    private fun check() = checkMemo.of(planner.selectionRev, ClaimsStore.revision, dim, Format.locale) {
        val chunks = planner.selection.filter(::workChunk)
        val issue = when {
            chunks.size < SectorRules.MIN_CHUNKS -> tr("kami_claims.map.sector.issue.few", SectorRules.MIN_CHUNKS)
            chunks.size > SectorRules.MAX_CHUNKS -> tr("kami_claims.map.sector.issue.many", SectorRules.MAX_CHUNKS)
            !MapModel.connected(chunks.toSet()) -> tr("kami_claims.map.sector.issue.split")
            else -> null
        }
        Check(chunks.map { k -> ChunkCells.cell(k).let { (x, z) -> ChunkRef(dim, x, z).wire() } }, issue)
    }

    private fun managers(info: Info) = managerMemo.of(info.members, Format.locale) {
        listOf(Option("", tr("kami_libs.common.none"), Icons.PERSON)) + info.members.map { Option(it.id, it.name, Icons.PERSON) }
    }

    fun body(ui: Ui, f: Flow) {
        val info = page.info ?: return
        val check = check()
        SectorDraft.chunks = check.refs
        ui.fieldLabel(f.take(9), tr("kami_claims.map.sector.name"))
        ui.textField(f.take(CONTROL_H), nameField, tr("kami_claims.map.sector.name.hint"), maxLength = NameCheck.max(page.limits), allow = NameCheck::allows, key = "sector-name")
        ui.fieldLabel(f.take(9), tr("kami_claims.map.sector.color"))
        ui.colorPicker(f.take(COLOR_PICKER_H), color, "sector-color")?.let { color = it }
        ui.fieldLabel(f.take(9), tr("kami_claims.map.sector.manager"))
        ui.select(f.take(CONTROL_H), managers(info), manager, key = "sector-manager")?.let { manager = it }
        f.skip(2)
        ui.property(f.take(11), tr("kami_claims.map.sector.valid"), tr("kami_libs.format.of", Format.number(check.refs.size), Format.number(planner.selection.size)))
        check.issue?.let { issue ->
            val row = f.take(14)
            ui.chip(row.x, row.y, issue, Palette.warning, Icons.WARNING, maxWidth = row.w, key = "sector-issue")
        }
        f.skip(2)
        buttons(ui, f, info, check)
    }

    private fun buttons(ui: Ui, f: Flow, info: Info, check: Check) {
        val existing = editing
        val name = nameField.text.trim()
        val reason = when {
            page.lock(Perm.SECTORS) != null -> page.lock(Perm.SECTORS)
            existing == null && info.sectors.size >= info.sectorCap -> tr("kami_claims.map.sector.limit")
            check.issue != null -> check.issue
            name.isEmpty() -> tr("kami_claims.map.sector.name.missing")
            else -> NameCheck.problem(name, page.limits) ?: colorProblem(color)
        }
        val label = tr(if (existing == null) "kami_claims.map.sector.create" else "kami_claims.map.sector.set_chunks")
        if (ui.button(f.take(CONTROL_H), label, Icons.CHECK, ButtonStyle.PRIMARY, reason == null, reason, pending = page.pending(SAVE_KEY), key = "sector-save")) {
            page.act("sector_save", existing?.id ?: "new", name, color.toString(), check.refs.joinToString(","), manager.ifEmpty { "-" }, key = SAVE_KEY)
        }
        if (existing != null) {
            f.skip(2)
            if (ui.button(f.take(CONTROL_H), tr("kami_claims.map.sector.delete"), Icons.REMOVE, ButtonStyle.DANGER, page.can(Perm.SECTORS), page.lock(Perm.SECTORS), pending = page.pending("sector_delete"), key = "sector-delete")) {
                Dialogs.confirm(
                    page.app, tr("kami_claims.map.sector.delete.title", existing.name), null, Icons.REMOVE,
                    listOf(Consequence(tr("kami_claims.map.sector.delete.jobs"), Severity.WARNING)),
                    tr("kami_claims.map.sector.delete"), "sector_delete", arrayOf(existing.id), danger = true, after = planner::end
                )
            }
        }
        f.skip(2)
        if (ui.button(f.take(CONTROL_H), tr("kami_libs.common.close"), Icons.CROSS, ButtonStyle.SECONDARY, key = "sector-close")) planner.end()
    }
}

private const val SAVE_KEY = "sector_save:map"
