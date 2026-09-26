package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Illustrations
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
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.*

class DashboardPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title = "Dashboard"
    override val sections = listOf("dashboard")
    override val help = listOf(
        Callout("dashboard:tiles", "Key numbers", "Treasury, net, runway, land. Click to open."),
        Callout("dashboard:attention", "Attention", "Issues sorted by urgency with actions."),
        Callout("dashboard:chart", "Treasury history", "Solid: history. Dashed: projection."),
        Callout("dashboard:steps", "Next steps", "Quick setup checklist.")
    )

    override fun actions(ui: Ui, r: Rect) {
        val w = buttonWidth("Deposit", Icons.DEPOSIT)
        if (ui.button(Rect(r.right - w, r.y + 1, w, 18), "Deposit", Icons.DEPOSIT, ButtonStyle.PRIMARY, key = "dash-deposit")) Dialogs.money(app, true)
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val net = info.income - info.upkeep - info.jobs
        val history = snap.history
        val balances = history.map { it.treasury }
        val tiles = r.top(58).columns(4, 6)
        ui.anchor("dashboard:tiles", r.top(58))
        val weekAgo = history.getOrNull(history.size - 8)?.treasury
        if (ui.statTile(tiles[0], "Treasury", Format.number(info.treasury), Icons.TREASURY, Palette.money,
            trend = weekAgo?.let { Trend(info.treasury - it, "7d ${Format.signed(info.treasury - it)}") }, sub = if (weekAgo == null) "No history" else null,
            spark = balances.takeLast(14), flashValue = info.treasury, tip = Tip.text("Country treasury balance.", "Treasury"))) app.navigate(Route("budget"))
        if (ui.statTile(tiles[1], "Net per day", Format.signed(net), if (net >= 0) Icons.INCOME else Icons.EXPENSE, if (net >= 0) Palette.success else Palette.danger,
            sub = "in ${info.income} · out ${info.upkeep + info.jobs}", tip = Tip("Daily net", listOf("Tax +${info.income}" to Palette.success, "Upkeep -${info.upkeep}" to Palette.danger, "Wages -${info.jobs}" to Palette.danger)))) app.navigate(Route("budget"))
        if (ui.statTile(tiles[2], "Runway", app.runwayText(info.treasury, net), Icons.CLOCK, app.runwayColor(info.treasury, net),
            sub = "bill ${Format.duration(info.nextBilling)}", tip = Tip.text("Time until treasury runs out at current net.", "Runway"))) app.navigate(Route("budget"))
        val debt = info.claimList.count { it.debt > 0 }
        if (ui.statTile(tiles[3], "Territory", Format.number(info.chunks), Icons.AREA, if (debt > 0) Palette.danger else Palette.text,
            sub = if (debt > 0) "$debt debt" else "${info.free}/${info.freeAllowed} free", tip = Tip.text("Owned chunks.", "Territory"))) app.navigate(Route("chunks"))
        val (left, right) = r.dropTop(64).columns(listOf(1.35f, 1f), 8)
        val lf = Flow(left, 8)
        val alerts = app.visibleAlerts()
        val attentionH = if (alerts.isEmpty()) 60 else (alerts.sumOf { app.alertHeight(it, left.w - 12) } + 28).coerceAtMost((left.h * 0.55).toInt())
        val att = lf.take(attentionH)
        ui.anchor("dashboard:attention", att)
        val body = ui.card(att, "Needs attention", Icons.BELL, if (alerts.any { it.severity == "DANGER" }) Severity.DANGER else if (alerts.isNotEmpty()) Severity.WARNING else null, trailing = if (alerts.isEmpty()) null else alerts.size.toString())
        if (alerts.isEmpty()) {
            Draw.icon(ui.g, Icons.CHECK, body.x, body.y)
            Draw.text(ui.g, "No active issues.", body.x + 20, body.y + 4, Palette.success)
        } else ui.scroll("attention", body, alerts.sumOf { app.alertHeight(it, body.w) }) { area ->
            var y = area.y
            alerts.forEach { a -> y += app.alertCard(ui, Rect(area.x, y, area.w, 0), a) }
        }
        val chart = lf.remaining()
        ui.anchor("dashboard:chart", chart)
        val cb = ui.card(chart, "Treasury · ${history.size.coerceAtLeast(1)}d", Icons.STATS, help = "Solid: history. Dashed: 14d projection at current net.")
        val forecast = forecast(info.treasury, net, 14)
        val labels = history.map { "day ${it.day}" } + List(14) { "in ${it + 1}d" }
        ui.lineChart(cb, listOf(Series("Treasury", balances + info.treasury + forecast, Palette.money, area = true, dashedFrom = balances.size)), labels, key = "treasury-chart")
        val rf = Flow(right, 8)
        val steps = snap.steps
        if (steps.isNotEmpty() && steps.any { !it.done } && !ClientClaims.prefs.hiddenSteps) {
            val stepsRect = rf.take(22 + steps.size * 24 + 10)
            ui.anchor("dashboard:steps", stepsRect)
            val sb = ui.card(stepsRect, "Next steps", Icons.CHECK, trailing = "${steps.count { it.done }}/${steps.size}")
            steps.forEachIndexed { i, st ->
                val row = Rect(sb.x, sb.y + i * 24, sb.w, 22)
                val hover = ui.hovering(row) && !st.done
                if (hover) { Draw.fill(ui.g, row, Palette.hover); ui.cursor = Cursor.HAND }
                Draw.icon(ui.g, if (st.done) Icons.CHECK else Icons.CHEVRON_RIGHT, row.x, row.y + 2)
                Draw.text(ui.g, Draw.fit(st.label, row.w - 22), row.x + 20, row.y + 2, if (st.done) Palette.textMuted else Palette.text)
                Draw.text(ui.g, Draw.fit(st.hint, row.w - 22), row.x + 20, row.y + 12, Palette.textMuted)
                if (!st.done && ui.pressed(row) != null) app.navigate(Route(st.page))
            }
            if (ui.link(sb.right - 30, stepsRect.y + 6, "Hide", key = "hide-steps")) { ClientClaims.prefs.hiddenSteps = true; ClientClaims.savePrefs() }
        }
        me(ui, rf)
        val activity = rf.remaining()
        val ab = ui.card(activity, "Activity", Icons.CLOCK, help = "The latest treasury movements.")
        if (snap.ledger.isEmpty()) {
            Draw.paragraph(ui.g, if (info.details) "No entries yet. Deposits, upkeep and tax will appear here." else "Visible for ${Vocabulary.rank(minRank("details").name).label}+.", ab.x, ab.y, ab.w, Palette.textMuted)
        } else ui.scroll("activity", ab, snap.ledger.size * 12) { area ->
            snap.ledger.forEachIndexed { i, e ->
                val y = area.y + i * 12
                val look = Vocabulary.ledger(e.kind)
                Draw.icon(ui.g, look.icon, area.x, y - 1, 8)
                Draw.text(ui.g, Format.ago(e.at).replace(" ago", ""), area.x + 11, y, Palette.textMuted)
                Draw.text(ui.g, Draw.fit(look.label + (if (e.note.isNotEmpty()) " · ${e.note}" else ""), area.w - 110), area.x + 44, y, Palette.textSecondary)
                ui.moneyRight(area.right, y, e.amount, signed = true)
            }
        }
        if (ui.link(ab.right - 48, activity.y + 6, "Ledger", key = "open-ledger") && info.details) app.navigate(Route("ledger"))
    }

    private fun me(ui: Ui, f: Flow) {
        val info = info ?: return
        val mine = info.members.firstOrNull { it.id == snap.me }
        val plots = info.claimList.filter { it.ownerId == snap.me }
        val rows = 2 + plots.size.coerceAtMost(3)
        val box = f.take(22 + rows * 12 + 8)
        val b = ui.card(box, "You", Icons.PERSON)
        val flow = Flow(b, 1)
        val rank = Vocabulary.rank(info.rank)
        ui.property(flow.take(11), "Rank", rank.label, rank.color)
        val job = mine?.job?.takeIf { it.isNotEmpty() }
        val jobLine = job?.let { j -> info.jobList.firstOrNull { it.name == j } }
        ui.property(flow.take(11), "Job", if (job == null) "none" else "$job · ${mine.progress}/${jobLine?.quota ?: 0} · ${jobLine?.pay ?: 0} ◎", if (job == null) Palette.textMuted else Palette.text)
        plots.take(3).forEach { p ->
            ui.property(flow.take(11), "Plot ${p.x}, ${p.z}", if (p.lapse > 0) "${p.lapse}d unpaid" else "${p.tax} ◎/day paid", if (p.lapse > 0) Palette.danger else Palette.success, key = "myplot:${p.x}:${p.z}")
        }
    }
}
