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
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft
import kotlin.math.max

class BudgetPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Budget"
    override val sections = listOf("history")
    override val help = listOf(
        Callout("budget:income", "Income", "Where money comes from: plot tax and tribute from provinces."),
        Callout("budget:spending", "Spending", "Upkeep of your land by type, wages and tribute you pay."),
        Callout("budget:forecast", "Forecast", "The treasury over the next 30 days if nothing changes."),
        Callout("budget:whatif", "What if", "Try a different plot tax and see the effect before changing it.")
    )
    private var whatIfTax = -1

    override fun actions(ui: Ui, r: Rect) {
        val dw = buttonWidth("Deposit", Icons.DEPOSIT)
        val ww = buttonWidth("Withdraw", Icons.WITHDRAW)
        if (ui.button(Rect(r.right - dw, r.y + 1, dw, 18), "Deposit", Icons.DEPOSIT, ButtonStyle.PRIMARY, key = "b-dep")) Dialogs.money(app, true)
        if (ui.button(Rect(r.right - dw - ww - 4, r.y + 1, ww, 18), "Withdraw", Icons.WITHDRAW, enabled = can("withdraw"), disabledReason = lock("withdraw"), key = "b-wd")) Dialogs.money(app, false)
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val tributeIn = info.provinces.sumOf { if (it.mode == "percent") (it.income * it.amount).toLong() else it.amount.toLong() }
        val tributeOut = if (info.parent.isEmpty()) 0L else if (info.taxMode == "percent") (info.income * info.taxAmount).toLong() else info.taxAmount.toLong()
        val income = info.income + tributeIn
        val spending = info.upkeep + info.jobs + tributeOut
        val net = income - spending
        val tiles = r.top(46).columns(4, 6)
        ui.statTile(tiles[0], "Income / day", Format.number(income), Icons.INCOME, Palette.success)
        ui.statTile(tiles[1], "Spending / day", Format.number(spending), Icons.EXPENSE, Palette.danger)
        ui.statTile(tiles[2], "Net / day", Format.signed(net), Icons.SCALES, if (net >= 0) Palette.success else Palette.danger)
        ui.statTile(tiles[3], "Runway", app.runwayText(info.treasury, net), Icons.CLOCK, app.runwayColor(info.treasury, net))
        val rest = r.dropTop(52)
        val (left, right) = rest.columns(2, 8)
        val lf = Flow(left, 6)
        val inc = lf.take(26 + 2 * 12 + info.provinces.size * 11 + 8)
        ui.anchor("budget:income", inc)
        val ib = ui.card(inc, "Income", Icons.INCOME)
        val fi = Flow(ib, 1)
        line(ui, fi.take(11), Icons.HOUSE, "Plot tax", "${info.claimList.count { it.owner.isNotEmpty() }} plots × ${info.tax} ◎", info.income, Palette.success)
        line(ui, fi.take(11), Icons.CHAIN, "Tribute from provinces", "${info.provinces.size} provinces", tributeIn, Palette.success)
        info.provinces.forEach { p -> sub(ui, fi.take(10), p.name, Vocabulary.tribute(p.mode, p.amount), if (p.mode == "percent") (p.income * p.amount).toLong() else p.amount.toLong()) }
        val spend = lf.take(26 + 3 * 12 + info.breakdown.size * 10 + 10)
        ui.anchor("budget:spending", spend)
        val sb = ui.card(spend, "Spending", Icons.EXPENSE)
        val fs = Flow(sb, 1)
        line(ui, fs.take(11), Icons.AREA, "Upkeep", "${info.chunks - info.free} paid chunks", info.upkeep, Palette.danger)
        info.breakdown.sortedByDescending { it.perDay }.forEach { b -> sub(ui, fs.take(10), Vocabulary.type(b.type).label, "${b.count} chunks", b.perDay.toLong(), Vocabulary.type(b.type).color) }
        line(ui, fs.take(11), Icons.TOOL, "Wages", "cap ${snap.jobShare}% of income = ${info.income * snap.jobShare / 100} ◎", info.jobs, Palette.danger)
        line(ui, fs.take(11), Icons.CHAIN, "Tribute to overlord", info.parent.ifEmpty { "independent" }, tributeOut, Palette.danger)
        val what = lf.remaining()
        ui.anchor("budget:whatif", what)
        val wb = ui.card(what, "What if", Icons.SEARCH, help = "Nothing is changed here. Use Plot law to change the tax.")
        if (whatIfTax < 0) whatIfTax = info.tax
        val wf = Flow(wb, 4)
        ui.fieldLabel(wf.take(9), "Plot tax", "$whatIfTax ◎ per plot per day")
        ui.slider(wf.take(CONTROL_H), whatIfTax.toDouble(), 0.0, max(20.0, info.tax * 3.0), 1.0, format = { "${it.toInt()} ◎" }, key = "whatif")?.let { whatIfTax = it.toInt() }
        val plots = info.claimList.count { it.owner.isNotEmpty() }
        val newNet = net + (whatIfTax - info.tax).toLong() * plots
        ui.property(wf.take(11), "Net per day", "${Format.signed(net)} → ${Format.signed(newNet)}", if (newNet >= 0) Palette.success else Palette.danger)
        ui.property(wf.take(11), "Runway", "${app.runwayText(info.treasury, net)} → ${app.runwayText(info.treasury, newNet)}", app.runwayColor(info.treasury, newNet))
        if (ui.link(wb.x, wf.rest.y + 2, "Change plot tax in Plot law", key = "to-plotlaw")) app.navigate(Route("plotlaw"))
        val rf = Flow(right, 6)
        val fc = rf.take((right.h * 0.55).toInt())
        ui.anchor("budget:forecast", fc)
        val fb = ui.card(fc, "Forecast, 30 days", Icons.STATS, help = "Based on today's income and spending. Real results change with taxes paid and land claimed.")
        val history = snap.history.takeLast(30).map { it.treasury }
        ui.lineChart(fb.dropBottom(12), listOf(Series("Treasury", history + info.treasury + forecast(info.treasury, net, 30), Palette.money, true, history.size)), snap.history.takeLast(30).map { "day ${it.day}" } + "today" + List(30) { "in ${it + 1}d" }, key = "forecast")
        val summary = if (net >= 0) "At this rate the treasury grows by ${Format.number(net * 30)} ◎ in 30 days." else "At this rate the treasury is empty in ${info.treasury / -net} days."
        Draw.text(ui.g, Draw.fit(summary, fb.w), fb.x, fb.bottom - 9, if (net >= 0) Palette.success else Palette.warning)
        val flows = rf.remaining()
        val flowBody = ui.card(flows, "Daily flows", Icons.SCALES, help = "Green: money in. Red: money out. One bar per billing day.")
        val days = snap.history.takeLast(20)
        ui.barChart(flowBody, days.map { it.income + it.tributeIn + it.deposits }, days.map { it.upkeep + it.jobs + it.tributeOut + it.withdrawals }, days.map { "day ${it.day}" })
    }

    private fun line(ui: Ui, r: Rect, icon: kami.libs.ui.style.Icon, label: String, detail: String, amount: Long, color: Int) {
        Draw.icon(ui.g, icon, r.x - 2, r.y - 3, 12)
        Draw.text(ui.g, label, r.x + 12, r.y, TextStyle.HEADING)
        Draw.text(ui.g, Draw.fit(detail, r.w - Draw.width(label, TextStyle.HEADING) - 70), r.x + 16 + Draw.width(label, TextStyle.HEADING), r.y, Palette.textMuted)
        Draw.textRight(ui.g, "${Format.number(amount)} ◎", r.right, r.y, color = color)
    }

    private fun sub(ui: Ui, r: Rect, label: String, detail: String, amount: Long, color: Int = Palette.textSecondary) {
        Draw.fill(ui.g, Rect(r.x + 4, r.y + 3, 3, 3), color)
        Draw.text(ui.g, Draw.fit("$label  ·  $detail", r.w - 70), r.x + 12, r.y, Palette.textSecondary)
        Draw.textRight(ui.g, Format.number(amount), r.right, r.y, Palette.textMuted)
    }
}

class LedgerPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Ledger"
    override val sections = listOf("ledger")
    override val help = listOf(Callout("ledger:filters", "Filters", "Show only one kind of transaction."), Callout("ledger:table", "Transactions", "Every coin that entered or left the treasury, newest first. Click a column to sort."))
    private val table = TableState<LedgerLine>()
    private var kind = "all"

    override fun draw(ui: Ui, r: Rect) {
        val rows = snap.ledger.filter { kind == "all" || it.kind == kind }
        val bar = r.top(20)
        ui.anchor("ledger:filters", bar)
        val options = listOf(Option("all", "All kinds", Icons.LEDGER)) + Vocabulary.ledger.map { (k, look) -> Option(k, look.label, look.icon, look.description) }
        ui.select(bar.left(160), options, kind, key = "ledger-kind")?.let { kind = it }
        val totalIn = rows.filter { it.amount > 0 }.sumOf { it.amount }
        val totalOut = rows.filter { it.amount < 0 }.sumOf { it.amount }
        Draw.text(ui.g, "${rows.size} entries", bar.x + 168, bar.y + 6, Palette.textMuted)
        ui.moneyRight(bar.right - 130, bar.y + 6, totalIn, signed = true)
        ui.moneyRight(bar.right - 40, bar.y + 6, totalOut, signed = true)
        if (ui.iconButton(Rect(bar.right - 20, bar.y + 1, 18, 18), Icons.COPY, "Copy as text", key = "ledger-copy")) {
            Minecraft.getInstance().keyboardHandler.clipboard = rows.joinToString("\n") { "${Format.exact(it.at)}\t${Vocabulary.ledger(it.kind).label}\t${it.amount}\t${it.balance}\t${it.actor}\t${it.note}" }
            app.toast(Severity.SUCCESS, "Ledger copied", "${rows.size} lines are on your clipboard.")
        }
        val tr = r.dropTop(24)
        ui.anchor("ledger:table", tr)
        if (snap.ledger.isEmpty()) {
            ui.emptyState(tr, "No transactions yet", "Deposits, upkeep, taxes and wages are written here as they happen.", Illustrations.TREASURY)
            return
        }
        ui.table(tr, listOf(
            Column<LedgerLine>("When", 70, sort = compareBy { it.at }) { _, c, e -> Draw.text(g, Format.ago(e.at), c.x, c.y + 4, Palette.textMuted); tooltip("at:${e.at}", c, Format.exact(e.at)) },
            Column<LedgerLine>("Kind", 110, sort = compareBy { it.kind }) { _, c, e -> val look = Vocabulary.ledger(e.kind); Draw.icon(g, look.icon, c.x - 2, c.y, 16); Draw.text(g, Draw.fit(look.label, c.w - 16), c.x + 15, c.y + 4, Palette.text) },
            Column.text<LedgerLine>("Details", -1, sortable = false) { e -> listOf(e.actor, e.note).filter { it.isNotEmpty() }.joinToString(" · ") },
            Column<LedgerLine>("Amount", 70, Align.RIGHT, compareBy { it.amount }) { _, c, e -> moneyRight(c.right, c.y + 4, e.amount, signed = true) },
            Column<LedgerLine>("Balance", 80, Align.RIGHT, compareBy { it.balance }) { _, c, e -> moneyRight(c.right, c.y + 4, e.balance) }
        ), rows, table, { "${it.at}${it.kind}${it.amount}" }, emptyText = "Nothing of this kind.")
    }
}

class StatisticsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Statistics"
    override val sections = listOf("history")
    override val help = listOf(Callout("stats:range", "Range", "How many days to show."), Callout("stats:chart", "Chart", "Hover the chart to read exact values."))
    private var tab = 0
    private var range = 30

    override fun draw(ui: Ui, r: Rect) {
        val tabs = r.top(20)
        ui.subTabs(tabs.dropRight(170), listOf(TabItem("Treasury", Icons.TREASURY), TabItem("Money flows", Icons.SCALES), TabItem("Territory", Icons.AREA), TabItem("Population", Icons.PEOPLE)), tab, "stat-tabs")?.let { tab = it }
        val rangeRect = tabs.right(160)
        ui.anchor("stats:range", rangeRect)
        ui.segmented(rangeRect, listOf(Option(7, "7d"), Option(30, "30d"), Option(90, "90d")), range, key = "range")?.let { range = it }
        val days = snap.history.takeLast(range)
        val area = r.dropTop(26)
        ui.anchor("stats:chart", area)
        if (days.size < 2) {
            ui.emptyState(area, "Not enough history yet", "Statistics are recorded once per day at billing. Come back tomorrow.", Illustrations.FOG)
            return
        }
        val labels = days.map { "day ${it.day}" }
        val chart = area.dropBottom(60, 6)
        val insight = area.bottom(60)
        when (tab) {
            0 -> ui.lineChart(chart, listOf(Series("Treasury", days.map { it.treasury }, Palette.money, true)), labels, key = "s-treasury")
            1 -> ui.lineChart(chart, listOf(
                Series("Plot tax", days.map { it.income }, Palette.success), Series("Tribute in", days.map { it.tributeIn }, Palette.geoProvince),
                Series("Upkeep", days.map { it.upkeep }, Palette.danger), Series("Wages", days.map { it.jobs }, Palette.warning)
            ), labels, key = "s-flows")
            2 -> ui.lineChart(chart, listOf(Series("Chunks", days.map { it.chunks.toLong() }, Palette.info, true), Series("In debt", days.map { it.debtChunks.toLong() }, Palette.danger)), labels, key = "s-land")
            else -> ui.lineChart(chart, listOf(Series("Citizens", days.map { it.members.toLong() }, Palette.success, true), Series("Rented plots", days.map { it.plots.toLong() }, Palette.money)), labels, key = "s-people")
        }
        val card = ui.card(insight, "Insights", Icons.STAR)
        val first = days.first()
        val last = days.last()
        val lines = listOf(
            "Treasury ${change(first.treasury, last.treasury)} over ${days.size} days.",
            "Upkeep per day ${change(first.upkeep, last.upkeep)}, plot tax ${change(first.income, last.income)}.",
            "Land ${change(first.chunks.toLong(), last.chunks.toLong())} chunks, citizens ${change(first.members.toLong(), last.members.toLong())}."
        )
        lines.forEachIndexed { i, s -> Draw.text(ui.g, Draw.fit(s, card.w), card.x, card.y + i * 11, Palette.textSecondary) }
    }

    private fun change(a: Long, b: Long): String {
        if (a == b) return "stayed at ${Format.number(b)}"
        val pct = if (a != 0L) " (${if (b > a) "+" else ""}${(b - a) * 100 / a}%)" else ""
        return "${if (b > a) "grew" else "fell"} from ${Format.number(a)} to ${Format.number(b)}$pct"
    }
}
