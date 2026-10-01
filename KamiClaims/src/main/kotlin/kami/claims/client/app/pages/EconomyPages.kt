package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Illustrations
import kami.claims.client.app.Vocabulary
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

class BudgetPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.budget")
    override val sections = listOf("history")
    override val help get() = listOf(
        Callout("budget:income", tr("kami_claims.budget.income"), tr("kami_claims.budget.help.income")),
        Callout("budget:spending", tr("kami_claims.budget.spending"), tr("kami_claims.budget.help.spending")),
        Callout("budget:forecast", tr("kami_claims.budget.forecast"), tr("kami_claims.budget.help.forecast")),
        Callout("budget:whatif", tr("kami_claims.budget.whatif"), tr("kami_claims.budget.help.whatif"))
    )
    private var whatIfTax = -1

    override fun actionsWidth() = buttonWidth(tr("kami_claims.money.deposit"), Icons.DEPOSIT) + buttonWidth(tr("kami_libs.common.withdraw"), Icons.WITHDRAW) + 4

    override fun actions(ui: Ui, r: Rect) {
        val row = Row(r)
        if (ui.edgeButton(row, tr("kami_claims.money.deposit"), Icons.DEPOSIT, ButtonStyle.PRIMARY, key = "b-dep")) Dialogs.money(app, true)
        if (ui.edgeButton(row, tr("kami_libs.common.withdraw"), Icons.WITHDRAW, enabled = can("withdraw"), disabledReason = lock("withdraw"), key = "b-wd")) Dialogs.money(app, false)
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val tributeIn = info.provinces.sumOf { if (it.mode == "percent") (it.income * it.amount).toLong() else it.amount.toLong() }
        val tributeOut = if (info.parent.isEmpty()) 0L else if (info.taxMode == "percent") (info.income * info.taxAmount).toLong() else info.taxAmount.toLong()
        val income = info.income + tributeIn
        val spending = info.upkeep + info.jobs + tributeOut
        val net = income - spending
        ui.kpiRow(r.top(44), listOf(
            KpiTile(tr("kami_claims.budget.income_day"), Format.number(income), Icons.INCOME, Palette.success),
            KpiTile(tr("kami_claims.budget.spending_day"), Format.number(spending), Icons.EXPENSE, Palette.danger),
            KpiTile(tr("kami_claims.kpi.net"), Format.signed(net), Icons.SCALES, if (net >= 0) Palette.success else Palette.danger),
            KpiTile(tr("kami_claims.help.term.runway"), app.runwayText(info.treasury, net), Icons.CLOCK, app.runwayColor(info.treasury, net))
        ), key = "budget-kpi")
        val rest = r.dropTop(50)
        val (left, right) = rest.columns(2, 6)
        val lf = Flow(left, 6)
        val inc = lf.take(24 + 2 * (TABLE_ROW_H + 1) + info.provinces.size * 11)
        ui.anchor("budget:income", inc)
        val ib = ui.card(inc, tr("kami_claims.budget.income"), Icons.INCOME)
        val fi = Flow(ib, 1)
        line(ui, fi.take(TABLE_ROW_H), Icons.HOUSE, tr("kami_claims.ledger.plot_tax"), tr("kami_claims.budget.plot_tax.detail", trn("kami_claims.unit.plot", info.claimList.count { it.owner.isNotEmpty() }), Format.money(info.citizenRent.toLong())), info.income, Palette.success)
        line(ui, fi.take(TABLE_ROW_H), Icons.CHAIN, tr("kami_claims.ledger.tribute_in"), trn("kami_claims.unit.province", info.provinces.size), tributeIn, Palette.success)
        info.provinces.forEach { p -> sub(ui, fi.take(10), p.name, Vocabulary.tribute(p.mode, p.amount), if (p.mode == "percent") (p.income * p.amount).toLong() else p.amount.toLong()) }
        val spend = lf.take(24 + 3 * (TABLE_ROW_H + 1) + info.breakdown.size * 11)
        ui.anchor("budget:spending", spend)
        val sb = ui.card(spend, tr("kami_claims.budget.spending"), Icons.EXPENSE)
        val fs = Flow(sb, 1)
        line(ui, fs.take(TABLE_ROW_H), Icons.AREA, tr("kami_claims.ledger.upkeep"), tr("kami_claims.budget.upkeep.detail", trn("kami_claims.unit.chunk", info.chunks - info.free)), info.upkeep, Palette.danger)
        info.breakdown.sortedByDescending { it.perDay }.forEach { b -> sub(ui, fs.take(10), Vocabulary.type(b.type).label, trn("kami_claims.unit.chunk", b.count), b.perDay.toLong(), Vocabulary.type(b.type).color) }
        line(ui, fs.take(TABLE_ROW_H), Icons.TOOL, tr("kami_claims.ledger.job_pay"), tr("kami_claims.budget.wages.detail", Format.percent(snap.jobShare / 100.0), Format.money(info.income * snap.jobShare / 100)), info.jobs, Palette.danger)
        line(ui, fs.take(TABLE_ROW_H), Icons.CHAIN, tr("kami_claims.ledger.tribute_out"), info.parent.ifEmpty { tr("kami_claims.budget.independent") }, tributeOut, Palette.danger)
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
        val history = snap.history.takeLast(30).map { it.treasury }
        ui.lineChart(fb.dropBottom(12), listOf(Series(tr("kami_claims.kpi.treasury"), history + info.treasury + forecast(info.treasury, net, 30), Palette.money, true, history.size)),
            snap.history.takeLast(30).map { tr("kami_claims.chart.day", Format.number(it.day)) } + tr("kami_claims.chart.today") + List(30) { tr("kami_libs.time.in", Format.days(it + 1L)) }, key = "forecast")
        val summary = if (net >= 0) tr("kami_claims.budget.forecast.grows", Format.money(net * 30)) else tr("kami_claims.budget.forecast.empty", Format.days(info.treasury / -net))
        Draw.text(ui.g, Draw.fit(summary, fb.w), fb.x, fb.bottom - 9, if (net >= 0) Palette.success else Palette.warning)
        val flows = rf.remaining()
        val flowBody = ui.card(flows, tr("kami_claims.budget.flows"), Icons.SCALES, help = tr("kami_claims.budget.flows.help"))
        val days = snap.history.takeLast(20)
        ui.barChart(flowBody, days.map { it.income + it.tributeIn + it.deposits }, days.map { it.upkeep + it.jobs + it.tributeOut + it.withdrawals }, days.map { tr("kami_claims.chart.day", Format.number(it.day)) })
    }

    private fun line(ui: Ui, r: Rect, icon: Icon, label: String, detail: String, amount: Long, color: Int) {
        val x = r.x + Draw.leadIcon(ui.g, icon, r.x, r.centerY) + 2
        val y = r.y + 3
        val labelW = Draw.width(label, TextStyle.HEADING)
        Draw.text(ui.g, label, x, y, TextStyle.HEADING)
        Draw.text(ui.g, Draw.fit(detail, r.right - x - labelW - 74), x + labelW + 4, y, Palette.textMuted)
        Draw.textRight(ui.g, Format.money(amount), r.right, y, color = color)
    }

    private fun sub(ui: Ui, r: Rect, label: String, detail: String, amount: Long, color: Int = Palette.textSecondary) {
        Draw.fill(ui.g, Rect(r.x + 5, r.y + 3, 3, 3), color)
        Draw.text(ui.g, Draw.fit("$label  ·  $detail", r.w - 86), r.x + 16, r.y, Palette.textSecondary)
        Draw.textRight(ui.g, Format.number(amount), r.right, r.y, Palette.textMuted)
    }

}

class LedgerPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.ledger")
    override val sections = listOf("ledger")
    override val help get() = listOf(
        Callout("ledger:filters", tr("kami_claims.ledger.help.filter"), tr("kami_claims.ledger.help.filter.desc")),
        Callout("ledger:table", tr("kami_claims.ledger.help.table"), tr("kami_claims.ledger.help.table.desc"))
    )
    private val table = TableState<LedgerLine>()
    private var kind = "all"

    override fun draw(ui: Ui, r: Rect) {
        var rows: List<LedgerLine> = emptyList()
        val tableRect = ui.filterBar(r, "ledger:filters") { bar ->
            rows = ui.filtered("ledger-filtered", listOf(kind, snap.ledger)) { snap.ledger.filter { kind == "all" || it.kind == kind } }
            val options = listOf(Option("all", tr("kami_claims.ledger.all"), Icons.LEDGER)) + Vocabulary.ledger.map { (k, look) -> Option(k, look.label, look.icon, look.description) }
            ui.select(bar.left(160), options, kind, key = "ledger-kind")?.let { kind = it }
            val totalIn = rows.filter { it.amount > 0 }.sumOf { it.amount }
            val totalOut = rows.filter { it.amount < 0 }.sumOf { it.amount }
            Draw.text(ui.g, trn("kami_claims.unit.entry", rows.size), bar.x + 168, bar.y + 5, Palette.textMuted)
            ui.moneyRight(bar.right - 130, bar.y + 5, totalIn, signed = true)
            ui.moneyRight(bar.right - 40, bar.y + 5, totalOut, signed = true)
            if (ui.iconButton(Rect(bar.right - CONTROL_H, bar.y, CONTROL_H, CONTROL_H), Icons.COPY, tr("kami_claims.ledger.copy.tooltip"), key = "ledger-copy")) {
                Minecraft.getInstance().keyboardHandler.clipboard = rows.joinToString("\n") { "${Format.exact(it.at)}\t${Vocabulary.ledger(it.kind).label}\t${it.amount}\t${it.balance}\t${it.actor}\t${it.note}" }
                app.toast(Severity.SUCCESS, tr("kami_claims.ledger.copied"), trn("kami_claims.unit.line", rows.size))
            }
        }
        ui.anchor("ledger:table", tableRect)
        if (snap.ledger.isEmpty()) {
            ui.emptyState(tableRect, tr("kami_claims.ledger.empty.title"), tr("kami_claims.ledger.empty.desc"), Illustrations.TREASURY)
            return
        }
        ui.table(tableRect, listOf(
            Column<LedgerLine>(tr("kami_claims.ledger.col.when"), 70, sort = compareBy { it.at }) { _, c, e -> Draw.text(g, Format.ago(e.at), c.x, c.y + 3, Palette.textMuted); tooltip("at:${e.at}", c, Format.exact(e.at)) },
            Column<LedgerLine>(tr("kami_claims.ledger.col.kind"), 110, sort = compareBy { it.kind }) { _, c, e -> val look = Vocabulary.ledger(e.kind); val x = c.x + Draw.leadIcon(g, look.icon, c.x, c.centerY) + 2; Draw.text(g, Draw.fit(look.label, c.right - x), x, c.y + 3, Palette.text) },
            Column.text<LedgerLine>(tr("kami_claims.ledger.col.details"), -1, sortable = false) { e -> listOf(e.actor, e.note).filter { it.isNotEmpty() }.joinToString(" · ") },
            Column<LedgerLine>(tr("kami_claims.ledger.col.amount"), 70, Align.RIGHT, compareBy { it.amount }) { _, c, e -> moneyRight(c.right, c.y + 3, e.amount, signed = true) },
            Column<LedgerLine>(tr("kami_claims.ledger.col.balance"), 80, Align.RIGHT, compareBy { it.balance }) { _, c, e -> moneyRight(c.right, c.y + 3, e.balance) }
        ), rows, table, { "${it.at}${it.kind}${it.amount}" }, emptyText = tr("kami_claims.ledger.empty.filtered"))
    }
}

class StatisticsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.statistics")
    override val sections = listOf("history")
    override val help get() = listOf(
        Callout("stats:range", tr("kami_claims.stats.help.range"), tr("kami_claims.stats.help.range.desc")),
        Callout("stats:chart", tr("kami_claims.stats.help.chart"), tr("kami_claims.stats.help.chart.desc"))
    )
    private var tab = 0
    private var range = 30

    override fun draw(ui: Ui, r: Rect) {
        val tabs = r.top(CONTROL_H)
        ui.subTabs(tabs.dropRight(170), listOf(TabItem(tr("kami_claims.kpi.treasury"), Icons.TREASURY), TabItem(tr("kami_claims.stats.tab.flows"), Icons.SCALES), TabItem(tr("kami_claims.nav.group.territory"), Icons.AREA), TabItem(tr("kami_claims.stats.tab.population"), Icons.PEOPLE)), tab, "stat-tabs")?.let { tab = it }
        val rangeRect = tabs.right(160)
        ui.anchor("stats:range", rangeRect)
        ui.segmented(rangeRect, listOf(7, 30, 90).map { Option(it, Format.days(it.toLong())) }, range, key = "range")?.let { range = it }
        val days = snap.history.takeLast(range)
        val area = r.dropTop(CONTROL_H + 6)
        ui.anchor("stats:chart", area)
        if (days.size < 2) {
            ui.emptyState(area, tr("kami_libs.chart.no_history"), tr("kami_claims.stats.empty.desc"), Illustrations.FOG)
            return
        }
        val labels = days.map { tr("kami_claims.chart.day", Format.number(it.day)) }
        val chart = area.dropBottom(58, 6)
        val insight = area.bottom(58)
        when (tab) {
            0 -> ui.lineChart(chart, listOf(Series(tr("kami_claims.kpi.treasury"), days.map { it.treasury }, Palette.money, true)), labels, key = "s-treasury")
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
        lines.forEachIndexed { i, s -> Draw.text(ui.g, Draw.fit(s, card.w), card.x, card.y + i * 11, Palette.textSecondary) }
    }

    private fun change(a: Long, b: Long): String {
        if (a == b) return tr("kami_claims.stats.change.same", Format.number(b))
        if (a == 0L) return tr("kami_claims.stats.change", Format.number(a), Format.number(b))
        val pct = Format.percent((b - a).toDouble() / a).let { if (b > a) "+$it" else it }
        return tr("kami_claims.stats.change.percent", Format.number(a), Format.number(b), pct)
    }
}
