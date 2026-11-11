package kami.claims.client.app.pages

import kami.claims.client.app.ClientLocks
import kami.claims.client.app.Vocabulary
import kami.claims.client.app.rankLine
import kami.claims.client.app.tint
import kami.claims.gov.PRESIDENT
import kami.claims.gov.Perm
import kami.claims.client.store.restricted
import kami.claims.net.Info
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.RISK_DOT
import kami.libs.ui.widget.riskDot
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.section
import kami.libs.ui.widget.select

class RankLookup {
    private var perm: Perm? = null
    private var member: String? = null

    fun draw(ui: Ui, r: Rect, info: Info) {
        val holders = perm?.let { holders(info, it) }.orEmpty()
        val effective = member?.let { id -> info.members.firstOrNull { it.id == id } }?.let { effective(info, it.rank) }.orEmpty()
        val total = 2 * (SECTION_H + CONTROL_H + GAP) + maxOf(1, holders.size) * ROW_H + maxOf(1, effective.size) * ROW_H
        ui.scroll("rank-lookup", r, total) { area ->
            var y = area.y
            y += selectorSection(ui, Rect(area.x, y, area.w, 0), tr("kami_claims.ranks.who")) {
                ui.select(it, permOptions(), perm, tr("kami_claims.ranks.who.placeholder"), key = "lookup-perm")?.let { next -> perm = next }
            }
            holders.forEachIndexed { i, holder -> holderRow(ui, Rect(area.x, y + i * ROW_H, area.w, ROW_H), holder) }
            if (perm != null && holders.isEmpty()) Draw.text(ui.g, tr("kami_claims.ranks.who.nobody"), area.x + 2, y + 2, Palette.textMuted)
            y += maxOf(1, holders.size) * ROW_H
            y += selectorSection(ui, Rect(area.x, y, area.w, 0), tr("kami_claims.ranks.viewas")) {
                ui.select(it, memberOptions(info), member, tr("kami_claims.ranks.viewas.placeholder"), key = "lookup-member")?.let { next -> member = next }
            }
            effective.forEachIndexed { i, p -> effectiveRow(ui, Rect(area.x, y + i * ROW_H, area.w, ROW_H), p) }
            if (member != null && effective.isEmpty()) Draw.text(ui.g, if (info.restricted(Perm.VIEW_PEOPLE)) ClientLocks.perm(Perm.VIEW_PEOPLE).orEmpty() else tr("kami_claims.ranks.viewas.none"), area.x + 2, y + 2, Palette.textMuted)
        }
    }

    private fun permOptions() = Perm.entries.map { Option(it, Vocabulary.perm(it).label, null, Vocabulary.perm(it).description) }

    private fun memberOptions(info: Info) = info.members.map { Option(it.id, it.name, null, info.rankLine(it.rank)?.name) }

    private fun selectorSection(ui: Ui, at: Rect, title: String, selector: (Rect) -> Unit): Int {
        val body = ui.section(Rect(at.x, at.y, at.w, SECTION_H), title)
        selector(Rect(body.x, body.y, body.w, CONTROL_H))
        return SECTION_H + CONTROL_H + GAP
    }

    private class Holder(val label: String, val color: Int)

    private fun holders(info: Info, perm: Perm): List<Holder> =
        info.ranks.filter { it.id == PRESIDENT || perm.name in it.perms }.map { Holder(it.name, it.tint) } +
            info.groups.filterValues { perm.name in it }.keys.map { Holder(tr("kami_claims.group.${it.lowercase()}"), Palette.textSecondary) }

    private fun effective(info: Info, rankId: String): List<Perm> {
        val line = info.rankLine(rankId) ?: return emptyList()
        return Perm.entries.filter { line.id == PRESIDENT || it.name in line.perms }
    }

    private fun holderRow(ui: Ui, row: Rect, holder: Holder) {
        Draw.fill(ui.g, Rect(row.x + 2, row.y + (row.h - DOT) / 2, DOT, DOT), holder.color)
        ui.fitText(row.x + DOT + 8, row.y + 2, row.w - DOT - 8, holder.label, holder.color)
    }

    private fun effectiveRow(ui: Ui, row: Rect, perm: Perm) {
        ui.riskDot(row.x + 2, row.y + (row.h - RISK_DOT) / 2, Vocabulary.risk(perm), "lookup-risk:${perm.name}")
        ui.fitText(row.x + RISK_DOT + 6, row.y + 2, row.w - RISK_DOT - 6, Vocabulary.perm(perm).label, Palette.text)
    }
}

private const val SECTION_H = 13
private const val GAP = 4
private const val ROW_H = 12
private const val DOT = 6
