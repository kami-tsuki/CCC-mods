package kami.claims.client.admin

import kami.libs.text.Phrase
import kami.libs.ui.core.Memo
import kami.claims.admin.CountryAdmin
import kami.claims.admin.CountryDetail
import kami.claims.admin.CountryRequests
import kami.claims.admin.LedgerRow
import kami.claims.admin.MemberRow
import kami.claims.admin.ResearchCategory
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
private const val COMPACT_W = 420
private const val KPI_COUNT = 7
private const val GROUP_GAP = 6
private const val FIRST_GOVERNANCE_TAB = 4

private data class ScopeCat(val cat: ResearchCategory, val key: String, val treeName: String, val categoryName: String)

internal class CountryDetailPage(private val app: KamiApp) : Page() {
    private val countryTabs = Memo()
    private val warColumnsMemo = Memo()
    private val ledgerColumnsMemo = Memo()
    private val memberColumnsMemo = Memo()
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
    private val level = TextState()
    private var leader: String? = null
    private var war: String? = null
    private var op = CountryAdmin.researchOps[0]
    private var scope = ""
    private var controlsH = 0
    private var stats: CountryStats? = null
    private val kit = GovernanceKit(app)
    private val governanceTabs = listOf(GovernmentTab(kit), RanksTab(kit), GroupsTab(kit), JobsTab(kit), SectorsTab(kit))
    private val audit = AuditTab()

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
        scope = ""
        governanceTabs.forEach { it.reset() }
        listOf(name, chunk, node, amount, level).forEach { it.set("") }
        op = CountryAdmin.researchOps[0]
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
        val compact = r.w < COMPACT_W
        val tilesH = if (compact) TILE_H * 2 + 4 else TILE_H
        tiles(ui, r.dropTop(HEADER_H + 4).top(tilesH), d)
        val rest = r.dropTop(HEADER_H + tilesH + 10)
        if (tab >= FIRST_GOVERNANCE_TAB) tables(ui, rest, d)
        else if (compact) {
            val split = rest.h * 11 / 20
            tables(ui, rest.top(split), d)
            controls(ui, rest.dropTop(split + 8), d)
        } else {
            val (left, right) = rest.columns(listOf(1.4f, 1f), 8)
            tables(ui, left, d)
            controls(ui, right, d)
        }
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
        Draw.fill(ui.g, Rect(r.x, r.y + 4, 10, 10), d.row.color or 0xFF000000.toInt())
        val nameEnd = ui.fitText(r.x + 16, r.y + 5, (r.w - 24 - buttonWidth(viewAs, Icons.EYE) - (pill?.let { Draw.width(it) + 22 } ?: 0)).coerceAtLeast(0), d.row.name, style = TextStyle.HEADING)
        pill?.let { ui.statusPill(nameEnd + 6, r.y + 3, it, Severity.NEUTRAL) }
        if (ui.edgeButton(r.top(CONTROL_H), viewAs, Icons.EYE, enabled = d.row.active, disabledReason = tr("kami_claims.admin.view_as.disbanded"), key = "country-view")) {
            ClaimsStore.send("view", d.row.name)
            ClientHooks.openApp(wait = true)
        }
    }

    private fun tiles(ui: Ui, r: Rect, d: CountryDetail) {
        val items = listOf(
        KpiTile(tr("kami_libs.flow.treasury"), Format.money(d.row.treasury), Icons.TREASURY, Palette.money, tip = Tip.text(Format.money(d.row.treasury), tr("kami_libs.flow.treasury"))),
        KpiTile(tr("kami_claims.admin.col.leader"), d.row.leaderName.ifEmpty { tr("kami_libs.common.none") }, Icons.CROWN, sub = tr("kami_claims.admin.members_n", d.row.members),
            tip = d.row.leaderName.takeIf { it.isNotEmpty() }?.let { Tip.text(tr("kami_claims.admin.members_n", d.row.members), it) }),
        KpiTile(tr("kami_claims.admin.col.claims"), Format.number(d.row.claims.toLong()), Icons.AREA, sub = tr("kami_claims.admin.since", Format.exact(d.created))),
        KpiTile(tr("kami_libs.common.level"), Format.number(d.row.level.toLong()), Icons.STAR, sub = tr("kami_claims.admin.research_n", d.research.done, d.research.total)),
        KpiTile(tr("kami_claims.kpi.power"), WarPage.power(d.row.power), Icons.SHIELD,
            sub = tr("kami_claims.kpi.power.sub", Format.number(d.row.reputation), d.row.fame?.let(WarPage::stars) ?: "-"),
            tip = Tip.text(tr("kami_claims.admin.fame_votes", d.row.fameCount), tr("kami_claims.kpi.power"))),
        KpiTile(tr("kami_claims.admin.col.wars"), Format.number(d.row.wars.toLong()), Icons.SHIELD, if (d.row.wars > 0) Palette.danger else Palette.text),
        KpiTile(tr("kami_claims.admin.col.overlord"), d.row.overlord ?: tr("kami_libs.common.none"), Icons.CHAIN,
            sub = d.provinces.takeIf { it.isNotEmpty() }?.let { tr("kami_claims.admin.provinces_n", it.size) },
            tip = Tip(tr("kami_claims.admin.col.overlord"), listOfNotNull(
                (d.row.overlord ?: tr("kami_libs.common.none")) to Palette.text,
                d.provinces.takeIf { it.isNotEmpty() }?.let { tr("kami_libs.common.provinces_list", it.joinToString(", ")) to Palette.textSecondary }
            )))
        )
        if (r.h < TILE_H * 2) ui.kpiRow(r, items, key = "country-kpi")
        else {
            val half = (r.h - 4) / 2
            ui.kpiRow(r.top(half), items.take(4), key = "country-kpi")
            ui.kpiRow(r.dropTop(half + 4), items.drop(4), key = "country-kpi-2")
        }
    }

    private fun tables(ui: Ui, r: Rect, d: CountryDetail) {
        val tabs = countryTabs.of(d.members.size, d.wars.size, d.governance.ranks.size, d.governance.jobs.size, d.governance.sectors.size, Format.locale) { listOf(
            TabItem(tr("kami_libs.common.members"), Icons.PEOPLE, d.members.size),
            TabItem(tr("kami_claims.admin.tab.ledger"), Icons.LEDGER),
            TabItem(tr("kami_claims.admin.tab.wars"), Icons.SHIELD, d.wars.size, Severity.DANGER),
            TabItem(tr("kami_libs.common.statistics"), Icons.STATS),
            TabItem(tr("kami_claims.admin.tab.government"), Icons.SCALES),
            TabItem(tr("kami_claims.admin.tab.ranks"), Icons.CROWN, d.governance.ranks.size),
            TabItem(tr("kami_claims.admin.tab.groups"), Icons.LOCK),
            TabItem(tr("kami_claims.admin.tab.jobs"), Icons.SCROLL, d.governance.jobs.size),
            TabItem(tr("kami_claims.admin.tab.sectors"), Icons.AREA, d.governance.sectors.size),
            TabItem(tr("kami_claims.admin.tab.audit"), Icons.LEDGER)
        ) }
        ui.subTabs(r.top(CONTROL_H), tabs, tab, key = "country-tabs")?.let { tab = it }
        val body = r.dropTop(CONTROL_H + 6)
        when (tab) {
            0 -> ui.table(body, memberColumns(), d.members, members, { it.uuid }, emptyText = tr("kami_libs.table.empty"), key = "country-members")
                .opened?.let { m -> app.navigate(Route("player", mapOf("uuid" to m.uuid))) }
            1 -> ui.table(body, ledgerColumns(), d.ledger, ledger, { it.at to it.kind to it.balance }, severity = { if (it.amount >= 0) Severity.SUCCESS else Severity.DANGER },
                emptyText = tr("kami_libs.table.empty"), key = "country-ledger")
            2 -> ui.table(body, warColumns(), d.wars, wars, { it.id }, emptyText = tr("kami_libs.table.empty"), key = "country-wars")
            3 -> (stats ?: CountryStats(d.row.id).also { stats = it }).draw(ui, body)
            governanceTabs.size + FIRST_GOVERNANCE_TAB -> audit.draw(ui, body, d)
            else -> governanceTabs[tab - FIRST_GOVERNANCE_TAB].draw(ui, body, d)
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
        ui.select(f.take(CONTROL_H), d.wars.map { Option(it.id, it.enemy) }, war, tr("kami_libs.flow.war"), enabled = d.wars.isNotEmpty(), disabledReason = tr("kami_libs.table.empty"), key = "country-war")?.let { war = it }
        button(line(), tr("kami_claims.admin.war_end"), Icons.HANDSHAKE, missing.takeIf { w == null }, true, CountryRequests.WAR_END, tr("kami_claims.admin.war_end.title", d.row.name, w?.enemy.orEmpty())) { listOfNotNull(war) }
        f.skip(GROUP_GAP)
        line().let { row ->
            val ops = CountryAdmin.researchOps.map { Option(it, tr("kami_claims.admin.research.$it")) }
            ui.select(row.take(ops.maxOf { Draw.width(it.label) } + 24), ops, op, key = "country-op")?.let { op = it }
            ui.textField(row.remaining(), node, tr("kami_claims.admin.research.placeholder"), maxLength = 128, key = "country-node")
        }
        button(line(), tr("kami_claims.admin.research"), Icons.SCROLL, missing.takeIf { node.text.isBlank() }, op == "reset" || op == "revoke", CountryRequests.RESEARCH,
            tr("kami_claims.admin.research.title", tr("kami_claims.admin.research.$op"), node.text.trim(), d.row.name)) { listOf(op, node.text.trim()) }
        f.skip(GROUP_GAP)
        val cats = d.research.categories.map { c ->
            ScopeCat(c, "${c.tree}|${c.id}", Phrase.or("kami_claims.research.tree.${c.tree}", c.treeTitle).resolve(), Phrase.or("kami_claims.research.category.${c.id}", c.title).resolve())
        }
        val pickedCat = cats.firstOrNull { it.key == scope }
        val picked = pickedCat?.cat
        val scopeName = pickedCat?.let { tr("kami_claims.admin.research.scope.name", it.treeName, it.categoryName) } ?: tr("kami_claims.admin.research.all")
        val done = picked?.done ?: d.research.categories.sumOf { it.done }
        val total = picked?.total ?: d.research.categories.sumOf { it.total }
        val queued = picked?.queued ?: d.research.categories.sumOf { it.queued }
        val finishable = total - done
        val resettable = done + queued
        val scopes = listOf(Option("", tr("kami_claims.admin.research.scope.all", d.research.categories.sumOf { it.done }, d.research.categories.sumOf { it.total }))) +
            cats.map { Option(it.key, tr("kami_claims.admin.research.scope.cat", it.treeName, it.categoryName, it.cat.done, it.cat.total)) }
        ui.select(f.take(CONTROL_H), scopes, pickedCat?.key ?: "", key = "country-scope")?.let { scope = it }
        val suffix = if (picked == null) "all" else "category"
        val tree = picked?.tree.orEmpty()
        val category = picked?.id.orEmpty()
        line().let { row ->
            button(row, tr("kami_claims.admin.research.finish_$suffix"), Icons.SCROLL, tr("kami_claims.admin.research.scope.complete").takeIf { finishable == 0 }, false, CountryRequests.RESEARCH_SCOPE,
                tr("kami_claims.admin.research.scope.title", tr("kami_claims.admin.research.finish"), scopeName, d.row.name, finishable)) { listOf("finish", tree, category) }
            button(row, tr("kami_claims.admin.research.reset_$suffix"), Icons.REMOVE, tr("kami_claims.admin.research.scope.empty").takeIf { resettable == 0 }, true, CountryRequests.RESEARCH_SCOPE,
                tr("kami_claims.admin.research.scope.title", tr("kami_claims.admin.research.reset"), scopeName, d.row.name, resettable)) { listOf("reset", tree, category) }
        }
        f.skip(GROUP_GAP)
        val wanted = level.text.trim().toIntOrNull()?.takeIf { it in 1..d.topLevel }
        ui.textField(f.take(CONTROL_H), level, tr("kami_claims.admin.level.placeholder", d.row.level, Format.number(d.xp), d.topLevel), maxLength = 4, allow = { it.isDigit() }, key = "country-level")
        button(line(), tr("kami_claims.admin.level.apply"), Icons.SCROLL, tr("kami_claims.admin.level.invalid", d.topLevel).takeIf { wanted == null }, true, CountryRequests.LEVEL,
            tr("kami_claims.admin.level.title", d.row.name, d.row.level, wanted ?: 0)) { listOf(wanted.toString()) }
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

    private fun memberColumns() = memberColumnsMemo.of(Format.locale) { listOf(
        Column<MemberRow>(tr("kami_libs.common.name"), -1, sort = textOrder { it.name }) { _, r, row ->
            avatar(row.uuid, r.x, r.y + (r.h - 10) / 2, 10, null)
            fitText(r.x + 14, r.y + (r.h - 8) / 2, r.w - 14, row.name, Palette.text, key = "mn:${row.uuid}")
        },
        Column.text<MemberRow>(tr("kami_libs.common.rank"), 64) { it.rank },
        Column.number<MemberRow>(tr("kami_claims.admin.col.since"), 60, format = { Format.ago(it) }) { it.since },
        Column.number<MemberRow>(tr("kami_libs.common.last_seen"), 60, format = { Format.ago(it) }) { it.seen }
    ) }

    private fun ledgerColumns() = ledgerColumnsMemo.of(Format.locale) { listOf(
        Column.number<LedgerRow>(tr("kami_libs.common.time"), 60, format = { Format.ago(it) }) { it.at },
        Column.text<LedgerRow>(tr("kami_claims.admin.col.kind"), 70) { trOr("kami_claims.ledger.${it.kind.lowercase()}", it.kind) },
        Column.number<LedgerRow>(tr("kami_libs.common.amount"), 64, color = { if (it.amount >= 0) Palette.success else Palette.danger }, format = { Format.signedMoney(it) }) { it.amount },
        Column.number<LedgerRow>(tr("kami_libs.flow.treasury"), 64, color = { Palette.money }, format = { Format.money(it) }) { it.balance },
        Column.text<LedgerRow>(tr("kami_claims.admin.col.actor"), -1) { listOfNotNull(it.actor, noteLabel(it.note).ifEmpty { null }).joinToString(" · ") }
    ) }

    private fun warColumns() = warColumnsMemo.of(Format.locale) { listOf(
        Column.text<WarRow>(tr("kami_claims.admin.col.enemy"), -1) { it.enemy },
        Column.text<WarRow>(tr("kami_libs.common.side"), 70) { tr(if (it.attacker) "kami_claims.admin.side.attacker" else "kami_claims.admin.side.defender") },
        Column.text<WarRow>(tr("kami_claims.admin.col.phase"), 90) { tr("kami_claims.warpage.phase.${it.phase.lowercase()}") }
    ) }
}
