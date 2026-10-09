package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.app.ClaimsApp
import kami.claims.client.store.ClaimsStore
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.app.HomesCard
import kami.claims.client.app.Tenure
import kami.claims.client.app.Vocabulary
import kami.claims.client.store.ClientResearch
import kami.claims.net.DayLine
import kami.claims.net.GoalLine
import kami.claims.net.Info
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
    override val title get() = tr("kami_libs.common.overview")
    override val sections = listOf("dashboard")
    private val treasuryChart = Memo()
    private val abroadMemo = Memo()
    private val myPlots = Memo()

    private class Block(val h: Int, val draw: (Rect) -> Unit)
    private class MyPlot(val label: String, val text: String, val color: Int, val key: String)
    private val homes = HomesCard(app)
    override val help get() = listOf(
        Callout("dashboard:tiles", tr("kami_claims.dashboard.help.tiles"), tr("kami_claims.dashboard.help.tiles.desc")),
        Callout("dashboard:attention", tr("kami_claims.dashboard.attention"), tr("kami_claims.dashboard.help.attention.desc")),
        Callout("dashboard:chart", tr("kami_claims.dashboard.help.chart"), tr("kami_claims.dashboard.chart.help")),
        Callout("dashboard:goals", tr("kami_claims.dashboard.goals"), tr("kami_claims.dashboard.help.goals.desc"))
    )

    override fun actionsWidth() = buttonWidth(tr("kami_libs.common.deposit"), Icons.DEPOSIT)

    override fun actions(ui: Ui, r: Rect) {
        if (ui.edgeButton(r, tr("kami_libs.common.deposit"), Icons.DEPOSIT, ButtonStyle.PRIMARY, key = "dash-deposit")) Dialogs.money(app, true)
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val net = info.income - info.upkeep - info.jobs
        val loaded = ClaimsStore.history
        val history = loaded.orEmpty()
        val treasury = treasuryChart.of(history, info.treasury, net, Format.locale) { TreasuryChart(history, info.treasury, net) }
        val weekAgo = history.getOrNull(history.size - 8)?.treasury
        val debt = info.claimList.count { it.debt > 0 }
        val tiles = listOf(
            KpiTile(tr("kami_libs.flow.treasury"), Format.number(ui.countUp("dashboard-treasury", info.treasury)), Icons.TREASURY, Palette.money,
                trend = weekAgo?.let { Trend(info.treasury - it, tr("kami_claims.kpi.treasury.week", Format.signed(info.treasury - it))) }, sub = if (weekAgo == null && loaded != null) tr("kami_claims.kpi.treasury.no_history") else null,
                spark = treasury.spark, flashValue = info.treasury, tip = Tip(tr("kami_claims.kpi.net.title"), listOf(tr("kami_claims.kpi.net.tax", Format.signedMoney(info.income)) to Palette.success, tr("kami_claims.kpi.net.upkeep", Format.signedMoney(-info.upkeep)) to Palette.danger, tr("kami_claims.kpi.net.wages", Format.signedMoney(-info.jobs)) to Palette.danger)), onClick = { app.navigate(Route("budget")) }),
            KpiTile(tr("kami_claims.help.term.runway"), app.runwayText(info.treasury, net), Icons.CLOCK, app.runwayColor(info.treasury, net),
                sub = tr("kami_claims.dashboard.runway.sub", Format.duration(info.nextBilling)), tip = Tip.text(tr("kami_claims.kpi.runway.tooltip"), tr("kami_claims.help.term.runway")), onClick = { app.navigate(Route("budget")) }),
            KpiTile(tr("kami_claims.kpi.land"), Format.number(info.chunks), Icons.AREA, if (debt > 0) Palette.danger else Palette.text,
                sub = if (debt > 0) tr("kami_claims.common.in_debt_x", Format.number(debt)) else tr("kami_claims.dashboard.land.free", Format.number(info.free), Format.number(info.freeAllowed)),
                tip = Tip.text(tr("kami_claims.dashboard.land.tooltip"), tr("kami_claims.kpi.land")), onClick = { app.navigate(Route("chunks")) })
                ) + listOfNotNull(levelTile(ui), powerTile(info))
        val est = r.withWidth(r.w - 4)
        val split = tiles.size > 3 && est.w / tiles.size < 90
        val tilesH = if (split) TILE_H * 2 + 6 else TILE_H
        val wide = est.w >= 420
        val colW = if (wide) est.columns(listOf(1.35f, 1f), 6)[0].w else est.w
        val alerts = app.visibleAlerts()
        val attentionH = if (alerts.isEmpty()) 40 else (alerts.sumOf { app.alertHeight(it, colW - 12) } + 28).coerceAtMost(ATTENTION_MAX)
        val goals = if (ClientClaims.prefs.hiddenSteps) emptyList() else snap.goals.take(GOAL_COUNT)
        val plots = plotsOf(info)
        val abroad = abroadMemo.of(snap.homes, info.name) { snap.homes.filter { it.country != info.name } }
        val goalsH = if (goals.isEmpty()) 0 else 24 + goals.size * GOAL_H
        val meH = 24 + (2 + plots.size) * 12
        val homesH = if (abroad.isEmpty()) 0 else homes.height(abroad)
        val progressH = if (ClientResearch.state.country.isNotEmpty()) progressCardHeight() else 0
        val boostH = boostCardHeight()
        val goalsB = Block(goalsH) { box ->
            ui.anchor("dashboard:goals", box)
            val sb = ui.card(box, tr("kami_claims.dashboard.goals"), Icons.CHECK)
            goalRows(ui, sb, goals)
            val hide = tr("kami_claims.dashboard.goals.hide")
            if (ui.link(box.right - Draw.width(hide) - 14, box.y + 5, hide, key = "hide-goals")) { ClientClaims.prefs.hiddenSteps = true; ClientClaims.savePrefs() }
        }
        val meB = Block(meH) { me(ui, it, info, plots) }
        val homesB = Block(homesH) { homes.draw(ui, it, abroad, "dash-homes") }
        val progressB = Block(progressH) { ui.progressCard(it) }
        val boostB = Block(boostH) { ui.boostCard(it) }
        val attentionB = Block(attentionH) { box ->
            ui.anchor("dashboard:attention", box)
            val body = ui.card(box, tr("kami_claims.dashboard.attention"), Icons.BELL, if (alerts.any { it.severity == "DANGER" }) Severity.DANGER else if (alerts.isNotEmpty()) Severity.WARNING else null, trailing = if (alerts.isEmpty()) null else Format.number(alerts.size))
            if (alerts.isEmpty()) {
                val x = body.x + Draw.leadIcon(ui.g, Icons.CHECK, body.x, body.y + 6) + 2
                Draw.text(ui.g, tr("kami_claims.dashboard.attention.empty"), x, body.y + 2, Palette.success)
            } else ui.scroll("attention", body, alerts.sumOf { app.alertHeight(it, body.w) }) { area ->
                var y = area.y
                alerts.forEach { a -> y += app.alertCard(ui, Rect(area.x, y, area.w, 0), a) }
            }
        }
        val chartB = Block(if (wide) CHART_MIN else CHART_NARROW) { box ->
            ui.anchor("dashboard:chart", box)
            val cb = ui.card(box, tr("kami_claims.dashboard.chart", Format.days(history.size.coerceAtLeast(1).toLong())), Icons.STATS, help = tr("kami_claims.dashboard.chart.help"))
            if (loaded == null) ui.skeleton(cb) else ui.lineChart(cb, treasury.series, treasury.labels, key = "treasury-chart")
        }
        val right = listOf(goalsB, meB, homesB, progressB, boostB).filter { it.h > 0 }
        val rightH = right.sumOf { it.h + 6 }
        val bodyH = if (wide) maxOf(attentionH + 6 + CHART_MIN, rightH) else (listOf(attentionB, goalsB, meB, progressB, boostB, homesB, chartB).filter { it.h > 0 }).sumOf { it.h + 6 }
        ui.scroll("dashboard", r, tilesH + 6 + bodyH) { c ->
            val tilesRect = c.top(tilesH)
            ui.anchor("dashboard:tiles", tilesRect)
            if (split) {
                ui.kpiRow(tilesRect.top(TILE_H), tiles.take(3), key = "dashboard-kpi")
                ui.kpiRow(Rect(tilesRect.x, tilesRect.bottom - TILE_H, tilesRect.w, TILE_H), tiles.drop(3), key = "dashboard-kpi2")
            } else ui.kpiRow(tilesRect, tiles, key = "dashboard-kpi")
            val area = c.dropTop(tilesH + 6)
            if (wide) {
                val (left, rightCol) = area.columns(listOf(1.35f, 1f), 6)
                val lf = Flow(left, 6)
                attentionB.draw(lf.take(attentionH))
                chartB.draw(lf.remaining())
                val rf = Flow(rightCol, 6)
                right.forEach { it.draw(rf.take(it.h)) }
            } else {
                val f = Flow(area, 6)
                listOf(attentionB, goalsB, meB, progressB, boostB, homesB, chartB).filter { it.h > 0 }.forEach { it.draw(f.take(it.h)) }
            }
        }
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

    private fun powerTile(info: Info) = info.power?.let {
        KpiTile(tr("kami_claims.kpi.power"), WarPage.power(it), Icons.SHIELD, Palette.text,
            sub = tr("kami_claims.kpi.power.sub", Format.number(info.reputation), info.fame?.let(WarPage::stars) ?: "-"),
            tip = Tip.text(tr("kami_claims.kpi.power.tooltip"), tr("kami_claims.kpi.power")), onClick = { app.navigate(Route("war")) })
    }

    private fun plotsOf(info: Info): List<MyPlot> = myPlots.of(info.claimList, snap.me, Format.locale, System.currentTimeMillis() / 60_000) {
        info.claimList.filter { it.ownerId == snap.me && (it.state != "active" || it.rentDebt > 0) }.take(2).map { p ->
            val note = Tenure.note(p, p.state, p.until, p.rentDebt)
            MyPlot(tr("kami_claims.plot.at", p.x, p.z), note.ifEmpty { Format.perDay(Format.money(p.rent.toLong())) }, Tenure.severity(p.state, p.rentDebt).color, "myplot:${p.x}:${p.z}")
        }
    }

    private fun me(ui: Ui, box: Rect, info: Info, plots: List<MyPlot>) {
        val mine = info.members.firstOrNull { it.id == snap.me }
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
private const val ATTENTION_MAX = 160
private const val CHART_MIN = 110
private const val CHART_NARROW = 160
private const val GOAL_H = 26
private const val FORECAST_DAYS = 14

private class TreasuryChart(history: List<DayLine>, treasury: Long, net: Long) {
    val balances = history.map { it.treasury }
    val spark = balances.takeLast(14)
    val series = listOf(Series(tr("kami_libs.flow.treasury"), balances + treasury + forecast(treasury, net, FORECAST_DAYS), Palette.money, area = true, dashedFrom = balances.size))
    val labels = history.map { tr("kami_claims.chart.day", Format.number(it.day)) } + List(FORECAST_DAYS) { tr("kami_libs.time.in", Format.days(it + 1L)) }
}
