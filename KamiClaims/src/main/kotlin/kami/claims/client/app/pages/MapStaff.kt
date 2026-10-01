package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Vocabulary
import kami.claims.client.rankOf
import kami.claims.client.store.ClaimsStore
import kami.claims.net.Detail
import kami.claims.service.View
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.SMALL_H
import kami.libs.ui.widget.TABLE_ROW_H
import kami.libs.ui.widget.disclosure
import kami.libs.ui.widget.select

class StaffPanel(private val page: ClaimsPage) {
    private class Worker(val id: String, val label: String, val width: Int, val blocked: Boolean, val tip: String, val key: String)
    private class Staff(val rows: List<Worker>, val count: String, val reason: String, val you: String, val none: String, val title: String, val assign: String, val nobody: String)

    private var open = false
    private val staffMemo = Memo()
    private val optionsMemo = Memo()

    fun close() { open = false }

    fun draw(ui: Ui, f: Flow, d: Detail, x: Int, z: Int, e: View.Entry?) {
        val job = page.snap.types.firstOrNull { it.name == d.type }?.job.orEmpty()
        if (job.isEmpty()) return
        val works = e != null && e.flags and View.ASSIGNED != 0
        val manage = d.relation == View.REL_MEMBER && page.can("jobs")
        if (!works && !manage) return
        val members = page.info?.members
        val staff = staffMemo.of(members, d, ClaimsStore.rank, Format.locale) {
            val byId = members.orEmpty().associateBy { it.id }
            val rows = d.workerIds.mapIndexed { i, id ->
                val name = d.workerNames.getOrElse(i) { id }
                val label = Draw.fit(name, NAME_W)
                Worker(id, label, Draw.width(label) + SMALL_H, byId[id]?.rank?.let(::rankOf)?.let { it >= ClaimsStore.rank } == true, tr("kami_libs.common.remove_x", name), "unassign:$id")
            }
            Staff(rows, Format.number(rows.size), tr("kami_claims.error.lower_ranks"), tr("kami_claims.map.work.you"), tr("kami_claims.map.work.none"), tr("kami_claims.common.workers"), tr("kami_claims.map.work.assign"), tr("kami_claims.map.work.nobody"))
        }
        f.skip(4)
        if (works) {
            val row = f.take(TABLE_ROW_H)
            val tx = row.x + Draw.leadIcon(ui.g, Icons.CHECK, row.x, row.centerY, Palette.textMuted) + 2
            Draw.text(ui.g, Draw.fit(staff.you, row.right - tx), tx, row.y + 3, Palette.textMuted)
        }
        if (!manage) return
        val options = optionsMemo.of(members, d, job, Format.locale) {
            members.orEmpty().filter { it.id !in d.workerIds }.map { m ->
                Option(m.id, m.name, Icons.PERSON, disabledReason = if (job in m.jobs) null else tr("kami_claims.map.work.needs", Vocabulary.job(job)))
            }
        }
        ui.select(f.take(CONTROL_H), options, null, staff.assign, options.isNotEmpty() && !page.pending("assign"), staff.nobody, key = "assign-pick")?.let {
            page.act("assign", it, x.toString(), z.toString())
        }
        f.skip(2)
        if (ui.disclosure(f.take(14), staff.title, open, staff.count, "work-fold", hairline = true)) open = !open
        if (!open) return
        if (staff.rows.isEmpty()) {
            val row = f.take(TABLE_ROW_H)
            Draw.text(ui.g, Draw.fit(staff.none, row.w), row.x, row.y + 3, Palette.textMuted)
            return
        }
        f.skip(2)
        chips(ui, f, staff, x, z)
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
            if (!w.blocked && ui.pressed(chip) != null) page.act("unassign", w.id, x.toString(), z.toString())
            cx += chip.w + 3
        }
    }
}

private const val NAME_W = 80
private const val CHIP_H = 13
