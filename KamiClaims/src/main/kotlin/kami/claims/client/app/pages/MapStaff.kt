package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.ClientLocks
import kami.claims.client.map.SectorShapes
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.restricted
import kami.claims.gov.Perm
import kami.claims.net.Detail
import kami.claims.net.JobSpecLine
import kami.claims.service.View
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Dim
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.SMALL_H
import kami.libs.ui.widget.TABLE_ROW_H
import kami.libs.ui.widget.clickable
import kami.libs.ui.widget.disclosure
import kami.libs.ui.widget.section
import kami.libs.ui.widget.select

class StaffPanel(private val page: ClaimsPage, private val planner: MapPlanner) {
    private class Worker(val id: String, val label: String, val width: Int, val blocked: Boolean, val tip: String, val key: String)
    private class Staff(val rows: List<Worker>, val count: String, val reason: String, val you: String, val none: String, val title: String, val assign: String, val nobody: String)
    private class Cover(val sectors: List<SectorShapes.Shape>, val jobs: List<JobSpecLine>, val holders: Set<String>)

    private var open = false
    private val coverMemo = Memo()
    private val staffMemo = Memo()
    private val optionsMemo = Memo()

    fun close() { open = false }

    fun draw(ui: Ui, f: Flow, d: Detail, x: Int, z: Int, e: View.Entry?) {
        val info = page.info ?: return
        if (d.relation != View.REL_MEMBER) return
        val dim = Dim.current()
        val cover = coverMemo.of(ClaimsStore.revision, d, dim) {
            val sectors = SectorShapes.at(dim, x, z)
            val jobs = SectorShapes.covering(info, d.type, sectors)
            Cover(sectors, jobs, jobs.flatMapTo(HashSet()) { it.holders })
        }
        val works = e != null && e.flags and View.ASSIGNED != 0
        val manage = cover.jobs.isNotEmpty() && page.can(Perm.ASSIGN_CHUNKS)
        if (cover.sectors.isEmpty() && cover.jobs.isEmpty() && !works) return
        f.skip(4)
        if (cover.sectors.isNotEmpty()) sectorRows(ui, f, cover.sectors)
        if (cover.jobs.isNotEmpty()) jobRows(ui, f, cover.jobs)
        val members = info.members
        val staff = staffMemo.of(members, d, info.rank, Format.locale) {
            val byId = members.associateBy { it.id }
            val rows = d.workerIds.mapIndexed { i, id ->
                val name = d.workerNames.getOrElse(i) { id }
                val label = Draw.fit(name, NAME_W)
                val target = byId[id]
                val blocked = target != null && target.id != page.snap.me && !ClaimsStore.outranks(target.rank)
                Worker(id, label, Draw.width(label) + SMALL_H, blocked, tr("kami_libs.common.remove_x", name), "unassign:$id")
            }
            Staff(rows, Format.number(rows.size), tr("kami_claims.error.rank_below"), tr("kami_claims.map.work.you"), tr("kami_claims.map.work.none"), tr("kami_claims.common.workers"), tr("kami_claims.map.work.assign"), tr("kami_claims.map.work.nobody"))
        }
        if (works) {
            val row = f.take(TABLE_ROW_H)
            val tx = row.x + Draw.leadIcon(ui.g, Icons.CHECK, row.x, row.centerY, Palette.textMuted) + 2
            ui.fitText(tx, row.y + 3, row.right - tx, staff.you, Palette.textMuted)
        }
        if (!page.can(Perm.ASSIGN_CHUNKS)) return
        if (manage) {
            val options = optionsMemo.of(members, d, cover, Format.locale) {
                members.filter { it.id in cover.holders && it.id !in d.workerIds }.map { m ->
                    Option(m.id, m.name, Icons.PERSON, cover.jobs.filter { m.id in it.holders }.joinToString { it.name })
                }
            }
            ui.select(f.take(CONTROL_H), options, null, staff.assign, options.isNotEmpty() && !page.pending("assign"), staff.nobody, key = "assign-pick")?.let {
                page.act("assign", it, x.toString(), z.toString(), Dim.current())
            }
            f.skip(2)
        } else if (info.restricted(Perm.VIEW_JOBS)) {
            ui.select(f.take(CONTROL_H), emptyList(), null, staff.assign, false, ClientLocks.perm(Perm.VIEW_JOBS), key = "assign-pick")
            f.skip(2)
        }
        if (ui.disclosure(f.take(14), staff.title, open, staff.count, "work-fold", hairline = true)) open = !open
        if (!open) return
        if (staff.rows.isEmpty()) {
            val row = f.take(TABLE_ROW_H)
            ui.fitText(row.x, row.y + 3, row.w, staff.none, Palette.textMuted)
            return
        }
        f.skip(2)
        chips(ui, f, staff, x, z)
    }

    private fun sectorRows(ui: Ui, f: Flow, sectors: List<SectorShapes.Shape>) {
        ui.section(f.take(14), tr("kami_claims.map.sector.here"))
        val editable = page.can(Perm.SECTORS)
        sectors.forEach { s ->
            val row = f.take(TABLE_ROW_H)
            val line = s.line
            Draw.fill(ui.g, Rect(row.x, row.y + 3, 5, 7), Palette.opaque(line.color))
            ui.fitText(row.x + 9, row.y + 3, row.w - 9, line.name, if (line.fragmented) Palette.warning else Palette.textSecondary)
            val tip = if (line.fragmented) tr("kami_claims.map.sector.fragmented") else tr("kami_claims.map.sector.edit")
            ui.tooltip("sector:${line.id}", row, tip)
            if (ui.clickable("sector-row:${line.id}", row, editable)) planner.editSector(line)
        }
    }

    private fun jobRows(ui: Ui, f: Flow, jobs: List<JobSpecLine>) {
        ui.section(f.take(14), tr("kami_claims.map.sector.jobs"))
        jobs.forEach { job ->
            val row = f.take(TABLE_ROW_H)
            Draw.fill(ui.g, Rect(row.x, row.y + 3, 5, 7), Palette.opaque(job.color))
            ui.fitText(row.x + 9, row.y + 3, row.w - 9, job.name, Palette.textSecondary)
        }
    }

    private fun chips(ui: Ui, f: Flow, staff: Staff, x: Int, z: Int) {
        var row = f.take(CHIP_H)
        var cx = row.x
        staff.rows.forEach { w ->
            if (cx > row.x && cx + w.width > row.right) { f.skip(1); row = f.take(CHIP_H); cx = row.x }
            val chip = Rect(cx, row.y, w.width.coerceAtMost(row.w), CHIP_H)
            val hover = !w.blocked && ui.hovering(chip)
            Draw.fill(ui.g, chip, Palette.alpha(Palette.border, if (hover) 0xA0 else 0x50))
            Draw.text(ui.g, w.label, chip.x + 4, chip.y + 3, if (w.blocked) Palette.textMuted else Palette.textSecondary)
            Draw.leadIcon(ui.g, Icons.CROSS, chip.right - SMALL_H + 3, chip.centerY, if (hover) Palette.danger else Palette.textMuted)
            if (hover) ui.cursor = Cursor.HAND
            ui.tooltip(w.key, chip, if (w.blocked) staff.reason else w.tip)
            if (!w.blocked && ui.pressed(chip) != null) page.act("unassign", w.id, x.toString(), z.toString(), Dim.current())
            cx += chip.w + 3
        }
    }
}

private const val NAME_W = 80
private const val CHIP_H = 13
