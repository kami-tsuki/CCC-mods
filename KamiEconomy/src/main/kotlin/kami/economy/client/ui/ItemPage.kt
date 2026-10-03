package kami.economy.client.ui

import kami.economy.client.MarketPrefs
import kami.economy.net.Detail
import kami.economy.net.Level
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.graph.ChartStyle
import kami.libs.ui.graph.Ohlc
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

private const val CHART_H = 132
private const val HEAD_H = 30
private const val PILLS_W = 112
private const val BOOK_ROW = 11
private const val BOOK_ROWS = 6
private const val BOOK_MID = 4
private const val VENDOR_ROWS = 8
private const val TICKET_H = 470
private const val TICKET_W = 190

internal class ItemPage(val app: MarketApp) : Page() {
    private enum class Span(val arg: String, val pattern: String) {
        DAY("day", "HH:mm"), WEEK("week", "EEE d"), MONTH("month", "d MMM"), ALL("all", "d MMM");

        val formatter by lazy { DateTimeFormatter.ofPattern(pattern, Format.locale).withZone(ZoneId.systemDefault()) }

        companion object {
            fun of(arg: String) = entries.firstOrNull { it.arg == arg } ?: WEEK
        }
    }

    private var item = ""
    private var span = Span.of(MarketPrefs.prefs.chartRange)
    private var style = ChartStyle.CANDLE
    private var vendorsOpen = false
    private val ticket = Ticket(app)

    override val title get() = app.stack(item).hoverName.string
    override val subtitle get() = detail()?.let { tr("kami_economy.item.lot", it.lot) }
    override val help get() = listOf(
        Callout("item:chart", tr("kami_economy.help.item.chart"), tr("kami_economy.help.item.chart.desc")),
        Callout("item:book", tr("kami_economy.help.item.book"), tr("kami_economy.help.item.book.desc")),
        Callout("item:ticket", tr("kami_economy.help.item.ticket"), tr("kami_economy.help.item.ticket.desc"))
    )

    private fun detail(): Detail? = app.snap.detail?.takeIf { it.item == item }

    override fun opened(route: Route) {
        val next = route.param("item") ?: return
        item = next
        vendorsOpen = false
        ticket.open(next, route.param("side") != "sell", route.param("kind") == "order")
        app.request("detail", item, span.arg)
    }

    override fun leaving(next: Route): Boolean {
        if (next.page != "item") app.request("detail", "")
        return true
    }

    override fun draw(ui: Ui, r: Rect) {
        val d = detail()
        val stack = app.stack(item)
        val head = r.top(22)
        ui.itemSlot(head.left(22), stack, key = "detail:item")
        d?.let { Draw.text(ui.g, Draw.fit(tr("kami_economy.item.held", unitsText(it.held, stack.maxStackSize)), head.w - 30), head.x + 30, head.y + 7, Palette.textSecondary) }
        if (d != null) {
            val delta = d.change.toLong()
            ui.kpiRow(r.dropTop(26).top(34), listOf(
                KpiTile(tr("kami_economy.col.buy"), priceText(d.buy.toLong()), color = Palette.money, tip = Tip.text(tr("kami_economy.kpi.buy", d.lot))),
                KpiTile(tr("kami_economy.col.sell"), priceText(d.sell.toLong()), color = Palette.money, tip = Tip.text(tr("kami_economy.kpi.sell", d.lot))),
                KpiTile(tr("kami_economy.item.spread"), if (d.buy > 0 && d.sell > 0) Format.money((d.buy - d.sell).toLong()) else "–", tip = Tip.text(tr("kami_economy.kpi.spread"))),
                KpiTile(tr("kami_economy.col.change"), permille(delta), color = if (delta > 0) Palette.success else if (delta < 0) Palette.danger else Palette.textMuted, tip = Tip.text(tr("kami_economy.col.change.tip"))),
                KpiTile(tr("kami_economy.item.sold_bought"), "${Format.compact(d.sold)} / ${Format.compact(d.bought)}", tip = Tip.text(tr("kami_economy.kpi.traded")))
            ), key = "item-kpi")
        }
        val main = r.dropTop(66)
        val ticketW = if (main.w < 440) main.w * 5 / 12 else TICKET_W
        left(ui, main.dropRight(ticketW, 8), d)
        val right = main.right(ticketW)
        ui.anchor("item:ticket", right)
        ui.scroll("ticket", right, TICKET_H) { c -> ticket.draw(ui, Flow(c, 4), d) }
    }

    private fun left(ui: Ui, r: Rect, d: Detail?) {
        val asks = d?.asks.orEmpty().take(BOOK_ROWS).reversed()
        val bids = d?.bids.orEmpty().take(BOOK_ROWS)
        val vendors = d?.vendors.orEmpty()
        val rows = (asks.size + bids.size).coerceAtLeast(1) * BOOK_ROW + BOOK_MID
        val vendorH = if (vendors.isEmpty()) 0 else 20 + if (vendorsOpen) vendors.take(VENDOR_ROWS).size * BOOK_ROW else 0
        val candles = ui.filtered("chart:history", d?.chart?.candles ?: emptyList<Any>()) {
            d?.chart?.candles.orEmpty().map { Ohlc(it.open.toDouble(), it.high.toDouble(), it.low.toDouble(), it.close.toDouble(), it.volume.toDouble(), it.at) }
        }
        ui.scroll("item-left", r, HEAD_H + 4 + CHART_H + 8 + 12 + rows + vendorH) { c ->
            val chart = Rect(c.x, c.y + HEAD_H + 4, c.w, CHART_H)
            ui.anchor("item:chart", chart)
            val open = d?.chart?.open?.toDouble()?.takeIf { it > 0 }
            val hovered = ui.priceChart(chart, candles, style, open, d?.chart?.start ?: 0L, this::time)
            chartHeader(ui, Rect(c.x, c.y, c.w, HEAD_H), hovered ?: candles.lastOrNull(), open, hovered != null)
            var y = chart.bottom + 8
            ui.anchor("item:book", Rect(c.x, y, c.w, 12 + rows))
            Draw.text(ui.g, tr("kami_economy.market.book"), c.x, y, Palette.textSecondary)
            y += 12
            if (asks.isEmpty() && bids.isEmpty()) Draw.text(ui.g, tr("kami_economy.market.book.empty"), c.x, y + 1, Palette.textMuted)
            val most = (asks + bids).maxOfOrNull { it.amount } ?: 0
            asks.forEachIndexed { i, l -> bookRow(ui, Rect(c.x, y + i * BOOK_ROW, c.w, BOOK_ROW), l, Palette.danger, most, "ask$i", false) }
            y += asks.size * BOOK_ROW + BOOK_MID
            bids.forEachIndexed { i, l -> bookRow(ui, Rect(c.x, y + i * BOOK_ROW, c.w, BOOK_ROW), l, Palette.success, most, "bid$i", true) }
            y += bids.size.coerceAtLeast(if (asks.isEmpty()) 1 else 0) * BOOK_ROW + 6
            if (vendors.isNotEmpty()) vendorBlock(ui, Rect(c.x, y, c.w, vendorH), d)
        }
    }

    private fun time(ms: Long) = span.formatter.format(Instant.ofEpochMilli(ms))

    private fun chartHeader(ui: Ui, r: Rect, shown: Ohlc?, open: Double?, scrubbing: Boolean) {
        val pills = r.top(SMALL_H).right(PILLS_W)
        ui.segmented(pills, Span.entries.map { Option(it, tr("kami_economy.chart.range.${it.arg}")) }, span, key = "span")?.let { next ->
            span = next
            MarketPrefs.prefs.chartRange = next.arg
            MarketPrefs.save()
            app.request("detail", item, next.arg)
        }
        val toggle = Rect(r.right - SMALL_H, r.bottom - SMALL_H, SMALL_H, SMALL_H)
        val candle = style == ChartStyle.CANDLE
        if (ui.iconButton(toggle, Icons.STATS, tr(if (candle) "kami_economy.market.chart.line" else "kami_economy.market.chart.candles"), selected = candle, key = "style")) style = if (candle) ChartStyle.LINE else ChartStyle.CANDLE
        if (shown == null) return
        val diff = open?.let { shown.close - it }
        val tone = if (diff == null || diff == 0.0) Palette.textMuted else if (diff > 0) Palette.success else Palette.danger
        val price = Format.money(shown.close.roundToLong())
        Draw.text(ui.g, price, r.x, r.y, TextStyle.DISPLAY)
        val change = if (diff == null || open == null) "–" else "${if (diff > 0) "▲" else if (diff < 0) "▼" else "•"} ${Format.signed(diff.roundToLong())} (${Format.percent(abs(diff) / open, 1)})"
        val x = r.x + Draw.width(price, TextStyle.DISPLAY) + 8
        Draw.text(ui.g, change, x, r.y + 1, tone)
        Draw.text(ui.g, if (scrubbing) Format.exact(shown.at) else tr("kami_economy.chart.caption.${span.arg}"), x, r.y + 11, Palette.textMuted)
    }

    private fun bookRow(ui: Ui, r: Rect, l: Level, color: Int, most: Int, key: String, bid: Boolean) {
        val embargo = l.relation == "embargo"
        if (most > 0) Draw.fill(ui.g, r.withWidth((r.w.toLong() * l.amount / most).toInt().coerceIn(1, r.w)), Palette.alpha(color, 0x28))
        if (l.mine) Draw.outline(ui.g, r, Palette.brass)
        if (l.country.isNotEmpty()) ui.flag(Rect(r.x + 2, r.y + 1, 11, 8), l.color, l.flag)
        Draw.text(ui.g, Format.money(l.price.toLong()), r.x + 16, r.y + 1, if (l.mine) Palette.brass else if (embargo) Palette.textMuted else color)
        val amount = when {
            l.market -> tr("kami_libs.common.market")
            embargo -> tr("kami_libs.common.embargo")
            else -> Format.number(l.amount)
        }
        Draw.text(ui.g, amount, r.right - Draw.width(amount) - 3, r.y + 1, if (embargo) Palette.danger else Palette.textMuted)
        val side = if (bid) "bid" else "ask"
        val kind = tr(if (l.market) "kami_economy.book.tip.market.$side" else "kami_economy.book.tip.$side", Format.number(l.amount), Format.money(l.price.toLong()))
        ui.tooltip("book:$key", r, Tip(lines = listOfNotNull(
            kind to Palette.textSecondary,
            l.country.takeIf { it.isNotEmpty() }?.let { app.countryTip(it, l.relation) to Palette.textMuted },
            (tr("kami_economy.book.tip.mine") to Palette.brass).takeIf { l.mine }
        )))
    }

    private fun vendorBlock(ui: Ui, r: Rect, d: Detail?) {
        val vendors = d?.vendors.orEmpty()
        val cheapest = vendors.filter { it.sell }.minByOrNull { it.price }
        val text = cheapest?.let { tr("kami_economy.item.vendor", Format.money(it.price.toLong()), it.country) } ?: tr("kami_economy.item.vendors", vendors.size)
        val toggle = tr(if (vendorsOpen) "kami_economy.item.vendors.hide" else "kami_economy.item.vendors.show", vendors.size)
        Draw.text(ui.g, Draw.fit(text, r.w - Draw.width(toggle) - 8), r.x, r.y + 4, Palette.textSecondary)
        if (ui.link(r.right - Draw.width(toggle), r.y + 4, toggle, key = "vendors-toggle")) vendorsOpen = !vendorsOpen
        if (!vendorsOpen) return
        vendors.take(VENDOR_ROWS).forEachIndexed { i, v ->
            val row = Rect(r.x, r.y + 20 + i * BOOK_ROW, r.w, BOOK_ROW)
            ui.flag(Rect(row.x + 2, row.y + 1, 11, 8), v.color, v.flag)
            Draw.text(ui.g, Draw.fit(v.country, row.w / 2 - 20), row.x + 16, row.y + 1, if (v.embargo) Palette.danger else Palette.text)
            val kind = tr(if (v.sell) "kami_economy.market.vendors.sells" else "kami_economy.market.vendors.buys")
            val price = Format.money(v.price.toLong())
            Draw.text(ui.g, price, row.right - Draw.width(price) - 3, row.y + 1, Palette.text)
            Draw.text(ui.g, kind, row.right - Draw.width(price) - Draw.width(kind) - 12, row.y + 1, if (v.sell) Palette.danger else Palette.success)
            if (v.embargo) ui.tooltip("vendor-embargo:$i", row, tr("kami_economy.market.embargo.vendor", v.country))
        }
    }
}
