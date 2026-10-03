package kami.economy.client.ui

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
private const val LEADER_H = 12
private val MEDALS = listOf(0xFFE0B040.toInt(), 0xFFBFC3C9.toInt(), 0xFFB8733B.toInt())

internal class DashboardPage(val app: MarketApp) : Page() {
    override val title get() = tr("kami_economy.nav.dashboard")
    override val help get() = listOf(Callout("dashboard:cards", tr("kami_economy.help.dashboard"), tr("kami_economy.help.dashboard.desc")))

    override fun opened(route: Route) = app.view("dashboard")

    private fun slotTile(label: String, slot: Slot) = KpiTile(
        label, if (slot.max > 0) "${slot.used}/${slot.max}" else slot.used.toString(),
        tip = slot.hint.takeIf { it.isNotEmpty() }?.let { Tip.text(trJson(it)) }
    )

    override fun draw(ui: Ui, r: Rect) {
        val s = app.snap
        val wide = r.w >= 480
        val cardsH = if (wide) CARD_H else 3 * CARD_H + 12
        ui.scroll("dashboard", r, KPI_H + cardsH + STAT_H + SMALL_H + 18) { c ->
            val flow = Flow(c, 6)
            ui.kpiRow(flow.take(KPI_H), listOf(
                KpiTile(tr("kami_economy.dash.funds"), Format.money(s.funds), Icons.COIN, Palette.money),
                slotTile(tr("kami_economy.slots.orders"), s.orderSlots),
                slotTile(tr("kami_libs.common.auctions"), s.auctionSlots),
                KpiTile(tr("kami_economy.dash.open_orders"), s.mine.size.toString())
            ), key = "dash-kpi")
            val cards = flow.take(cardsH)
            ui.anchor("dashboard:cards", cards)
            val dash = s.dash
            if (dash == null) ui.emptyState(cards, tr("kami_economy.market.calculating"), "")
            else {
                val cells = if (wide) cards.columns(3, 6) else cards.rows(3, 6)
                top(ui, cells[0], tr("kami_economy.dash.sold"), dash.topSold) { it.sold }
                top(ui, cells[1], tr("kami_economy.dash.bought"), dash.topBought) { it.bought }
                leaders(ui, cells[2], dash)
            }
            val stat = flow.take(STAT_H)
            dash?.let { Draw.paragraph(ui.g, tr("kami_economy.dash.stats", it.items, it.asks, it.bids, it.auctions, Format.number(it.traded)), stat.x, stat.y, stat.w, Palette.textSecondary) }
            val bar = flow.take(SMALL_H)
            val sell = tr("kami_economy.dash.sell")
            val sellW = buttonWidth(sell)
            if (ui.button(Rect(bar.x, bar.y, sellW, bar.h), sell, style = ButtonStyle.PRIMARY, key = "dash-sell")) app.navigate(Route("market", mapOf("search" to "1")))
            val tour = tr("kami_economy.dash.tour")
            if (ui.button(Rect(bar.x + sellW + 6, bar.y, buttonWidth(tour), bar.h), tour, key = "dash-tour")) app.startTour()
        }
    }

    private fun top(ui: Ui, r: Rect, title: String, rows: List<Row>, units: (Row) -> Long) {
        val inner = ui.card(r, title)
        if (rows.isEmpty()) Draw.text(ui.g, tr("kami_economy.dash.nothing"), inner.x, inner.y + 2, Palette.textMuted)
        rows.take(5).forEachIndexed { i, row ->
            val line = Rect(inner.x, inner.y + i * ROW_H, inner.w, ROW_H)
            val key = "dash:$title:${row.item}"
            if (ui.hovering(line)) Draw.fill(ui.g, line, Palette.hover)
            ui.itemIcon(line.x + 1, line.y + 1, app.stack(row.item), key)
            val price = priceText(row.buy.toLong())
            val count = Format.compact(units(row))
            Draw.text(ui.g, price, line.right - Draw.width(price) - 2, line.y + 5, Palette.money)
            val countX = line.right - 62 - Draw.width(count)
            Draw.text(ui.g, count, countX, line.y + 5, Palette.textMuted)
            Draw.text(ui.g, Draw.fit(app.stack(row.item).hoverName.string, countX - line.x - ROW_H - 8), line.x + ROW_H + 3, line.y + 5)
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
        list.take(3).forEachIndexed { i, l ->
            Draw.leadIcon(ui.g, Icons.STAR, inner.x, y + 4, MEDALS[i])
            var x = inner.x + 16
            if (l.flag != null || l.color != 0) { ui.flag(Rect(x, y, 11, 8), l.color, l.flag); x += 14 }
            Draw.text(ui.g, Draw.fit(l.name, inner.right - x - 60), x, y, Palette.text)
            ui.moneyRight(inner.right, y, l.value, compact = true)
            y += LEADER_H
        }
        return y
    }
}
