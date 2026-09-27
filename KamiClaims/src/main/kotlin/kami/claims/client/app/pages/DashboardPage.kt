package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Vocabulary
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Flow
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
    override val help get() = listOf(
        Callout("dashboard:tiles", tr("kami_claims.dashboard.help.tiles"), tr("kami_claims.dashboard.help.tiles.desc")),
        Callout("dashboard:attention", tr("kami_claims.dashboard.attention"), tr("kami_claims.dashboard.help.attention.desc")),
        Callout("dashboard:chart", tr("kami_claims.dashboard.help.chart"), tr("kami_claims.dashboard.chart.help")),
        Callout("dashboard:steps", tr("kami_claims.dashboard.steps"), tr("kami_claims.dashboard.help.steps.desc"))
    )

    override fun actionsWidth() = buttonWidth(tr("kami_claims.money.deposit"), Icons.DEPOSIT)

    override fun actions(ui: Ui, r: Rect) {
        val label = tr("kami_claims.money.deposit")
        val w = buttonWidth(label, Icons.DEPOSIT)
        if (ui.button(Rect(r.right - w, r.y, w, r.h), label, Icons.DEPOSIT, ButtonStyle.PRIMARY, key = "dash-deposit")) Dialogs.money(app, true)
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val net = info.income - info.upkeep - info.jobs
        val history = snap.history
        val balances = history.map { it.treasury }
        val tiles = r.top(TILE_H).columns(4, 6)
        ui.anchor("dashboard:tiles", r.top(TILE_H))
        val weekAgo = history.getOrNull(history.size - 8)?.treasury
        if (ui.statTile(tiles[0], tr("kami_claims.kpi.treasury"), Format.number(info.treasury), Icons.TREASURY, Palette.money,
            trend = weekAgo?.let { Trend(info.treasury - it, tr("kami_claims.kpi.treasury.week", Format.signed(info.treasury - it))) }, sub = if (weekAgo == null) tr("kami_claims.kpi.treasury.no_history") else null,
            spark = balances.takeLast(14), flashValue = info.treasury, tip = Tip.text(tr("kami_claims.kpi.open_budget"), tr("kami_claims.kpi.treasury")))) app.navigate(Route("budget"))
        if (ui.statTile(tiles[1], tr("kami_claims.kpi.net"), Format.signed(net), if (net >= 0) Icons.INCOME else Icons.EXPENSE, if (net >= 0) Palette.success else Palette.danger,
            sub = tr("kami_claims.dashboard.net.sub", Format.number(info.income), Format.number(info.upkeep + info.jobs)),
            tip = Tip(tr("kami_claims.kpi.net.title"), listOf(tr("kami_claims.kpi.net.tax", Format.signedMoney(info.income)) to Palette.success,
                tr("kami_claims.kpi.net.upkeep", Format.signedMoney(-info.upkeep)) to Palette.danger, tr("kami_claims.kpi.net.wages", Format.signedMoney(-info.jobs)) to Palette.danger)))) app.navigate(Route("budget"))
        if (ui.statTile(tiles[2], tr("kami_claims.kpi.runway"), app.runwayText(info.treasury, net), Icons.CLOCK, app.runwayColor(info.treasury, net),
            sub = tr("kami_claims.dashboard.runway.sub", Format.duration(info.nextBilling)), tip = Tip.text(tr("kami_claims.kpi.runway.tooltip"), tr("kami_claims.kpi.runway")))) app.navigate(Route("budget"))
        val debt = info.claimList.count { it.debt > 0 }
        if (ui.statTile(tiles[3], tr("kami_claims.kpi.land"), Format.number(info.chunks), Icons.AREA, if (debt > 0) Palette.danger else Palette.text,
            sub = if (debt > 0) tr("kami_claims.kpi.land.debt", Format.number(debt)) else tr("kami_claims.dashboard.land.free", Format.number(info.free), Format.number(info.freeAllowed)),
            tip = Tip.text(tr("kami_claims.dashboard.land.tooltip"), tr("kami_claims.kpi.land")))) app.navigate(Route("chunks"))
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
        val forecast = forecast(info.treasury, net, 14)
        val labels = history.map { tr("kami_claims.chart.day", Format.number(it.day)) } + List(14) { tr("kami_libs.time.in", Format.days(it + 1L)) }
        ui.lineChart(cb, listOf(Series(tr("kami_claims.kpi.treasury"), balances + info.treasury + forecast, Palette.money, area = true, dashedFrom = balances.size)), labels, key = "treasury-chart")
        val rf = Flow(right, 6)
        val steps = snap.steps
        if (steps.isNotEmpty() && steps.any { !it.done } && !ClientClaims.prefs.hiddenSteps) {
            val stepsRect = rf.take(24 + steps.size * STEP_H)
            ui.anchor("dashboard:steps", stepsRect)
            val progress = "${steps.count { it.done }}/${steps.size}"
            val sb = ui.card(stepsRect, tr("kami_claims.dashboard.steps"), Icons.CHECK, trailing = progress)
            steps.forEachIndexed { i, st ->
                val row = Rect(sb.x, sb.y + i * STEP_H, sb.w, STEP_H - 2)
                val hover = ui.hovering(row) && !st.done
                if (hover) { Draw.fill(ui.g, row, Palette.hover); ui.cursor = Cursor.HAND }
                val x = row.x + Draw.leadIcon(ui.g, if (st.done) Icons.CHECK else Icons.CHEVRON_RIGHT, row.x, row.y + 5) + 2
                Draw.text(ui.g, Draw.fit(trJson(st.label), row.right - x), x, row.y + 1, if (st.done) Palette.textMuted else Palette.text)
                Draw.text(ui.g, Draw.fit(trJson(st.hint), row.right - x), x, row.y + 11, Palette.textMuted)
                if (!st.done && ui.pressed(row) != null) app.navigate(Route(st.page))
            }
            val hide = tr("kami_claims.dashboard.steps.hide")
            if (ui.link(stepsRect.right - Draw.width(progress) - Draw.width(hide) - 14, stepsRect.y + 5, hide, key = "hide-steps")) { ClientClaims.prefs.hiddenSteps = true; ClientClaims.savePrefs() }
        }
        me(ui, rf)
        val activity = rf.remaining()
        val ab = ui.card(activity, tr("kami_claims.dashboard.activity"), Icons.CLOCK, help = tr("kami_claims.dashboard.activity.help"))
        if (snap.ledger.isEmpty()) {
            Draw.paragraph(ui.g, if (info.details) tr("kami_claims.dashboard.activity.empty") else tr("kami_claims.lock.rank", Vocabulary.rank(minRank("details").name).label), ab.x, ab.y, ab.w, Palette.textMuted)
        } else ui.scroll("activity", ab, snap.ledger.size * TABLE_ROW_H) { area ->
            snap.ledger.forEachIndexed { i, e ->
                val y = area.y + i * TABLE_ROW_H
                val look = Vocabulary.ledger(e.kind)
                val x = area.x + Draw.leadIcon(ui.g, look.icon, area.x, y + TABLE_ROW_H / 2) + 2
                Draw.text(ui.g, Format.duration(System.currentTimeMillis() - e.at), x, y + 3, Palette.textMuted)
                Draw.text(ui.g, Draw.fit(look.label + (if (e.note.isNotEmpty()) " · ${e.note}" else ""), area.right - x - 100), x + 36, y + 3, Palette.textSecondary)
                ui.moneyRight(area.right, y + 3, e.amount, signed = true)
            }
        }
        val ledger = tr("kami_claims.nav.ledger")
        if (ui.link(activity.right - Draw.width(ledger) - 22, activity.y + 5, ledger, key = "open-ledger") && info.details) app.navigate(Route("ledger"))
    }

    private fun me(ui: Ui, f: Flow) {
        val info = info ?: return
        val mine = info.members.firstOrNull { it.id == snap.me }
        val plots = info.claimList.filter { it.ownerId == snap.me }
        val rows = 2 + plots.size.coerceAtMost(3)
        val box = f.take(24 + rows * 12)
        val b = ui.card(box, tr("kami_claims.dashboard.you"), Icons.PERSON)
        val flow = Flow(b, 1)
        val rank = Vocabulary.rank(info.rank)
        ui.property(flow.take(11), tr("kami_claims.dashboard.you.rank"), rank.label, rank.color)
        val job = mine?.job?.takeIf { it.isNotEmpty() }
        val jobLine = job?.let { j -> info.jobList.firstOrNull { it.name == j } }
        ui.property(flow.take(11), tr("kami_claims.dashboard.you.job"), if (job == null) tr("kami_claims.dashboard.you.no_job") else "${Vocabulary.job(job)} · ${mine.progress}/${jobLine?.quota ?: 0} · ${Format.money(jobLine?.pay?.toLong() ?: 0)}", if (job == null) Palette.textMuted else Palette.text)
        plots.take(3).forEach { p ->
            ui.property(flow.take(11), tr("kami_claims.plot.at", p.x, p.z), if (p.lapse > 0) tr("kami_claims.plot.unpaid", Format.days(p.lapse.toLong())) else Format.perDay(Format.money(p.tax.toLong())), if (p.lapse > 0) Palette.danger else Palette.success, key = "myplot:${p.x}:${p.z}")
        }
    }
}

private const val TILE_H = 44
private const val STEP_H = 22
