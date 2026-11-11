package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Vocabulary
import kami.claims.gov.Perm
import kami.claims.gov.SectorRules
import kami.claims.net.Info
import kami.claims.net.SectorLine
import kami.claims.war.ChunkRef
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Route
import kami.libs.ui.app.formDialog
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

private class SectorEdit(var base: SectorLine) {
    val name = TextState(base.name)
    var color = base.color
    var manager = base.manager
    val chunks = base.chunks.toMutableList()

    fun differs(line: SectorLine) = name.text.trim() != line.name || color != line.color || manager != line.manager || chunks != line.chunks

    fun args(): Array<String> = arrayOf(base.id, name.text.trim(), color.toString(), chunks.joinToString(","), manager.ifEmpty { "-" })
}

class SectorsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.sectors.title")
    override val help get() = listOf(
        Callout("sectors:list", tr("kami_claims.sectors.title"), tr("kami_claims.sectors.help.list.desc")),
        Callout("sectors:detail", tr("kami_claims.sectors.editor"), tr("kami_claims.sectors.help.detail.desc"))
    )
    private val columns = Memo()
    private val layout = MasterDetail(0.45, 170, 270)
    private val table = TableState<SectorLine>()
    private val edits = HashMap<String, SectorEdit>()
    private var contentH = DETAIL_ESTIMATE

    private fun canEdit(info: Info) = JobsView.can(info, Perm.SECTORS)

    init { onSuccess("sector_save") { SectorDraft.chunks = emptyList() } }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        table.selected.removeAll { id -> info.sectors.none { it.id == id } }
        layout.draw(ui, r, app.compact, table.selected, { list(ui, it, info) }, { detail(ui, it, info) })
    }

    private fun list(ui: Ui, r: Rect, info: Info) {
        ui.anchor("sectors:list", r)
        var area = r
        if (canEdit(info)) {
            newButton(ui, r.top(CONTROL_H), info)
            area = r.dropTop(CONTROL_H, 6)
        }
        val rows = columns.of(info, app.compact, Format.locale) { sectorColumns(info) }
        ui.table(area, rows, info.sectors, table, { it.id }, emptyText = tr("kami_claims.sectors.empty"), key = "sectors-table", severity = { if (it.fragmented) Severity.WARNING else null })
    }

    private fun sectorColumns(info: Info): List<Column<SectorLine>> = listOfNotNull(
        Column<SectorLine>(tr("kami_libs.common.name"), -1, sort = textOrder { it.name }) { _, c, s ->
            Draw.fill(g, Rect(c.x, c.centerY - 3, 6, 6), JobsView.opaque(s.color))
            fitText(c.x + 10, c.y + 3, c.w - 10, s.name)
        },
        Column.text<SectorLine>(tr("kami_claims.sectors.chunks"), 90, color = { Palette.textSecondary }) { typeSummary(it) }.takeUnless { app.compact },
        Column.text<SectorLine>(tr("kami_claims.sectors.manager"), 70, color = { Palette.textSecondary }) { if (it.manager.isEmpty()) "-" else JobsView.nameOf(info, it.manager) },
        Column<SectorLine>(tr("kami_claims.sectors.jobs"), 40, Align.RIGHT, compareBy { boundJobs(info, it) }) { _, c, s -> Draw.textRight(g, Format.number(boundJobs(info, s)), c.right, c.y + 3, Palette.textMuted) },
        Column<SectorLine>(tr("kami_libs.common.status"), 60) { _, c, s -> if (s.fragmented) fitText(c.x, c.y + 3, c.w, tr("kami_claims.sectors.fragmented"), Palette.warning) }
    )

    private fun boundJobs(info: Info, s: SectorLine) = info.jobSpecs.count { it.scope == "sector" && it.scopeRef == s.id }

    private fun typeOf(chunk: String): String? = ChunkRef.parse(chunk)?.let { ref -> ClientClaims.at(ref.dim, ref.x, ref.z)?.let { ClientClaims.typeName(it) } }

    private fun typeSummary(s: SectorLine) = s.chunks.groupingBy { typeOf(it) ?: "?" }.eachCount().entries.joinToString(", ") { "${Vocabulary.type(it.key).label} ${it.value}" }

    private fun newButton(ui: Ui, r: Rect, info: Info) {
        val reason = newReason(info)
        if (ui.button(r, tr("kami_claims.sectors.new"), Icons.ADD, ButtonStyle.PRIMARY, reason == null, reason, key = "sector-new")) newDialog(info)
    }

    private fun newReason(info: Info): String? = when {
        info.sectors.size >= info.sectorCap -> tr("kami_claims.sectors.reason.cap", info.sectorCap)
        SectorDraft.chunks.size !in SectorRules.MIN_CHUNKS..SectorRules.MAX_CHUNKS -> tr("kami_claims.sectors.reason.size", SectorRules.MIN_CHUNKS, SectorRules.MAX_CHUNKS, SectorDraft.chunks.size)
        else -> null
    }

    private fun newDialog(info: Info) {
        val name = TextState()
        var color = DEFAULT_COLOR
        var manager = ""
        val chunks = SectorDraft.chunks
        fun problem() = NameCheck.problem(name.text, limits) ?: colorProblem(color)
        formDialog({ app.open(it) }, tr("kami_claims.sectors.new"), tr("kami_claims.sectors.new.subtitle", chunks.size), Icons.AREA, tr("kami_claims.sectors.create"),
            { problem() == null }, { problem().orEmpty() },
            { act("sector_save", "new", name.text.trim(), color.toString(), chunks.joinToString(","), manager.ifEmpty { "-" }, key = "sector_save") }
        ) { r ->
            val s = Stack(r.x, r.y, r.w)
            fieldLabel(s.take(LABEL_H), tr("kami_libs.common.name"))
            textField(s.take(CONTROL_H), name, tr("kami_libs.common.name"), Icons.EDIT, maxLength = NameCheck.max(limits), allow = NameCheck::allows, key = "sector-new:name", autoFocus = true)
            fieldLabel(s.take(LABEL_H), tr("kami_claims.jobs.field.color"))
            colorPicker(s.take(COLOR_PICKER_H), color, "sector-new:color")?.let { color = it }
            fieldLabel(s.take(LABEL_H), tr("kami_claims.sectors.manager"))
            select(s.take(CONTROL_H), managerOptions(info), manager, tr("kami_libs.common.none"), key = "sector-new:manager")?.let { manager = it }
            s.bottom - r.y
        }
    }

    private fun managerOptions(info: Info) = listOf(Option("", tr("kami_libs.common.none"))) + info.members.map { Option(it.id, it.name) }

    private fun detail(ui: Ui, r: Rect, info: Info) {
        val line = info.sectors.firstOrNull { it.id in table.selected } ?: info.sectors.firstOrNull()
        if (line == null) {
            ui.emptyState(r, tr("kami_claims.sectors.empty"), tr("kami_claims.sectors.empty.desc"))
            return
        }
        panel(ui, r, info, line, editFor(line))
    }

    private fun editFor(line: SectorLine): SectorEdit {
        val edit = edits[line.id] ?: return SectorEdit(line).also { edits[line.id] = it }
        if (!edit.differs(line)) edit.base = line
        else if (!edit.differs(edit.base)) return SectorEdit(line).also { edits[line.id] = it }
        return edit
    }

    private fun panel(ui: Ui, r: Rect, info: Info, line: SectorLine, edit: SectorEdit) {
        ui.panel(r)
        ui.anchor("sectors:detail", r)
        val canEdit = canEdit(info)
        val inner = r.inset(8)
        val staged = canEdit && edit.differs(line)
        val area = if (staged) inner.dropBottom(BAR_H, 6) else inner
        ui.scroll("sector-detail:${line.id}", area, contentH) { content ->
            val s = Stack(content.x, content.y, content.w)
            header(ui, s, line, edit)
            fields(ui, s, info, edit, canEdit)
            chunks(ui, s, edit, canEdit)
            if (canEdit) delete(ui, s, info, line)
            contentH = s.bottom - content.y
        }
        if (staged) stagedBar(ui, inner.bottom(BAR_H), line, edit)
    }

    private fun header(ui: Ui, s: Stack, line: SectorLine, edit: SectorEdit) {
        val row = Row(s.take(HEADER_H), 4)
        Draw.fill(ui.g, Rect(row.take(6).x, row.rest.y + 5, 6, 6), JobsView.opaque(edit.color))
        val center = center(line)
        if (ui.edgeButton(row, tr("kami_claims.sectors.show"), Icons.MAP, enabled = center != null, key = "sector:${line.id}:map")) center?.let { app.openMapAt(it.first, it.second, select = false) }
        ui.fitText(row.rest.x, row.rest.y + 3, row.rest.w, line.name, style = TextStyle.HEADING, key = "sector-head")
        if (line.fragmented) ui.fitText(s.x, s.take(HINT_H).y, s.w, tr("kami_claims.sectors.fragmented.desc"), Palette.warning, key = "sector-fragmented")
    }

    private fun center(line: SectorLine): Pair<Int, Int>? {
        val points = line.chunks.mapNotNull { ChunkRef.parse(it) }
        if (points.isEmpty()) return null
        return points.sumOf { it.x } / points.size to points.sumOf { it.z } / points.size
    }

    private fun fields(ui: Ui, s: Stack, info: Info, edit: SectorEdit, canEdit: Boolean) {
        val key = "sector:${edit.base.id}"
        ui.fieldLabel(s.take(LABEL_H), tr("kami_libs.common.name"))
        ui.textField(s.take(CONTROL_H), edit.name, tr("kami_libs.common.name"), Icons.EDIT, canEdit, NameCheck.max(limits), NameCheck::allows, key = "$key:name")
        ui.fieldLabel(s.take(LABEL_H), tr("kami_claims.jobs.field.color"))
        ui.colorPicker(s.take(COLOR_PICKER_H), edit.color, "$key:color", canEdit)?.let { edit.color = it }
        ui.fieldLabel(s.take(LABEL_H), tr("kami_claims.sectors.manager"))
        ui.select(s.take(CONTROL_H), managerOptions(info), edit.manager, tr("kami_libs.common.none"), canEdit, key = "$key:manager")?.let { edit.manager = it }
    }


    private fun chunkLabel(chunk: String) = ChunkRef.parse(chunk)?.let { "${it.x}, ${it.z}" } ?: chunk

    private fun chunks(ui: Ui, s: Stack, edit: SectorEdit, canEdit: Boolean) {
        ui.section(s, tr("kami_claims.sectors.chunks"), tr("kami_libs.format.of", Format.number(edit.chunks.size), Format.number(SectorRules.MAX_CHUNKS)))
        val reason = if (edit.chunks.size <= SectorRules.MIN_CHUNKS) tr("kami_claims.sectors.reason.min", SectorRules.MIN_CHUNKS) else null
        edit.chunks.toList().forEach { chunk ->
            val row = Row(s.take(TABLE_ROW_H), 4)
            if (canEdit && ui.iconButton(row.takeFromRight(SMALL_H), Icons.REMOVE, tr("kami_libs.common.remove"), reason == null, disabledReason = reason, key = "sector:${edit.base.id}:$chunk:remove")) edit.chunks.remove(chunk)
            val look = Vocabulary.type(typeOf(chunk) ?: "")
            Draw.fill(ui.g, Rect(row.take(6).x, row.rest.y + 4, 6, 6), look.color)
            ui.fitText(row.rest.x, row.rest.y + 3, row.rest.w, "${chunkLabel(chunk)} · ${look.label}", key = "sector:${edit.base.id}:$chunk")
        }
        ui.fitText(s.x, s.take(HINT_H).y, s.w, tr("kami_claims.sectors.chunks.hint"), Palette.textMuted, key = "sector-chunk-hint")
    }

    private fun delete(ui: Ui, s: Stack, info: Info, line: SectorLine) {
        if (ui.button(s.take(CONTROL_H), tr("kami_claims.sectors.delete"), Icons.REMOVE, ButtonStyle.DANGER, key = "sector:${line.id}:delete")) confirmDelete(line, boundJobs(info, line))
    }

    private fun confirmDelete(line: SectorLine, jobs: Int) = Dialogs.confirm(
        app, tr("kami_claims.sectors.delete.title", line.name), null, Icons.REMOVE,
        listOf(Consequence(tr("kami_claims.sectors.delete.jobs", jobs), Severity.WARNING)),
        tr("kami_claims.sectors.delete"), "sector_delete", arrayOf(line.id), danger = true, key = "sector_delete:${line.id}",
        after = { edits.remove(line.id); table.selected.clear() }
    )

    private fun stagedBar(ui: Ui, r: Rect, line: SectorLine, edit: SectorEdit) {
        val reason = NameCheck.problem(edit.name.text, limits) ?: colorProblem(edit.color)
        ui.stagedBar(r, edit.name.text.trim(), reason == null, reason, pending("sector_save:${line.id}"), "sector:${line.id}", { send(edit) }, { edits.remove(line.id) })
    }

    private fun send(edit: SectorEdit) = act("sector_save", *edit.args(), key = "sector_save:${edit.base.id}")

    override fun leaving(next: Route): Boolean {
        val info = info
        val dirty = info?.sectors.orEmpty().mapNotNull { l -> edits[l.id]?.takeIf { it.differs(l) } }
        val save: (() -> Unit)? = if (info != null && canEdit(info)) { { dirty.forEach(::send) } } else null
        return guardLeave(next, dirty.isNotEmpty(), save) { edits.clear() } && super.leaving(next)
    }

    private companion object {
        const val LABEL_H = 9
        const val BAR_H = 28
        const val HEADER_H = 16
        const val HINT_H = 10
        const val DETAIL_ESTIMATE = 400
        const val DEFAULT_COLOR = 0x43A047
    }
}
