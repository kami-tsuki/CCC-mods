package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.app.HomesCard
import kami.claims.client.app.Tenure
import kami.claims.client.app.Vocabulary
import kami.claims.client.store.ClientResearch
import kami.claims.net.DayLine
import kami.claims.net.GoalLine
import kami.libs.ui.anim.countUp
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.text.trJson
import kami.libs.ui.widget.*

class DashboardPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.dashboard")
    override val sections = listOf("dashboard")
    private val treasuryChart = Memo()
    private val abroadMemo = Memo()
    private val myPlots = Memo()

    private class MyPlot(val label: String, val text: String, val color: Int, val key: String)
    private val homes = HomesCard(app)
    override val help get() = listOf(
        Callout("dashboard:tiles", tr("kami_claims.dashboard.help.tiles"), tr("kami_claims.dashboard.help.tiles.desc")),
        Callout("dashboard:attention", tr("kami_claims.dashboard.attention"), tr("kami_claims.dashboard.help.attention.desc")),
        Callout("dashboard:chart", tr("kami_claims.dashboard.help.chart"), tr("kami_claims.dashboard.chart.help")),
        Callout("dashboard:goals", tr("kami_claims.dashboard.goals"), tr("kami_claims.dashboard.help.goals.desc"))
    )

    override fun actionsWidth() = buttonWidth(tr("kami_claims.money.deposit"), Icons.DEPOSIT)

    override fun actions(ui: Ui, r: Rect) {
        if (ui.edgeButton(r, tr("kami_claims.money.deposit"), Icons.DEPOSIT, ButtonStyle.PRIMARY, key = "dash-deposit")) Dialogs.money(app, true)
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val net = info.income - info.upkeep - info.jobs
        val history = snap.history
        val treasury = treasuryChart.of(history, info.treasury, net, Format.locale) { TreasuryChart(history, info.treasury, net) }
        ui.anchor("dashboard:tiles", r.top(TILE_H))
        val weekAgo = history.getOrNull(history.size - 8)?.treasury
        val debt = info.claimList.count { it.debt > 0 }
        ui.kpiRow(r.top(TILE_H), listOf(
            KpiTile(tr("kami_claims.kpi.treasury"), Format.number(ui.countUp("dashboard-treasury", info.treasury)), Icons.TREASURY, Palette.money,
                trend = weekAgo?.let { Trend(info.treasury - it, tr("kami_claims.kpi.treasury.week", Format.signed(info.treasury - it))) }, sub = if (weekAgo == null) tr("kami_claims.kpi.treasury.no_history") else null,
                spark = treasury.spark, flashValue = info.treasury, tip = Tip(tr("kami_claims.kpi.net.title"), listOf(tr("kami_claims.kpi.net.tax", Format.signedMoney(info.income)) to Palette.success, tr("kami_claims.kpi.net.upkeep", Format.signedMoney(-info.upkeep)) to Palette.danger, tr("kami_claims.kpi.net.wages", Format.signedMoney(-info.jobs)) to Palette.danger)), onClick = { app.navigate(Route("budget")) }),
            KpiTile(tr("kami_claims.help.term.runway"), app.runwayText(info.treasury, net), Icons.CLOCK, app.runwayColor(info.treasury, net),
                sub = tr("kami_claims.dashboard.runway.sub", Format.duration(info.nextBilling)), tip = Tip.text(tr("kami_claims.kpi.runway.tooltip"), tr("kami_claims.help.term.runway")), onClick = { app.navigate(Route("budget")) }),
            KpiTile(tr("kami_claims.kpi.land"), Format.number(info.chunks), Icons.AREA, if (debt > 0) Palette.danger else Palette.text,
                sub = if (debt > 0) tr("kami_claims.common.in_debt_x", Format.number(debt)) else tr("kami_claims.dashboard.land.free", Format.number(info.free), Format.number(info.freeAllowed)),
                tip = Tip.text(tr("kami_claims.dashboard.land.tooltip"), tr("kami_claims.kpi.land")), onClick = { app.navigate(Route("chunks")) })
        ) + listOfNotNull(levelTile(ui)), key = "dashboard-kpi")
        val (left, right) = r.dropTop(TILE_H + 6).columns(listOf(1.35f, 1f), 6)
        val lf = Flow(left, 6)
        val alerts = app.visibleAlerts()
        val attentionH = if (alerts.isEmpty()) 40 else (alerts.sumOf { app.alertHeight(it, left.w - 12) } + 28).coerceAtMost((left.h * 0.55).toInt())
        val att = lf.take(attentionH)
        ui.anchor("dashboard:attention", att)
        val body = ui.card(att, tr("kami_claims.dashboard.attention"), Icons.BELL, if (alerts.any { it.severity == "DANGER" }) Severity.DANGER else if (alerts.isNotEmpty()) Severity.WARNING else null, trailing = if (alerts.isEmpty()) null else Format.number(alerts.size))
        if (alerts.isEmpty()) {
            val x = body.x + Draw.leadIcon(ui.g, Icons.CHECK, body.x, body.y + 6) + 2
            Draw.text(ui.g, tr("kami_claims.dashboard.attention.empty"), x, body.y + 2, Palette.success)
        } else ui.scroll("attention", body, alerts.sumOf { app.alertHeight(it, body.w) }) { area ->
            var y = area.y
            alerts.forEach { a -> y += app.alertCard(ui, Rect(area.x, y, area.w, 0), a) }
        }
        val chart = lf.remaining()
        ui.anchor("dashboard:chart", chart)
        val cb = ui.card(chart, tr("kami_claims.dashboard.chart", Format.days(history.size.coerceAtLeast(1).toLong())), Icons.STATS, help = tr("kami_claims.dashboard.chart.help"))
        ui.lineChart(cb, treasury.series, treasury.labels, key = "treasury-chart")
        val rf = Flow(right, 6)
        val goals = snap.goals.take(GOAL_COUNT)
        if (goals.isNotEmpty() && !ClientClaims.prefs.hiddenSteps) {
            val goalsRect = rf.take(24 + goals.size * GOAL_H)
            ui.anchor("dashboard:goals", goalsRect)
            val sb = ui.card(goalsRect, tr("kami_claims.dashboard.goals"), Icons.CHECK)
            goalRows(ui, sb, goals)
            val hide = tr("kami_claims.dashboard.goals.hide")
            if (ui.link(goalsRect.right - Draw.width(hide) - 14, goalsRect.y + 5, hide, key = "hide-goals")) { ClientClaims.prefs.hiddenSteps = true; ClientClaims.savePrefs() }
        }
        me(ui, rf)
        val abroad = abroadMemo.of(snap.homes, info.name) { snap.homes.filter { it.country != info.name } }
        if (abroad.isNotEmpty()) homes.draw(ui, rf.take(homes.height(abroad)), abroad, "dash-homes")
        if (ClientResearch.state.country.isNotEmpty()) ui.progressCard(rf.take(progressCardHeight()))
    }

    private fun goalRows(ui: Ui, area: Rect, goals: List<GoalLine>) {
        goals.forEachIndexed { i, goal ->
            val row = Rect(area.x, area.y + i * GOAL_H, area.w, GOAL_H - 2)
            val linked = goal.page.isNotEmpty()
            val hit = ui.clickable("goal-row:$i", row, linked)
            if (linked && ui.hovering(row)) Draw.fill(ui.g, row, Palette.hover)
            ui.progressBar(Rect(row.x + 2, row.y + 2, row.w - 4, PROGRESS_LABELLED_H), goal.value, goal.max, trJson(goal.text), key = "goal:$i")
            if (hit) app.navigate(Route(goal.page))
        }
    }

    private fun levelTile(ui: Ui): KpiTile? {
        val s = ClientResearch.state
        if (s.country.isEmpty()) return null
        val xp = ui.countUp("dashboard-xp", s.xp)
        return KpiTile(tr("kami_libs.common.level"), s.level.toString(), Icons.STAR, Palette.brass, sub = xpText(xp), onClick = { app.navigate(Route("levels")) })
    }

    private fun me(ui: Ui, f: Flow) {
        val info = info ?: return
        val mine = info.members.firstOrNull { it.id == snap.me }
        val plots = myPlots.of(info.claimList, snap.me, Format.locale, System.currentTimeMillis() / 60_000) {
            info.claimList.filter { it.ownerId == snap.me && (it.state != "active" || it.rentDebt > 0) }.take(2).map { p ->
                val note = Tenure.note(p, p.state, p.until, p.rentDebt)
                MyPlot(tr("kami_claims.plot.at", p.x, p.z), note.ifEmpty { Format.perDay(Format.money(p.rent.toLong())) }, Tenure.severity(p.state, p.rentDebt).color, "myplot:${p.x}:${p.z}")
            }
        }
        val rows = 2 + plots.size
        val box = f.take(24 + rows * 12)
        val b = ui.card(box, tr("kami_libs.common.you"), Icons.PERSON)
        val flow = Flow(b, 1)
        val rank = Vocabulary.rank(info.rank)
        ui.property(flow.take(11), tr("kami_libs.common.rank"), rank.label, rank.color)
        val job = mine?.job?.takeIf { it.isNotEmpty() }
        val jobLine = job?.let { j -> info.jobList.firstOrNull { it.name == j } }
        ui.property(flow.take(11), tr("kami_libs.common.job"), if (job == null) tr("kami_libs.common.none") else "${Vocabulary.job(job)} · ${mine.done}/${jobLine?.quota ?: 0} · ${Format.money(jobLine?.pay?.toLong() ?: 0)}", if (job == null) Palette.textMuted else Palette.text)
        plots.forEach { ui.property(flow.take(11), it.label, it.text, it.color, key = it.key) }
    }
}

private const val TILE_H = 44
private const val GOAL_COUNT = 3
private const val GOAL_H = 26
private const val FORECAST_DAYS = 14

private class TreasuryChart(history: List<DayLine>, treasury: Long, net: Long) {
    val balances = history.map { it.treasury }
    val spark = balances.takeLast(14)
    val series = listOf(Series(tr("kami_claims.kpi.treasury"), balances + treasury + forecast(treasury, net, FORECAST_DAYS), Palette.money, area = true, dashedFrom = balances.size))
    val labels = history.map { tr("kami_claims.chart.day", Format.number(it.day)) } + List(FORECAST_DAYS) { tr("kami_libs.time.in", Format.days(it + 1L)) }
}
