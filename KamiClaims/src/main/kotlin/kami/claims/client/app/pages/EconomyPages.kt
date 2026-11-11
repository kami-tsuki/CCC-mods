package kami.claims.client.app.pages

import kami.libs.ui.core.Memo
import kami.claims.gov.Perm
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Finance
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
import kami.claims.client.app.runway
import kami.claims.client.store.ClaimsStore
import kami.libs.text.SearchQuery
import kami.claims.net.Info
import kami.claims.net.LedgerLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
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
import kami.libs.ui.text.trn
import kami.libs.ui.widget.*
import kotlin.math.max
import net.minecraft.client.Minecraft
import kami.libs.ui.text.noteLabel

private const val KPI_H = 44
private const val SECTION_GAP = 6
internal const val CARD_HEAD_H = 24
private const val WHATIF_H = 110

class BudgetPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.budget")
    override val sections = listOf("history")
    override val help get() = listOf(
        Callout("budget:income", tr("kami_libs.common.income"), tr("kami_claims.budget.help.income")),
        Callout("budget:spending", tr("kami_claims.budget.spending"), tr("kami_claims.budget.help.spending")),
        Callout("budget:forecast", tr("kami_claims.budget.forecast"), tr("kami_claims.budget.help.forecast")),
        Callout("budget:whatif", tr("kami_claims.budget.whatif"), tr("kami_claims.budget.help.whatif"))
    )
    private var whatIfTax = -1

    override fun actionsWidth() = buttonWidth(tr("kami_libs.common.deposit"), Icons.DEPOSIT) + buttonWidth(tr("kami_libs.common.withdraw"), Icons.WITHDRAW) + 4

    override fun actions(ui: Ui, r: Rect) {
        val row = Row(r)
        if (ui.edgeButton(row, tr("kami_libs.common.deposit"), Icons.DEPOSIT, ButtonStyle.PRIMARY, key = "b-dep")) Dialogs.money(app, true)
        val budgetBound = info?.government?.treasury == "budget"
        val reason = lock(Perm.WITHDRAW) ?: tr("kami_claims.error.budget_bound").takeIf { budgetBound }
        if (ui.edgeButton(row, tr("kami_libs.common.withdraw"), Icons.WITHDRAW, enabled = reason == null, disabledReason = reason, key = "b-wd")) Dialogs.money(app, false)
    }

    private fun pendingHeight(info: Info) = if (info.withdrawals.isEmpty()) 0 else SECTION_GAP + withdrawalsHeight(info)

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val minH = KPI_H + 2 * SECTION_GAP + 2 * CARD_HEAD_H + 5 * (TABLE_ROW_H + 1) + 11 * (info.provinces.size + info.breakdown.size) + WHATIF_H + pendingHeight(info)
        ui.scroll("budget", r, minH) { area -> body(ui, area.withHeight(max(area.h, minH)), info) }
    }

    private fun body(ui: Ui, r: Rect, info: Info) {
        val finance = Finance.of(info)
        val tributeIn = finance.tributeIn
        val tributeOut = finance.tributeOut
        val income = finance.income
        val spending = finance.spending
        val net = finance.net
        ui.kpiRow(r.top(KPI_H), listOf(
            KpiTile(tr("kami_claims.budget.income_day"), Format.number(income), Icons.INCOME, Palette.success),
            KpiTile(tr("kami_claims.budget.spending_day"), Format.number(spending), Icons.EXPENSE, Palette.danger),
            KpiTile(tr("kami_claims.kpi.net"), Format.signed(net), Icons.SCALES, if (net >= 0) Palette.success else Palette.danger),
            KpiTile(tr("kami_claims.help.term.runway"), app.runwayText(info.treasury, net), Icons.CLOCK, app.runwayColor(info.treasury, net))
        ), key = "budget-kpi")
        val rest = r.dropTop(KPI_H + SECTION_GAP)
        val (left, right) = rest.columns(2, 6)
        val lf = Flow(left, SECTION_GAP)
        val inc = lf.take(CARD_HEAD_H + 2 * (TABLE_ROW_H + 1) + info.provinces.size * 11)
        ui.anchor("budget:income", inc)
        val ib = ui.card(inc, tr("kami_libs.common.income"), Icons.INCOME)
        val fi = Flow(ib, 1)
        line(ui, fi.take(TABLE_ROW_H), Icons.HOUSE, tr("kami_claims.ledger.plot_tax"), tr("kami_libs.format.times", trn("kami_claims.unit.plot", info.claimList.count { it.owner.isNotEmpty() }), Format.money(info.citizenRent.toLong())), info.income, Palette.success)
        line(ui, fi.take(TABLE_ROW_H), Icons.CHAIN, tr("kami_claims.ledger.tribute_in"), trn("kami_claims.unit.province", info.provinces.size), tributeIn, Palette.success)
        info.provinces.forEach { p -> sub(ui, fi.take(10), p.name, Vocabulary.tribute(p.mode, p.amount), Vocabulary.tributeEstimate(p.mode, p.amount, p.income)) }
        val spend = lf.take(CARD_HEAD_H + 3 * (TABLE_ROW_H + 1) + info.breakdown.size * 11)
        ui.anchor("budget:spending", spend)
        val sb = ui.card(spend, tr("kami_claims.budget.spending"), Icons.EXPENSE)
        val fs = Flow(sb, 1)
        line(ui, fs.take(TABLE_ROW_H), Icons.AREA, tr("kami_claims.ledger.upkeep"), tr("kami_claims.budget.upkeep.detail", trn("kami_claims.unit.chunk", info.chunks - info.free)), info.upkeep, Palette.danger)
        info.breakdown.sortedByDescending { it.perDay }.forEach { b -> sub(ui, fs.take(10), Vocabulary.type(b.type).label, trn("kami_claims.unit.chunk", b.count), b.perDay.toLong(), Vocabulary.type(b.type).color) }
        line(ui, fs.take(TABLE_ROW_H), Icons.TOOL, tr("kami_claims.ledger.job_pay"), tr("kami_claims.budget.wages.detail", Format.percent(snap.jobShare / 100.0), Format.money(info.income * snap.jobShare / 100)), info.jobs, Palette.danger)
        line(ui, fs.take(TABLE_ROW_H), Icons.CHAIN, tr("kami_claims.ledger.tribute_out"), info.parent.ifEmpty { tr("kami_claims.budget.independent") }, tributeOut, Palette.danger)
        if (info.withdrawals.isNotEmpty()) withdrawalsCard(ui, lf.take(pendingHeight(info) - SECTION_GAP), info)
        val what = lf.remaining()
        ui.anchor("budget:whatif", what)
        val wb = ui.card(what, tr("kami_claims.budget.whatif"), Icons.SEARCH, help = tr("kami_claims.budget.whatif.help"))
        if (whatIfTax < 0) whatIfTax = info.citizenRent
        val wf = Flow(wb, 4)
        ui.fieldLabel(wf.take(9), tr("kami_claims.ledger.plot_tax"), tr("kami_claims.budget.whatif.rate", Format.perDay(Format.money(whatIfTax.toLong()))))
        ui.slider(wf.take(CONTROL_H), whatIfTax.toDouble(), 0.0, max(20.0, info.citizenRent * 3.0), 1.0, format = { Format.money(it.toLong()) }, key = "whatif")?.let { whatIfTax = it.toInt() }
        val plots = info.claimList.count { it.owner.isNotEmpty() }
        val newNet = net + (whatIfTax - info.citizenRent).toLong() * plots
        ui.property(wf.take(11), tr("kami_claims.kpi.net"), "${Format.signed(net)} → ${Format.signed(newNet)}", if (newNet >= 0) Palette.success else Palette.danger)
        ui.property(wf.take(11), tr("kami_claims.help.term.runway"), "${app.runwayText(info.treasury, net)} → ${app.runwayText(info.treasury, newNet)}", app.runwayColor(info.treasury, newNet))
        if (ui.link(wb.x, wf.rest.y + 2, tr("kami_claims.budget.whatif.link"), key = "to-plotlaw")) app.navigate(Route("plotlaw"))
        val rf = Flow(right, 6)
        val fc = rf.take((right.h * 0.55).toInt())
        ui.anchor("budget:forecast", fc)
        val fb = ui.card(fc, tr("kami_claims.budget.forecast.title"), Icons.STATS, help = tr("kami_claims.budget.forecast.help"))
        val loaded = ClaimsStore.history
        val recent = loaded.orEmpty().takeLast(30)
        val history = recent.map { it.treasury }
        if (loaded == null) ui.skeleton(fb.dropBottom(12))
        else ui.lineChart(fb.dropBottom(12), listOf(Series(tr("kami_libs.flow.treasury"), history + info.treasury + forecast(info.treasury, net, 30), Palette.money, true, history.size)),
            recent.map { tr("kami_claims.chart.day", Format.number(it.day)) } + tr("kami_libs.common.today") + List(30) { tr("kami_libs.time.in", Format.days(it + 1L)) }, key = "forecast")
        val summary = runway(info.treasury, net)?.let { tr("kami_claims.budget.forecast.empty", Format.days(it)) } ?: tr("kami_claims.budget.forecast.grows", Format.money(net * 30))
        ui.fitText(fb.x, fb.bottom - 9, fb.w, summary, if (net >= 0) Palette.success else Palette.warning)
        val flows = rf.remaining()
        val flowBody = ui.card(flows, tr("kami_claims.budget.flows"), Icons.SCALES, help = tr("kami_claims.budget.flows.help"))
        val days = loaded.orEmpty().takeLast(20)
        if (loaded == null) ui.skeleton(flowBody) else ui.barChart(flowBody, days.map { it.income + it.tributeIn + it.deposits }, days.map { it.upkeep + it.jobs + it.tributeOut + it.withdrawals }, days.map { tr("kami_claims.chart.day", Format.number(it.day)) })
    }

    private fun line(ui: Ui, r: Rect, icon: Icon, label: String, detail: String, amount: Long, color: Int) {
        val x = r.x + Draw.leadIcon(ui.g, icon, r.x, r.centerY) + 2
        val y = r.y + 3
        val labelW = Draw.width(label, TextStyle.HEADING)
        Draw.text(ui.g, label, x, y, TextStyle.HEADING)
        ui.fitText(x + labelW + 4, y, r.right - x - labelW - 74, detail, Palette.textMuted)
        Draw.textRight(ui.g, Format.money(amount), r.right, y, color = color)
    }

    private fun sub(ui: Ui, r: Rect, label: String, detail: String, amount: Long, color: Int = Palette.textSecondary) {
        Draw.fill(ui.g, Rect(r.x + 5, r.y + 3, 3, 3), color)
        ui.fitText(r.x + 16, r.y, r.w - 86, "$label  ·  $detail", Palette.textSecondary)
        Draw.textRight(ui.g, Format.number(amount), r.right, r.y, Palette.textMuted)
    }

}

class LedgerPage(app: ClaimsApp) : ClaimsPage(app) {
    private val ledgerColumns = Memo()
    private val ledgerFlows = Memo()
    private val ledgerKinds = Memo()
    override val title get() = tr("kami_claims.nav.ledger")
    override val sections = listOf(ClaimsStore.LEDGER)
    override val help get() = listOf(
        Callout("ledger:filters", tr("kami_libs.common.filter"), tr("kami_claims.ledger.help.filter.desc")),
        Callout("ledger:table", tr("kami_claims.ledger.help.table"), tr("kami_claims.ledger.help.table.desc"))
    )
    private val table = TableState<LedgerLine>()
    private var kind = "all"
    private var flow = "all"
    private val search = TextState()

    override fun draw(ui: Ui, r: Rect) {
        val ledger = ClaimsStore.ledger
        var rows: List<LedgerLine> = emptyList()
        val tableRect = ui.filterBar(r, "ledger:filters") { bar ->
            rows = ui.filtered("ledger-filtered", listOf(kind, flow, search.text, ledger)) {
                val q = SearchQuery.parse(search.text)
                ledger.orEmpty().filter { (kind == "all" || it.kind == kind) && (flow == "all" || (flow == "in") == (it.amount > 0)) && q.matches(it.actor, noteLabel(it.note)) }
            }
            val row = Row(bar, 6)
            val copy = row.takeFromRight(CONTROL_H)
            val totalIn = rows.filter { it.amount > 0 }.sumOf { it.amount }
            val totalOut = rows.filter { it.amount < 0 }.sumOf { it.amount }
            val inW = moneyWidth(totalIn, true)
            val outW = moneyWidth(totalOut, true)
            val count = trn("kami_claims.unit.entry", rows.size)
            if (row.rest.w >= 460 + inW + outW) {
                ui.moneyRight(row.takeFromRight(outW).right, bar.y + 5, totalOut, signed = true)
                ui.moneyRight(row.takeFromRight(inW).right, bar.y + 5, totalIn, signed = true)
            }
            if (row.rest.w >= 420 + Draw.width(count)) Draw.text(ui.g, count, row.takeFromRight(Draw.width(count)).x, bar.y + 5, Palette.textMuted)
            val options = ledgerKinds.of(Format.locale) { listOf(Option("all", tr("kami_claims.ledger.all"), Icons.LEDGER)) + Vocabulary.ledger.map { (k, look) -> Option(k, look.label, look.icon, look.description) } }
            ui.select(row.take(150), options, kind, key = "ledger-kind")?.let { kind = it }
            if (row.rest.w >= 230) ui.select(row.take(100), ledgerFlows.of(Format.locale) { listOf(Option("all", tr("kami_libs.common.all")), Option("in", tr("kami_libs.common.income"), Icons.INCOME), Option("out", tr("kami_claims.budget.spending"), Icons.EXPENSE)) }, flow, key = "ledger-flow")?.let { flow = it }
            ui.searchField(row.rest, search, key = "ledger-search")
            if (ui.iconButton(copy, Icons.COPY, tr("kami_claims.ledger.copy.tooltip"), key = "ledger-copy")) {
                Minecraft.getInstance().keyboardHandler.clipboard = rows.joinToString("\n") { "${Format.exact(it.at)}\t${Vocabulary.ledger(it.kind).label}\t${it.amount}\t${it.balance}\t${it.actor}\t${it.note}" }
                app.toast(Severity.SUCCESS, tr("kami_claims.ledger.copied"), trn("kami_claims.unit.line", rows.size))
            }
        }
        ui.anchor("ledger:table", tableRect)
        if (ledger == null) return ui.skeletonTable(tableRect, 5, skeletonRowCount(tableRect.h - TABLE_HEADER_H))
        if (ledger.isEmpty() && !stale(ClaimsStore.LEDGER)) {
            ui.emptyState(tableRect, tr("kami_libs.table.empty"), tr("kami_claims.ledger.empty.desc"), Illustrations.TREASURY)
            return
        }
        ui.table(tableRect, ledgerColumns.of(Format.locale) { listOf(
            Column<LedgerLine>(tr("kami_libs.common.when"), 70, sort = compareBy { it.at }) { _, c, e -> Draw.text(g, Format.ago(e.at), c.x, c.y + 3, Palette.textMuted); tooltip("at:${e.at}", c, Format.exact(e.at)) },
            Column<LedgerLine>(tr("kami_libs.common.type"), 110, sort = compareBy { it.kind }) { _, c, e -> val look = Vocabulary.ledger(e.kind); val x = c.x + Draw.leadIcon(g, look.icon, c.x, c.centerY) + 2; fitText(x, c.y + 3, c.right - x, look.label, Palette.text, key = "lg:${e.kind}:${c.y}") },
            Column<LedgerLine>(tr("kami_libs.common.details"), -1) { _, c, e -> fitText(c.x, c.y + 3, c.w, listOf(e.actor, noteLabel(e.note)).filter { it.isNotEmpty() }.joinToString(" · ")) },
            Column<LedgerLine>(tr("kami_libs.common.amount"), 70, Align.RIGHT, compareBy { it.amount }) { _, c, e -> moneyRight(c.right, c.y + 3, e.amount, signed = true) },
            Column<LedgerLine>(tr("kami_libs.common.balance"), 80, Align.RIGHT, compareBy { it.balance }) { _, c, e -> moneyRight(c.right, c.y + 3, e.balance) }
        ) }, rows, table, { "${it.at}|${it.kind}|${it.amount}|${it.balance}|${it.actor}" }, emptyText = tr("kami_libs.table.empty"), loading = stale(ClaimsStore.LEDGER))
        ui.staleRows(tableRect, stale(ClaimsStore.LEDGER))
    }
}

class StatisticsPage(app: ClaimsApp) : ClaimsPage(app) {
    private val rangeOptions = Memo()
    private val statTabs = Memo()
    override val title get() = tr("kami_libs.common.statistics")
    override val sections = listOf("history")
    override val help get() = listOf(
        Callout("stats:range", tr("kami_claims.stats.help.range"), tr("kami_claims.stats.help.range.desc")),
        Callout("stats:chart", tr("kami_claims.stats.help.chart"), tr("kami_claims.stats.help.chart.desc"))
    )
    private var tab = 0
    private var range = 30

    override fun draw(ui: Ui, r: Rect) {
        val tabs = r.top(CONTROL_H)
        ui.subTabs(tabs.dropRight(170), statTabs.of(Format.locale) { listOf(TabItem(tr("kami_libs.flow.treasury"), Icons.TREASURY), TabItem(tr("kami_claims.stats.tab.flows"), Icons.SCALES), TabItem(tr("kami_claims.nav.group.territory"), Icons.AREA), TabItem(tr("kami_claims.stats.tab.population"), Icons.PEOPLE)) }, tab, "stat-tabs")?.let { tab = it }
        val rangeRect = tabs.right(160)
        ui.anchor("stats:range", rangeRect)
        ui.segmented(rangeRect, rangeOptions.of(Format.locale) { listOf(7, 30, 90).map { Option(it, Format.days(it.toLong())) } }, range, key = "range")?.let { range = it }
        val loaded = ClaimsStore.history
        val days = loaded.orEmpty().takeLast(range)
        val area = r.dropTop(CONTROL_H + 6)
        ui.anchor("stats:chart", area)
        if (loaded == null) {
            ui.skeleton(area.dropBottom(58, 6))
            ui.skeletonLines(ui.card(area.bottom(58), tr("kami_claims.stats.insights"), Icons.STAR), 3)
            return
        }
        if (days.size < 2) {
            ui.emptyState(area, tr("kami_libs.chart.no_history"), tr("kami_claims.stats.empty.desc"), Illustrations.FOG)
            return
        }
        val labels = days.map { tr("kami_claims.chart.day", Format.number(it.day)) }
        val chart = area.dropBottom(58, 6)
        val insight = area.bottom(58)
        when (tab) {
            0 -> ui.lineChart(chart, listOf(Series(tr("kami_libs.flow.treasury"), days.map { it.treasury }, Palette.money, true)), labels, key = "s-treasury")
            1 -> ui.lineChart(chart, listOf(
                Series(tr("kami_claims.ledger.plot_tax"), days.map { it.income }, Palette.success), Series(tr("kami_claims.ledger.tribute_in"), days.map { it.tributeIn }, Palette.geoProvince),
                Series(tr("kami_claims.ledger.upkeep"), days.map { it.upkeep }, Palette.danger), Series(tr("kami_claims.ledger.job_pay"), days.map { it.jobs }, Palette.warning)
            ), labels, key = "s-flows")
            2 -> ui.lineChart(chart, listOf(Series(tr("kami_claims.nav.chunks"), days.map { it.chunks.toLong() }, Palette.info, true), Series(tr("kami_claims.stats.in_debt"), days.map { it.debtChunks.toLong() }, Palette.danger)), labels, key = "s-land")
            else -> ui.lineChart(chart, listOf(Series(tr("kami_claims.nav.citizens"), days.map { it.members.toLong() }, Palette.success, true), Series(tr("kami_claims.stats.rented_plots"), days.map { it.plots.toLong() }, Palette.money)), labels, key = "s-people")
        }
        val card = ui.card(insight, tr("kami_claims.stats.insights"), Icons.STAR)
        val first = days.first()
        val last = days.last()
        val lines = listOf(
            tr("kami_claims.stats.insight.treasury", Format.days(days.size.toLong()), change(first.treasury, last.treasury)),
            tr("kami_claims.stats.insight.flows", change(first.upkeep, last.upkeep), change(first.income, last.income)),
            tr("kami_claims.stats.insight.people", change(first.chunks.toLong(), last.chunks.toLong()), change(first.members.toLong(), last.members.toLong()))
        )
        lines.forEachIndexed { i, s -> ui.fitText(card.x, card.y + i * 11, card.w, s, Palette.textSecondary, key = "econ-line$i") }
    }

    private fun change(a: Long, b: Long): String {
        if (a == b) return tr("kami_claims.stats.change.same", Format.number(b))
        if (a == 0L) return tr("kami_claims.stats.change", Format.number(a), Format.number(b))
        val pct = Format.percent((b - a).toDouble() / a).let { if (b > a) "+$it" else it }
        return tr("kami_claims.stats.change.percent", Format.number(a), Format.number(b), pct)
    }
}
