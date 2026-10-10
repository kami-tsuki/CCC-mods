package kami.economy.client.ui

import kami.economy.LOG
import kami.economy.client.admin.MarketSection
import kami.economy.client.MarketLink
import kami.economy.client.MarketPrefs
import kami.economy.net.Detail
import kami.economy.net.Level
import kami.libs.jei.DropTarget
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
private const val TICKET_H = 440
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
    private var bannerWarned = false
    private val ticket = Ticket(app)
    private val spans = HashMap<String, Span>()
    private var waitFor: Detail? = null
    private var head = Rect(0, 0, 0, 0)
    private var drawnAt = 0L

    fun drops(): List<DropTarget> =
        if (System.currentTimeMillis() - drawnAt > 250) emptyList()
        else listOf(DropTarget(head.x, head.y, head.w, head.h) { stack -> MarketLink.market(stack)?.let { app.openItem(it, ticket.side, ticket.kind) } })

    override val title get() = MarketSection.stack(ticket.shown.ifEmpty { item }).hoverName.string
    override val subtitle get() = detail()?.let { tr("kami_economy.item.lot", ticket.lotOf(it)) }
    override val help get() = listOf(
        Callout("item:chart", tr("kami_economy.help.item.chart"), tr("kami_economy.help.item.chart.desc")),
        Callout("item:book", tr("kami_economy.help.item.book"), tr("kami_economy.help.item.book.desc")),
        Callout("item:ticket", tr("kami_economy.help.item.ticket"), tr("kami_economy.help.item.ticket.desc"))
    )

    private fun detail(): Detail? = app.detail(item)

    fun refresh() = app.request("detail", item, span.arg)

    private fun fetch() {
        waitFor = detail()
        app.request("detail", item, span.arg)
    }

    private val waiting get() = waitFor != null && detail() === waitFor

    override fun opened(route: Route) {
        val next = route.param("item") ?: return
        item = next
        vendorsOpen = false
        ticket.open(next, route.param("side") != "sell", route.param("kind") == "order", route.param("variant").orEmpty())
        fetch()
    }

    override fun actionsWidth() = SMALL_H

    override fun actions(ui: Ui, r: Rect) {
        val pinned = app.loaded && item in app.snap.watch
        if (ui.iconButton(Rect(r.x, r.y, SMALL_H, SMALL_H), Icons.STAR, tr(if (pinned) "kami_economy.watch.unpin" else "kami_economy.watch.pin"), selected = pinned, key = "watch-pin"))
            app.request("watch", item, if (pinned) "off" else "on")
    }

    override fun leaving(next: Route): Boolean {
        if (next.page != "item") app.request("detail", "")
        return true
    }

    override fun draw(ui: Ui, r: Rect) {
        val d = detail()
        if (d != null && d !== waitFor) spans[item] = span
        val stack = MarketSection.stack(ticket.shown.ifEmpty { item })
        head = r.top(22)
        drawnAt = System.currentTimeMillis()
        ui.itemSlot(head.left(22), stack, key = "detail:item")
        if (d == null) ui.skeleton(Rect(head.x + 30, head.y + 7, (head.w - 30) / 3, 8))
        else ui.fitText(head.x + 30, head.y + 7, head.w - 30, tr("kami_economy.item.held", unitsText(ticket.heldOf(d), stack.maxStackSize)), Palette.textSecondary)
        val first = d?.chart?.open?.toLong() ?: 0L
        val delta = d?.chart?.candles?.lastOrNull { it.close > 0 }?.takeIf { first > 0 }?.let { (it.close - first) * 1000 / first } ?: 0L
        val range = tr("kami_economy.chart.range.${span.arg}")
        fun sp(p: Long) = d?.let { ticket.priceOf(it, p) } ?: p
        val lotShown = d?.let { ticket.lotOf(it) } ?: 1
        fun source(server: Boolean) = tr(if (server) "kami_libs.party.server" else "kami_libs.common.player")
        val quest = d?.quest
        val qh = if (quest != null) QUEST_BANNER_H + 4 else 0
        quest?.let { q -> runCatching { ui.questBanner(app, Rect(r.x, r.y + 26, r.w, QUEST_BANNER_H), q) }.onFailure { if (!bannerWarned) LOG.warn("Quest banner {} failed", q.id, it); bannerWarned = true } }
        val lotSub = lotText(lotShown).takeIf { it.isNotEmpty() }
        ui.kpiRow(r.dropTop(26 + qh).top(44), listOf(
            KpiTile(tr("kami_economy.admin.col.course"), d?.let { Format.unitMoney(sp(it.course.roundToLong()), lotShown) }, trend = d?.let { Trend(delta, signedPercent(delta / 10.0, 1)) },
                tip = d?.let { Tip(null, listOf(
                    tr("kami_economy.kpi.course", Format.percent(it.spread, 1)) to Palette.textSecondary,
                    tr("kami_economy.item.change.tip", range) to Palette.textMuted,
                    "${tr("kami_economy.item.sold_bought")}: ${Format.number(it.sold)} / ${Format.number(it.bought)}" to Palette.textSecondary,
                    tr("kami_economy.tip.last",Format.unitMoney(sp(it.last.toLong()), lotShown)) to Palette.textSecondary
                )) } ?: Tip.text(tr("kami_economy.kpi.course", "–"))),
            KpiTile(tr("kami_economy.col.buy_now"), d?.let { Format.money(sp(it.buy.toLong())) }, color = Palette.money, sub = lotSub, tip = d?.takeIf { it.buy > 0 }?.let { Tip.text(tr("kami_economy.kpi.buy", lotShown), source(it.buyServer)) } ?: Tip.text(tr("kami_economy.kpi.buy", 1))),
            KpiTile(tr("kami_economy.market.sell_now"), d?.let { Format.money(sp(it.sell.toLong())) }, color = Palette.money, sub = lotSub, tip = d?.takeIf { it.sell > 0 }?.let { Tip.text(tr("kami_economy.kpi.sell", lotShown), source(it.sellServer)) } ?: Tip.text(tr("kami_economy.kpi.sell", 1)))
        ), key = "item-kpi")
        val main = r.dropTop(76 + qh)
        val ticketW = if (main.w < 440) main.w * 5 / 12 else TICKET_W
        left(ui, main.dropRight(ticketW, 8), d)
        val right = main.right(ticketW)
        ui.anchor("item:ticket", right)
        val actRect = right.bottom(CONTROL_H)
        val act = arrayOfNulls<Act>(1)
        ui.scroll("ticket", right.dropBottom(CONTROL_H + 6), TICKET_H) { c -> act[0] = ticket.draw(ui, Flow(c, 4), d) }
        val sticky = act[0]
        if (sticky == null) { if (d == null) ui.skeleton(actRect) }
        else if (ui.button(actRect, sticky.label, style = ButtonStyle.PRIMARY, enabled = sticky.reason == null, disabledReason = sticky.reason, pending = sticky.pending, key = "act")) sticky.run()
    }

    private fun left(ui: Ui, r: Rect, d: Detail?) {
        val asks = d?.asks.orEmpty().take(BOOK_ROWS).reversed().map { l -> d?.let { l.copy(price = ticket.priceOf(it, l.price.toLong()).toInt(), amount = ticket.unitsOf(l.amount)) } ?: l }
        val bids = d?.bids.orEmpty().take(BOOK_ROWS).map { l -> d?.let { l.copy(price = ticket.priceOf(it, l.price.toLong()).toInt(), amount = ticket.unitsOf(l.amount)) } ?: l }
        val vendors = d?.vendors.orEmpty()
        val lot = d?.let { ticket.lotOf(it) } ?: 1
        val qc = questColor(d?.quest?.rarity ?: 0)
        val rows = (asks.size + bids.size).coerceAtLeast(1) * BOOK_ROW + BOOK_MID
        val vendorH = if (vendors.isEmpty()) 0 else 20 + if (vendorsOpen) (vendors.take(VENDOR_ROWS).size + if (vendors.size > VENDOR_ROWS) 1 else 0) * BOOK_ROW else 0
        val hidden = d?.let { (it.asks.size - BOOK_ROWS).coerceAtLeast(0) + (it.bids.size - BOOK_ROWS).coerceAtLeast(0) } ?: 0
        val unit = d?.let { ticket.priceOf(it, 1_000_000L) } ?: 1_000_000L
        val factor = unit / 1_000_000.0 / lot.coerceAtLeast(1)
        val candles = ui.filtered("chart:history:${ticket.shown}", d?.chart?.candles ?: emptyList<Any>()) {
            d?.chart?.candles.orEmpty().map { Ohlc(it.open * factor, it.high * factor, it.low * factor, it.close * factor, it.volume.toDouble(), it.at) }
        }
        ui.scroll("item-left", r, HEAD_H + 4 + CHART_H + 8 + 12 + rows + vendorH) { c ->
            val chart = Rect(c.x, c.y + HEAD_H + 4, c.w, CHART_H)
            ui.anchor("item:chart", chart)
            val open = d?.chart?.open?.toDouble()?.takeIf { it > 0 }?.let { it * factor }
            val ready = d != null && (!waiting || spans[item] == span)
            val hovered = if (ready) ui.priceChart(chart, candles, style, open, d?.chart?.start ?: 0L, this::time) else null
            if (!ready) ui.skeleton(chart) else if (waiting) ui.staleVeil(chart)
            chartHeader(ui, Rect(c.x, c.y, c.w, HEAD_H), if (ready) hovered ?: candles.lastOrNull() else null, open, hovered != null)
            var y = chart.bottom + 8
            ui.anchor("item:book", Rect(c.x, y, c.w, 12 + rows))
            Draw.text(ui.g, tr("kami_economy.market.book"), c.x, y, Palette.textSecondary)
            if (hidden > 0) Draw.textRight(ui.g, tr("kami_libs.common.more", hidden), c.right, y, Palette.textMuted)
            y += 12
            if (d == null) ui.skeletonLines(Rect(c.x, y, c.w, BOOK_ROW), 1)
            else if (asks.isEmpty() && bids.isEmpty()) ui.fitText(c.x, y + 1, c.w, tr("kami_economy.market.book.empty"), Palette.textMuted)
            val most = (asks + bids).maxOfOrNull { it.amount } ?: 0
            asks.forEachIndexed { i, l -> bookRow(ui, Rect(c.x, y + i * BOOK_ROW, c.w, BOOK_ROW), l, Palette.danger, most, "ask$i", false, lot, qc) }
            y += asks.size * BOOK_ROW + BOOK_MID
            bids.forEachIndexed { i, l -> bookRow(ui, Rect(c.x, y + i * BOOK_ROW, c.w, BOOK_ROW), l, Palette.success, most, "bid$i", true, lot, qc) }
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
            fetch()
        }
        val toggle = Rect(r.right - SMALL_H, r.bottom - SMALL_H, SMALL_H, SMALL_H)
        val candle = style == ChartStyle.CANDLE
        if (ui.iconButton(toggle, Icons.STATS, tr(if (candle) "kami_economy.market.chart.line" else "kami_economy.market.chart.candles"), selected = candle, key = "style")) style = if (candle) ChartStyle.LINE else ChartStyle.CANDLE
        if (shown == null || shown.close <= 0) return
        val diff = open?.let { shown.close - it }
        val tone = if (diff == null || diff == 0.0) Palette.textMuted else if (diff > 0) Palette.success else Palette.danger
        val price = Format.rateMoney(shown.close)
        Draw.text(ui.g, price, r.x, r.y, TextStyle.DISPLAY)
        val change = if (diff == null || open == null) "–" else "${if (diff > 0) "▲" else if (diff < 0) "▼" else "•"} ${if (diff > 0) "+" else if (diff < 0) "-" else ""}${Format.rate(abs(diff))} (${Format.percent(abs(diff) / open, 1)})"
        val x = r.x + Draw.width(price, TextStyle.DISPLAY) + 8
        Draw.text(ui.g, change, x, r.y + 1, tone)
        Draw.text(ui.g, if (scrubbing) Format.exact(shown.at) else tr("kami_economy.chart.caption.${span.arg}"), x, r.y + 11, Palette.textMuted)
    }

    private fun bookRow(ui: Ui, r: Rect, l: Level, color: Int, most: Int, key: String, bid: Boolean, lot: Int, qc: Int) {
        val embargo = l.relation == "embargo"
        val shade = if (l.quest) qc else color
        if (most > 0) Draw.fill(ui.g, r.withWidth(if (l.amount < 0) r.w else (r.w.toLong() * l.amount / most).toInt().coerceIn(1, r.w)), Palette.alpha(shade, 0x28))
        if (l.mine) Draw.outline(ui.g, r, Palette.brass)
        if (l.quest) Draw.fill(ui.g, Rect(r.x + 4, r.y + 2, 7, 7), qc)
        else if (l.country.isNotEmpty()) ui.flag(Rect(r.x + 2, r.y + 1, 11, 8), l.color, l.flag)
        val price = Format.unitMoney(l.price.toLong(), lot)
        Draw.text(ui.g, price, r.x + 16, r.y + 1, if (l.mine) Palette.brass else if (embargo) Palette.textMuted else shade)
        var used = r.x + 16 + Draw.width(price)
        if (l.quest) {
            val label = tr("kami_economy.quest.ui.book")
            Draw.text(ui.g, label, used + 4, r.y + 1, qc)
            used += 4 + Draw.width(label)
        }
        if (l.tariff > 0 && !l.mine && !embargo) {
            val tariff = tr("kami_economy.book.tariff", l.tariff)
            Draw.text(ui.g, tariff, used + 4, r.y + 1, Palette.warning)
            used += 4 + Draw.width(tariff)
        }
        val qty = countText(l.amount)
        val amount = when {
            l.market -> tr("kami_libs.common.market") + " " + qty
            embargo -> tr("kami_libs.common.embargo")
            else -> qty
        }
        val amountW = (r.right - 3 - used - 6).coerceAtLeast(0)
        ui.fitText(r.right - 3 - amountW, r.y + 1, amountW, amount, if (embargo) Palette.danger else Palette.textMuted, align = Align.RIGHT)
        val side = if (bid) "bid" else "ask"
        val kind = tr(if (l.market) "kami_economy.book.tip.market.$side" else "kami_economy.book.tip.$side", qty, Format.unitMoney(l.price.toLong(), lot))
        ui.tooltip("book:$key", r, Tip(lines = listOfNotNull(
            kind to Palette.textSecondary,
            (tr("kami_economy.quest.ui.book.tip") to Palette.success).takeIf { l.quest },
            l.country.takeIf { it.isNotEmpty() && !l.quest }?.let { app.countryTip(it, l.relation) to Palette.textMuted },
            (if (bid || l.market) tr("kami_economy.book.tip.tax.you", if (l.market) app.snap.taxPct else app.taxFor(l.relation)) to Palette.warning
            else tr("kami_economy.book.tip.tax.seller", app.taxFor(l.relation)) to Palette.success).takeIf { !l.mine && !embargo && !l.quest },
            (tr("kami_economy.book.tip.tariff", l.tariff, l.country) to Palette.warning).takeIf { l.tariff > 0 && !l.mine && !embargo && !l.quest },
            (tr("kami_economy.book.tip.mine") to Palette.brass).takeIf { l.mine }
        )))
    }

    private fun vendorBlock(ui: Ui, r: Rect, d: Detail?) {
        val vendors = d?.vendors.orEmpty()
        val cheapest = vendors.filter { it.sell }.minByOrNull { it.price }
        val text = cheapest?.let { tr("kami_economy.item.vendor", Format.unitMoney(it.price.toLong(), it.lot), it.country) } ?: tr("kami_economy.item.vendors", vendors.size)
        val toggle = tr(if (vendorsOpen) "kami_economy.item.vendors.hide" else "kami_economy.item.vendors.show", vendors.size)
        ui.fitText(r.x, r.y + 4, r.w - Draw.width(toggle) - 8, text, Palette.textSecondary)
        if (ui.link(r.right - Draw.width(toggle), r.y + 4, toggle, key = "vendors-toggle")) vendorsOpen = !vendorsOpen
        if (!vendorsOpen) return
        vendors.take(VENDOR_ROWS).forEachIndexed { i, v ->
            val row = Rect(r.x, r.y + 20 + i * BOOK_ROW, r.w, BOOK_ROW)
            ui.flag(Rect(row.x + 2, row.y + 1, 11, 8), v.color, v.flag)
            ui.fitText(row.x + 16, row.y + 1, row.w / 2 - 20, v.country, if (v.embargo) Palette.danger else Palette.text)
            val kind = tr(if (v.sell) "kami_economy.market.vendors.sells" else "kami_economy.market.vendors.buys")
            val price = Format.unitMoney(v.price.toLong(), v.lot)
            Draw.text(ui.g, price, row.right - Draw.width(price) - 3, row.y + 1, Palette.text)
            Draw.text(ui.g, kind, row.right - Draw.width(price) - Draw.width(kind) - 12, row.y + 1, if (v.sell) Palette.danger else Palette.success)
            if (v.embargo) ui.tooltip("vendor-embargo:$i", row, tr("kami_libs.lock.embargo", v.country))
        }
        if (vendors.size > VENDOR_ROWS) Draw.text(ui.g, tr("kami_libs.common.more", vendors.size - VENDOR_ROWS), r.x + 16, r.y + 21 + VENDOR_ROWS * BOOK_ROW, Palette.textMuted)
    }
}
