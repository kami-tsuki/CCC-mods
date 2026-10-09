package kami.economy.client.ui

import kami.economy.client.admin.MarketSection
import kami.economy.net.Dash
import kami.economy.net.Leader
import kami.economy.net.Row
import kami.economy.net.Slot
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.text.trJson
import kami.libs.ui.widget.*

private const val KPI_H = 34
private const val CARD_H = 124
private const val STAT_H = 20
private const val WATCH_MIN = 96
private const val LEADER_H = 12
private val MEDALS = listOf(0xFFE0B040.toInt(), 0xFFBFC3C9.toInt(), 0xFFB8733B.toInt())

internal class DashboardPage(val app: MarketApp) : Page() {
    override val title get() = tr("kami_libs.common.dashboard")
    override val help get() = listOf(Callout("dashboard:cards", tr("kami_libs.common.overview"), tr("kami_economy.help.dashboard.desc")))

    private val watchList = WatchList(app, "dash-watch", open = app::openItem)

    override fun opened(route: Route) = app.view("dashboard")

    private fun slotTile(label: String, slot: Slot) = KpiTile(
        label, if (slot.max > 0) tr("kami_libs.format.used", slot.used, slot.max) else slot.used.toString(),
        tip = slot.hint.takeIf { it.isNotEmpty() }?.let { Tip.text(trJson(it)) }
    )

    override fun draw(ui: Ui, r: Rect) {
        val s = app.snap
        val wide = r.w >= 480
        val cardsH = if (wide) CARD_H else 3 * CARD_H + 12
        val footH = STAT_H + 4 + SMALL_H
        val main = r.dropBottom(footH + 6)
        val foot = Rect(r.x, r.bottom - footH, r.w, footH)
        val watchH = (main.h - KPI_H - cardsH - 12).coerceAtLeast(WATCH_MIN)
        ui.scroll("dashboard", main, KPI_H + cardsH + watchH + 12) { c ->
            val flow = Flow(c, 6)
            ui.kpiRow(flow.take(KPI_H), listOf(
                KpiTile(tr("kami_economy.dash.funds"), Format.money(s.funds), Icons.COIN, Palette.money),
                slotTile(tr("kami_economy.slots.orders"), s.orderSlots),
                slotTile(tr("kami_libs.common.auctions"), s.auctionSlots),
                KpiTile(tr("kami_economy.dash.open_orders"), s.mine.size.toString())
            ), key = "dash-kpi")
            val cards = flow.take(cardsH)
            ui.anchor("dashboard:cards", cards)
            val dash = app.cached("dashboard")?.dash
            val cells = if (wide) cards.columns(3, 6) else cards.rows(3, 6)
            if (dash == null) {
                listOf("kami_economy.dash.sold", "kami_economy.dash.bought", "kami_economy.dash.leaders").forEachIndexed { i, k -> ui.skeletonLines(ui.card(cells[i], tr(k)), 5) }
            } else {
                top(ui, cells[0], tr("kami_economy.dash.sold"), dash.topSold) { it.sold }
                top(ui, cells[1], tr("kami_economy.dash.bought"), dash.topBought) { it.bought }
                leaders(ui, cells[2], dash)
            }
            val watch = ui.card(flow.take(watchH), tr("kami_economy.dash.watch"))
            val watched = app.cached("dashboard")
            if (watched != null && watched.watch.isEmpty()) {
                if (watch.h >= 30) ui.emptyState(watch, tr("kami_economy.dash.watch.empty"), "", key = "watch-empty")
                else ui.fitText(watch.x, watch.y + 2, watch.w, tr("kami_economy.dash.watch.empty"), Palette.textMuted)
            } else watchList.draw(ui, watch, watched, app.loading("dashboard"))
        }
        val stat = foot.top(STAT_H)
        val dash = app.cached("dashboard")?.dash
        if (dash == null) ui.skeleton(stat.top(8).withWidth(stat.w * 2 / 3))
        else Draw.paragraph(ui.g, tr("kami_economy.dash.stats", dash.items, dash.asks, dash.bids, dash.auctions, Format.number(dash.traded)), stat.x, stat.y, stat.w, Palette.textSecondary)
        val bar = Rect(foot.x, foot.bottom - SMALL_H, foot.w, SMALL_H)
        val sell = tr("kami_economy.dash.sell")
        val sellW = buttonWidth(sell)
        if (ui.button(Rect(bar.x, bar.y, sellW, bar.h), sell, style = ButtonStyle.PRIMARY, key = "dash-sell")) app.navigate(Route("market", mapOf("search" to "1")))
        val tour = tr("kami_economy.dash.tour")
        if (ui.button(Rect(bar.x + sellW + 6, bar.y, buttonWidth(tour), bar.h), tour, key = "dash-tour")) app.startTour()
    }

    private fun top(ui: Ui, r: Rect, title: String, rows: List<Row>, units: (Row) -> Long) {
        val inner = ui.card(r, title)
        if (rows.isEmpty()) Draw.text(ui.g, tr("kami_economy.dash.nothing"), inner.x, inner.y + 2, Palette.textMuted)
        val priceW = rows.maxOfOrNull { Draw.width(Format.rateMoney(it.perItem())) } ?: 0
        rows.forEachIndexed { i, row ->
            val line = Rect(inner.x, inner.y + i * ROW_H, inner.w, ROW_H)
            val key = "dash:$title:${row.item}"
            if (ui.hovering(line)) Draw.fill(ui.g, line, Palette.hover)
            ui.itemIcon(line.x + 1, line.y + 1, MarketSection.stack(row.item), key)
            val price = Format.rateMoney(row.perItem())
            val count = Format.number(units(row))
            Draw.text(ui.g, price, line.right - Draw.width(price) - 2, line.y + 5, Palette.money)
            val countX = line.right - priceW - 10 - Draw.width(count)
            Draw.text(ui.g, count, countX, line.y + 5, Palette.textMuted)
            ui.fitText(line.x + ROW_H + 3, line.y + 5, countX - line.x - ROW_H - 8, MarketSection.stack(row.item).hoverName.string)
            if (ui.clickable("$key:go", line)) app.openItem(row.item)
        }
    }

    private fun leaders(ui: Ui, r: Rect, dash: Dash) {
        val inner = ui.card(r, tr("kami_economy.dash.leaders"))
        val y = group(ui, inner, inner.y, tr("kami_economy.dash.players"), dash.players)
        if (dash.countries.isNotEmpty()) group(ui, inner, y + 4, tr("kami_economy.dash.countries"), dash.countries)
    }

    private fun group(ui: Ui, inner: Rect, top: Int, title: String, list: List<Leader>): Int {
        Draw.text(ui.g, title, inner.x, top, Palette.textMuted)
        var y = top + 11
        list.forEachIndexed { i, l ->
            Draw.leadIcon(ui.g, Icons.STAR, inner.x, y + 4, MEDALS.getOrElse(i) { MEDALS.last() })
            var x = inner.x + 16
            if (l.flag != null || l.color != 0) { ui.flag(Rect(x, y, 11, 8), l.color, l.flag); x += 14 }
            ui.fitText(x, y, inner.right - x - 60, l.name, Palette.text)
            ui.moneyRight(inner.right, y, l.value)
            y += LEADER_H
        }
        return y
    }
}
