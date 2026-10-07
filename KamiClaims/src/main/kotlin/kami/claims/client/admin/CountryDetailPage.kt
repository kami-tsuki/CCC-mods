package kami.claims.client.admin

import kami.claims.admin.CountryAdmin
import kami.claims.admin.CountryDetail
import kami.claims.admin.CountryRequests
import kami.claims.admin.LedgerRow
import kami.claims.admin.MemberRow
import kami.claims.admin.WarRow
import kami.claims.client.ClientHooks
import kami.claims.client.app.pages.WarPage
import kami.claims.client.store.ClaimsStore
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.app.remoteFailed
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kami.libs.ui.widget.*
import kami.libs.ui.text.noteLabel

private const val HEADER_H = 24
private const val TILE_H = 44
private const val KPI_COUNT = 7
private const val GROUP_GAP = 6

internal class CountryDetailPage(private val app: KamiApp) : Page() {
    private var id: String? = null
    private var tab = 0
    private val detail get() = CountryAdminSection.detail
    private val members = TableState<MemberRow>()
    private val ledger = TableState<LedgerRow>()
    private val wars = TableState<WarRow>()
    private val name = TextState()
    private val chunk = TextState()
    private val node = TextState()
    private val amount = TextState()
    private var leader: String? = null
    private var war: String? = null
    private var op = CountryAdmin.researchOps[0]
    private var controlsH = 0
    private var stats: CountryStats? = null

    override val title get() = current()?.row?.name ?: tr("kami_claims.admin.nav.detail")

    override fun opened(route: Route) {
        val next = route.param("id") ?: return
        CountryAdminSection.shown = next
        if (next == id) return
        id = next
        tab = 0
        stats = null
        leader = null
        war = null
        listOf(name, chunk, node, amount).forEach { it.set("") }
    }

    private fun current() = detail.data?.takeIf { it.row.id == id }

    override fun draw(ui: Ui, r: Rect) {
        if (id == null) {
            if (ui.emptyState(r, tr("kami_claims.admin.none"), tr("kami_claims.admin.none.body"), action = tr("kami_claims.admin.nav.list"), actionIcon = Icons.FLAG))
                app.navigate(Route(CountryAdminSection.LIST_PAGE))
            return
        }
        detail.poll()
        val d = current() ?: return skeleton(ui, r)
        header(ui, r.top(HEADER_H), d)
        tiles(ui, r.dropTop(HEADER_H + 4).top(TILE_H), d)
        val (left, right) = r.dropTop(HEADER_H + TILE_H + 10).columns(listOf(1.4f, 1f), 8)
        tables(ui, left, d)
        controls(ui, right, d)
    }

    private fun skeleton(ui: Ui, r: Rect) {
        if (detail.error != null && !detail.loading) return ui.remoteFailed(r, detail, "country")
        ui.skeleton(Rect(r.x, r.y + 4, r.w / 4, 10))
        ui.skeletonKpis(r.dropTop(HEADER_H + 4).top(TILE_H), KPI_COUNT)
        val (left, right) = r.dropTop(HEADER_H + TILE_H + 10).columns(listOf(1.4f, 1f), 8)
        ui.skeleton(left.top(CONTROL_H).withWidth(left.w / 2))
        ui.skeletonTable(left.dropTop(CONTROL_H + 6), 5)
        ui.skeletonLines(ui.card(right, tr("kami_claims.admin.controls"), Icons.GEAR), 6)
    }

    private fun header(ui: Ui, r: Rect, d: CountryDetail) {
        val viewAs = tr("kami_claims.admin.view_as")
        val pill = tr("kami_claims.admin.disbanded").takeUnless { d.row.active }
        val name = Draw.fit(d.row.name, r.w - 24 - buttonWidth(viewAs, Icons.EYE) - (pill?.let { Draw.width(it) + 22 } ?: 0))
        Draw.fill(ui.g, Rect(r.x, r.y + 4, 10, 10), d.row.color or 0xFF000000.toInt())
        Draw.text(ui.g, name, r.x + 16, r.y + 5, TextStyle.HEADING)
        pill?.let { ui.statusPill(r.x + 22 + Draw.width(name), r.y + 3, it, Severity.NEUTRAL) }
        if (ui.edgeButton(r.top(CONTROL_H), viewAs, Icons.EYE, enabled = d.row.active, disabledReason = tr("kami_claims.admin.view_as.disbanded"), key = "country-view")) {
            ClaimsStore.send("view", d.row.name)
            ClientHooks.openApp(wait = true)
        }
    }

    private fun tiles(ui: Ui, r: Rect, d: CountryDetail) = ui.kpiRow(r, listOf(
        KpiTile(tr("kami_claims.admin.col.treasury"), Format.money(d.row.treasury, compact = true), Icons.TREASURY, Palette.money, tip = Tip.text(Format.money(d.row.treasury), tr("kami_claims.admin.col.treasury"))),
        KpiTile(tr("kami_claims.admin.col.leader"), d.row.leaderName.ifEmpty { tr("kami_libs.common.none") }, Icons.CROWN, sub = tr("kami_claims.admin.members_n", d.row.members),
            tip = d.row.leaderName.takeIf { it.isNotEmpty() }?.let { Tip.text(tr("kami_claims.admin.members_n", d.row.members), it) }),
        KpiTile(tr("kami_claims.admin.col.claims"), Format.number(d.row.claims.toLong()), Icons.AREA, sub = tr("kami_claims.admin.since", Format.exact(d.created))),
        KpiTile(tr("kami_claims.admin.col.level"), Format.number(d.row.level.toLong()), Icons.STAR, sub = tr("kami_claims.admin.research_n", d.research.done, d.research.total)),
        KpiTile(tr("kami_claims.kpi.power"), WarPage.power(d.row.power), Icons.SHIELD,
            sub = tr("kami_claims.kpi.power.sub", Format.number(d.row.reputation), d.row.fame?.let(WarPage::stars) ?: "-"),
            tip = Tip.text(tr("kami_claims.admin.fame_votes", d.row.fameCount), tr("kami_claims.kpi.power"))),
        KpiTile(tr("kami_claims.admin.col.wars"), Format.number(d.row.wars.toLong()), Icons.SHIELD, if (d.row.wars > 0) Palette.danger else Palette.text),
        KpiTile(tr("kami_claims.admin.col.overlord"), d.row.overlord ?: tr("kami_libs.common.none"), Icons.CHAIN,
            sub = d.provinces.takeIf { it.isNotEmpty() }?.let { tr("kami_claims.admin.provinces_n", it.size) },
            tip = Tip(tr("kami_claims.admin.col.overlord"), listOfNotNull(
                (d.row.overlord ?: tr("kami_libs.common.none")) to Palette.text,
                d.provinces.takeIf { it.isNotEmpty() }?.let { tr("kami_claims.admin.provinces_list", it.joinToString(", ")) to Palette.textSecondary }
            )))
    ), key = "country-kpi")

    private fun tables(ui: Ui, r: Rect, d: CountryDetail) {
        val tabs = listOf(
            TabItem(tr("kami_claims.admin.tab.members"), Icons.PEOPLE, d.members.size),
            TabItem(tr("kami_claims.admin.tab.ledger"), Icons.LEDGER),
            TabItem(tr("kami_claims.admin.tab.wars"), Icons.SHIELD, d.wars.size, Severity.DANGER),
            TabItem(tr("kami_claims.admin.nav.stats"), Icons.STATS)
        )
        ui.subTabs(r.top(CONTROL_H), tabs, tab, key = "country-tabs")?.let { tab = it }
        val body = r.dropTop(CONTROL_H + 6)
        when (tab) {
            0 -> ui.table(body, memberColumns(), d.members, members, { it.uuid }, emptyText = tr("kami_claims.admin.members.empty"), key = "country-members")
                .opened?.let { m -> app.navigate(Route("player", mapOf("uuid" to m.uuid))) }
            1 -> ui.table(body, ledgerColumns(), d.ledger, ledger, { it.at to it.kind to it.balance }, severity = { if (it.amount >= 0) Severity.SUCCESS else Severity.DANGER },
                emptyText = tr("kami_claims.admin.ledger.empty"), key = "country-ledger")
            2 -> ui.table(body, warColumns(), d.wars, wars, { it.id }, emptyText = tr("kami_claims.admin.wars.empty"), key = "country-wars")
            else -> (stats ?: CountryStats(d.row.id).also { stats = it }).draw(ui, body)
        }
    }

    private fun controls(ui: Ui, r: Rect, d: CountryDetail) {
        val body = ui.card(r, tr("kami_claims.admin.controls"), Icons.GEAR)
        ui.scroll("country-controls", body, controlsH) { area ->
            val f = Flow(Rect(area.x, area.y, area.w, Int.MAX_VALUE / 2), 4)
            controlLines(ui, f, d)
            controlsH = f.rest.y - area.y
        }
    }

    private fun controlLines(ui: Ui, f: Flow, d: CountryDetail) {
        val lock = lockUnless(adminManager) ?: tr("kami_claims.admin.disbanded").takeUnless { d.row.active }
        fun line() = Row(f.take(CONTROL_H), 4)
        fun button(row: Row, label: String, icon: Icon, invalid: String?, danger: Boolean, act: String, title: String, args: () -> List<String>) {
            val why = lock ?: invalid
            val full = listOf(d.row.id, d.rev) + args()
            if (ui.edgeButton(row, label, icon, if (danger) ButtonStyle.DANGER else ButtonStyle.SECONDARY, why == null, why, pending = adminPending(act, full), key = "country-act:$label"))
                reasonDialog({ app.open(it) }, title, danger) { reason, _, _ ->
                    app.adminAct(act, full, reason) { if (act == CountryRequests.DISBAND) app.navigate(Route(CountryAdminSection.LIST_PAGE)) }
                }
        }
        val missing = tr("kami_claims.admin.input_missing")

        ui.textField(f.take(CONTROL_H), name, tr("kami_claims.admin.rename.placeholder"), maxLength = 32, key = "country-name")
        button(line(), tr("kami_claims.admin.rename"), Icons.EDIT, missing.takeIf { name.text.isBlank() }, false, CountryRequests.RENAME, tr("kami_claims.admin.rename.title", d.row.name, name.text.trim())) { listOf(name.text.trim()) }
        f.skip(GROUP_GAP)
        val who = d.members.firstOrNull { it.uuid == leader }
        ui.select(f.take(CONTROL_H), d.members.map { Option(it.uuid, it.name) }, leader, tr("kami_claims.admin.leader.placeholder"), key = "country-leader")?.let { leader = it }
        button(line(), tr("kami_claims.admin.leader"), Icons.CROWN, missing.takeIf { who == null }, false, CountryRequests.LEADER, tr("kami_claims.admin.leader.title", who?.name.orEmpty(), d.row.name)) { listOfNotNull(leader) }
        f.skip(GROUP_GAP)
        ui.textField(f.take(CONTROL_H), chunk, tr("kami_claims.admin.unclaim.placeholder"), maxLength = 128, key = "country-chunk")
        line().let { row ->
            button(row, tr("kami_claims.admin.unclaim"), Icons.AREA, missing.takeIf { chunk.text.isBlank() }, false, CountryRequests.UNCLAIM, tr("kami_claims.admin.unclaim.title", chunk.text.trim(), d.row.name)) { listOf(chunk.text.trim()) }
            button(row, tr("kami_claims.admin.unclaim_all"), Icons.REMOVE, null, true, CountryRequests.UNCLAIM, tr("kami_claims.admin.unclaim_all.title", d.row.name)) { listOf(CountryAdmin.ALL) }
        }
        f.skip(GROUP_GAP)
        val w = d.wars.firstOrNull { it.id == war }
        ui.select(f.take(CONTROL_H), d.wars.map { Option(it.id, it.enemy) }, war, tr("kami_claims.admin.war_end.placeholder"), enabled = d.wars.isNotEmpty(), disabledReason = tr("kami_claims.admin.wars.empty"), key = "country-war")?.let { war = it }
        button(line(), tr("kami_claims.admin.war_end"), Icons.HANDSHAKE, missing.takeIf { w == null }, true, CountryRequests.WAR_END, tr("kami_claims.admin.war_end.title", d.row.name, w?.enemy.orEmpty())) { listOfNotNull(war) }
        f.skip(GROUP_GAP)
        line().let { row ->
            val ops = CountryAdmin.researchOps.map { Option(it, tr("kami_claims.admin.research.$it")) }
            ui.select(row.take(ops.maxOf { Draw.width(it.label) } + 24), ops, op, key = "country-op")?.let { op = it }
            ui.textField(row.remaining(), node, tr("kami_claims.admin.research.placeholder"), maxLength = 128, key = "country-node")
        }
        val needsNode = op != "reset"
        button(line(), tr("kami_claims.admin.research"), Icons.SCROLL, missing.takeIf { needsNode && node.text.isBlank() }, op == "reset" || op == "revoke", CountryRequests.RESEARCH,
            tr("kami_claims.admin.research.title", tr("kami_claims.admin.research.$op"), node.text.trim().ifEmpty { tr("kami_claims.admin.research.all") }, d.row.name)) { listOf(op, node.text.trim()) }
        f.skip(GROUP_GAP)
        val value = Format.parseAmount(amount.text)?.takeIf { it > 0 }
        val bad = tr("kami_claims.admin.money.invalid").takeIf { value == null }
        ui.textField(f.take(CONTROL_H), amount, tr("kami_libs.common.amount"), Icons.COIN, allow = { it.isDigit() || it in "kmbKMB.," }, key = "country-amount")
        line().let { row ->
            button(row, tr("kami_claims.admin.money.give"), Icons.DEPOSIT, bad, false, CountryRequests.MONEY, tr("kami_claims.admin.money.give.title", Format.money(value ?: 0), d.row.name)) { listOf("give", value.toString()) }
            button(row, tr("kami_claims.admin.money.take"), Icons.WITHDRAW, bad, true, CountryRequests.MONEY, tr("kami_claims.admin.money.take.title", Format.money(value ?: 0), d.row.name)) { listOf("take", value.toString()) }
        }
        f.skip(GROUP_GAP)
        button(line(), tr("kami_claims.admin.disband"), Icons.DANGER, null, true, CountryRequests.DISBAND, tr("kami_claims.admin.disband.title", d.row.name)) { emptyList() }
    }

    private fun memberColumns() = listOf(
        Column<MemberRow>(tr("kami_libs.common.name"), -1, sort = compareBy { it.name.lowercase() }) { _, r, row ->
            avatar(row.uuid, r.x, r.y + (r.h - 10) / 2, 10, null)
            Draw.text(g, Draw.fit(row.name, r.w - 14), r.x + 14, r.y + (r.h - 8) / 2, Palette.text)
        },
        Column.text<MemberRow>(tr("kami_libs.common.rank"), 64) { tr("kami_claims.rank.${it.rank.lowercase()}") },
        Column.number<MemberRow>(tr("kami_claims.admin.col.since"), 60, format = { Format.ago(it) }) { it.since },
        Column.number<MemberRow>(tr("kami_claims.admin.col.seen"), 60, format = { Format.ago(it) }) { it.seen }
    )

    private fun ledgerColumns() = listOf(
        Column.number<LedgerRow>(tr("kami_claims.admin.col.time"), 60, format = { Format.ago(it) }) { it.at },
        Column.text<LedgerRow>(tr("kami_claims.admin.col.kind"), 70) { trOr("kami_claims.ledger.${it.kind.lowercase()}", it.kind) },
        Column.number<LedgerRow>(tr("kami_libs.common.amount"), 64, color = { if (it.amount >= 0) Palette.success else Palette.danger }, format = { Format.signedMoney(it) }) { it.amount },
        Column.number<LedgerRow>(tr("kami_claims.admin.col.treasury"), 64, color = { Palette.money }, format = { Format.money(it, compact = true) }) { it.balance },
        Column.text<LedgerRow>(tr("kami_claims.admin.col.actor"), -1) { listOfNotNull(it.actor, noteLabel(it.note).ifEmpty { null }).joinToString(" · ") }
    )

    private fun warColumns() = listOf(
        Column.text<WarRow>(tr("kami_claims.admin.col.enemy"), -1) { it.enemy },
        Column.text<WarRow>(tr("kami_claims.admin.col.side"), 70) { tr(if (it.attacker) "kami_claims.admin.side.attacker" else "kami_claims.admin.side.defender") },
        Column.text<WarRow>(tr("kami_claims.admin.col.phase"), 90) { tr("kami_claims.warpage.phase.${it.phase.lowercase()}") }
    )
}
