package kami.claims.client.admin

import kami.claims.client.app.permsOf
import kami.claims.client.app.pages.NameCheck
import kami.claims.admin.CountryDetail
import kami.claims.admin.CountryRequests
import kami.claims.gov.CITIZEN
import kami.claims.gov.PRESIDENT
import kami.claims.gov.Perm
import kami.claims.gov.RankRules
import kami.claims.net.RankLine
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
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
import kami.libs.ui.widget.clickable
import kami.libs.ui.widget.colorPicker
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.reorderList
import kami.libs.ui.widget.select
import kami.libs.ui.widget.textField

private const val RANK_ROW_H = 20
private const val OPAQUE = 0xFF000000.toInt()
private const val DOT = 8

internal class RanksTab(kit: GovernanceKit) : GovernanceTab(kit, "gov-ranks") {
    private var selected: String? = null
    private var base: RankLine? = null
    private val name = TextState()
    private val plots = TextState()
    private var color = 0
    private val perms = mutableSetOf<Perm>()
    private var target: String? = null
    private var member: String? = null
    private var dest: String? = null
    private val newName = TextState()
    private var newColor = 0x1E88E5

    override fun revision(d: CountryDetail): Any? = d.governance.ranks.map { listOf(it.id, it.name, it.color, it.position, it.perms, it.plots) }

    override fun load(d: CountryDetail) {
        val rank = d.governance.ranks.firstOrNull { it.id == selected }
        if (rank != null && (base?.let(::matches) != false || matches(rank))) select(rank)
        target = null
    }

    private fun matches(rank: RankLine) = name.text == rank.name && plots.text == rank.plots.toString() && color == rank.color && perms == permsOf(rank.perms)

    private fun select(rank: RankLine) {
        selected = rank.id
        base = rank
        name.set(rank.name)
        plots.set(rank.plots.toString())
        color = rank.color
        perms.clear()
        perms += permsOf(rank.perms)
    }

    override fun lines(ui: Ui, f: Flow, d: CountryDetail) {
        val ranks = d.governance.ranks
        val rank = ranks.firstOrNull { it.id == selected } ?: ranks.firstOrNull()?.also(::select) ?: return
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.ranks.list"))
        list(ui, f, d, ranks, rank)
        editor(ui, f, d, rank)
        permissions(ui, f, d, rank)
        removal(ui, f, d, ranks, rank)
        assignment(ui, f, d, ranks)
        creation(ui, f, d)
    }

    private fun list(ui: Ui, f: Flow, d: CountryDetail, ranks: List<RankLine>, rank: RankLine) {
        val moved = ui.reorderList(f.take(ranks.size * RANK_ROW_H), ranks.size, RANK_ROW_H, { it == 0 || it == ranks.lastIndex }, key = "gov-ranks-list") { i, r ->
            val line = ranks[i]
            if (clickable("gov-rank:${line.id}", r)) select(line)
            if (line.id == rank.id) Draw.fill(g, r, Palette.selected)
            Draw.fill(g, Rect(r.x + 2, r.y + (r.h - DOT) / 2, DOT, DOT), line.color or OPAQUE)
            Draw.textRight(g, tr("kami_claims.admin.ranks.members", line.members, line.plots), r.right - 2, Draw.textY(r), Palette.textSecondary)
            fitText(r.x + DOT + 8, Draw.textY(r), r.w - DOT - 70, line.name, key = "gov-rank-name:${line.id}")
        }
        moved?.let { (from, to) -> kit.send(d, CountryRequests.RANK_MOVE, listOf(ranks[from].id, to.toString()), tr("kami_claims.admin.ranks.move.title", ranks[from].name, d.row.name)) }
    }

    private fun editor(ui: Ui, f: Flow, d: CountryDetail, rank: RankLine) {
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.ranks.edit", rank.name))
        ui.textField(f.take(CONTROL_H), name, tr("kami_claims.admin.ranks.name"), maxLength = NameCheck.max(limits), key = "gov-rank-name")
        ui.colorPicker(f.take(COLOR_PICKER_H), color, "gov-rank-color")?.let { color = it }
        kit.button(ui, Row(f.take(CONTROL_H)), d, tr("kami_claims.admin.ranks.save"), Icons.EDIT, CountryRequests.RANK_EDIT, listOf(rank.id, name.text.trim(), color.toString()),
            tr("kami_claims.admin.ranks.edit.title", rank.name, d.row.name), NameCheck.problem(name.text, limits))
        val row = Row(f.take(CONTROL_H))
        val count = plots.text.trim().toIntOrNull()
        kit.button(ui, row, d, tr("kami_claims.admin.ranks.plots.save"), Icons.EDIT, CountryRequests.RANK_PLOTS, listOf(rank.id, count.toString()),
            tr("kami_claims.admin.ranks.plots.title", rank.name, d.row.name), missing(count == null))
        ui.textField(row.remaining(), plots, tr("kami_claims.admin.ranks.plots"), maxLength = 4, allow = { it.isDigit() }, key = "gov-rank-plots")
        if ((plots.text.toIntOrNull() ?: 0) > RankRules.MAX_PLOTS) plots.set(RankRules.MAX_PLOTS.toString())
    }

    private fun permissions(ui: Ui, f: Flow, d: CountryDetail, rank: RankLine) {
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.ranks.perms"))
        val fixed = rank.id == PRESIDENT
        ui.permissionMatrix(f, perms, Perm.entries, !fixed && kit.lock(d) == null, if (fixed) tr("kami_claims.admin.ranks.president_all") else kit.lock(d), "gov-rank-perms")
        val diff = permDiff(permsOf(rank.perms), perms)
        ui.diffBlock(f, diff, "gov-rank-diff")
        kit.button(ui, Row(f.take(CONTROL_H)), d, tr("kami_claims.admin.ranks.perms.apply"), Icons.LOCK, CountryRequests.RANK_PERMS,
            listOf(rank.id, perms.joinToString(",") { it.name }), tr("kami_claims.admin.ranks.perms.title", rank.name, d.row.name),
            tr("kami_claims.admin.gov.nochange").takeIf { diff.isEmpty() || fixed })
    }

    private fun removal(ui: Ui, f: Flow, d: CountryDetail, ranks: List<RankLine>, rank: RankLine) {
        val others = ranks.filter { it.id != rank.id && it.id != PRESIDENT }
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.ranks.delete"))
        val chosen = target?.takeIf { t -> others.any { it.id == t } }
        ui.select(f.take(CONTROL_H), others.map { Option(it.id, it.name) }, chosen, tr("kami_claims.admin.ranks.delete.target"), key = "gov-rank-target")?.let { target = it }
        val locked = tr("kami_claims.admin.ranks.delete.fixed").takeIf { rank.id == PRESIDENT || rank.id == CITIZEN } ?: missing(rank.members > 0 && chosen == null)
        kit.button(ui, Row(f.take(CONTROL_H)), d, tr("kami_claims.admin.ranks.delete"), Icons.REMOVE, CountryRequests.RANK_DELETE, listOfNotNull(rank.id, chosen),
            tr("kami_claims.admin.ranks.delete.title", rank.name, d.row.name), locked, danger = true)
    }

    private fun assignment(ui: Ui, f: Flow, d: CountryDetail, ranks: List<RankLine>) {
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.ranks.assign"))
        val row = Row(f.take(CONTROL_H))
        val picked = d.members.firstOrNull { it.uuid == member }
        val rank = ranks.firstOrNull { it.id == dest }
        kit.button(ui, row, d, tr("kami_claims.admin.ranks.assign"), Icons.PEOPLE, CountryRequests.RANK_ASSIGN, listOfNotNull(member, dest),
            tr("kami_claims.admin.ranks.assign.title", picked?.name.orEmpty(), rank?.name.orEmpty(), d.row.name),
            tr("kami_claims.admin.input_missing").takeIf { picked == null || rank == null } ?: tr("kami_claims.admin.ranks.assign.president").takeIf { picked?.rankId == PRESIDENT })
        val cells = row.remaining().columns(2, 4)
        ui.select(cells[0], d.members.map { Option(it.uuid, it.name) }, member, tr("kami_claims.admin.leader.placeholder"), key = "gov-rank-member")?.let { member = it }
        ui.select(cells[1], ranks.filter { it.id != PRESIDENT }.map { Option(it.id, it.name) }, dest, tr("kami_claims.admin.ranks.assign.rank"), key = "gov-rank-dest")?.let { dest = it }
    }

    private fun creation(ui: Ui, f: Flow, d: CountryDetail) {
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.ranks.create"))
        ui.textField(f.take(CONTROL_H), newName, tr("kami_claims.admin.ranks.name"), maxLength = NameCheck.max(limits), key = "gov-rank-new")
        ui.colorPicker(f.take(COLOR_PICKER_H), newColor, "gov-rank-new-color")?.let { newColor = it }
        val full = tr("kami_claims.admin.ranks.cap", d.governance.ranks.size, d.governance.rankCap).takeIf { d.governance.ranks.size >= d.governance.rankCap }
        kit.button(ui, Row(f.take(CONTROL_H)), d, tr("kami_claims.admin.ranks.create"), Icons.ADD, CountryRequests.RANK_CREATE, listOf(newName.text.trim(), newColor.toString(), "", "0"),
            tr("kami_claims.admin.ranks.create.title", newName.text.trim(), d.row.name), full ?: NameCheck.problem(newName.text, limits))
    }

    private fun missing(invalid: Boolean) = tr("kami_claims.admin.input_missing").takeIf { invalid }
}
