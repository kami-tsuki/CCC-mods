package kami.economy.client.ui

import kami.economy.economy.Blacklist
import kami.economy.economy.Classification
import kami.economy.economy.StackCodec
import kami.economy.net.AuctionLine
import kami.economy.net.Detail
import kami.economy.net.OrderLine
import kami.libs.economy.CleanStep
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
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
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import kotlin.math.max
import kotlin.math.min

private const val ROW_H = 18
private const val MAX_PRICE = 1_000_000L

private fun Ui.itemCell(c: Rect, stack: ItemStack, name: String, key: String, color: Int = Palette.text): Boolean {
    val clicked = itemSlot(Rect(c.x, c.y, c.h, c.h), stack, key = key)
    Draw.text(g, Draw.fit(name, c.w - c.h - 4), c.x + c.h + 4, c.y + (c.h - 8) / 2, color)
    return clicked
}

private fun Ui.actionButton(r: Rect, label: String, enabled: Boolean = true, reason: String? = null) =
    button(r.right(buttonWidth(label)), label, style = ButtonStyle.PRIMARY, enabled = enabled, disabledReason = reason)

private fun ownedAllowed(item: String): Int = Minecraft.getInstance().player?.inventory?.items
    ?.filter { !it.isEmpty && Blacklist.itemId(it) == item && Blacklist.classify(it) == Classification.ALLOWED }?.sumOf { it.count } ?: 0

internal class OrdersPage(val app: MarketApp) : Page() {
    private val table = TableState<OrderLine>()
    override val title get() = tr("kami_economy.market.tab.orders")

    private fun key(o: OrderLine) = if (o.bid) "bid:${o.item}" else o.item
    private fun mode(o: OrderLine) = if (o.bid) "bid" else "edit"

    private val columns = listOf(
        Column<OrderLine>(tr("kami_economy.market.sell.col.item"), -1, sort = compareBy { app.stack(it.item).hoverName.string.lowercase() }) { _, c, o ->
            val name = app.stack(o.item).hoverName.string.let { if (o.bid) tr("kami_economy.market.orders.bid", it) else it }
            if (itemCell(c, app.stack(o.item), name, "order:${key(o)}", if (o.bid) Palette.success else Palette.text)) app.openItem(o.item, mode(o))
        },
        Column.number(tr("kami_economy.market.sell.col.count"), 50) { it.amount.toLong() },
        Column.number(tr("kami_economy.market.sell.col.price"), 80, format = { Format.money(it) }) { it.price.toLong() },
        Column<OrderLine>("", 2 * ROW_H + 2) { _, c, o ->
            if (iconButton(Rect(c.x, c.y, c.h, c.h), Icons.EDIT, tr("kami_economy.market.orders.edit.tooltip"), key = "edit:${key(o)}")) app.openItem(o.item, mode(o))
            if (iconButton(Rect(c.right - c.h, c.y, c.h, c.h), Icons.CROSS, tr(if (o.bid) "kami_economy.market.bid.cancel.tooltip" else "kami_economy.market.orders.cancel.tooltip"), key = "cancel:${key(o)}"))
                app.request(if (o.bid) "cancel_bid" else "cancel", o.item)
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        val events = ui.table(r.dropBottom(10, 4), columns, app.snap.orders, table, ::key, rowHeight = ROW_H, emptyText = tr("kami_economy.market.orders.empty"))
        events.opened?.let { app.openItem(it.item, mode(it)) }
        Draw.text(ui.g, Draw.fit(tr("kami_economy.market.orders.hint"), r.w), r.x, r.bottom - 8, TextStyle.CAPTION)
    }
}

internal class AuctionsPage(val app: MarketApp) : Page() {
    private val table = TableState<AuctionLine>()
    private val stacks = HashMap<Long, ItemStack>()
    private val bid = NumberState(1)
    private var bidFor = -1L
    override val title get() = tr("kami_economy.market.tab.auctions")

    private fun stack(a: AuctionLine) = stacks.getOrPut(a.id) {
        Minecraft.getInstance().level?.registryAccess()?.let { runCatching { StackCodec.decode(a.stackData, it) }.getOrNull() } ?: ItemStack.EMPTY
    }

    private fun minBid(a: AuctionLine) = maxOf(a.startPrice, a.currentBid + 1).toLong()

    private val columns = listOf(
        Column<AuctionLine>(tr("kami_economy.market.sell.col.item"), -1, sort = compareBy { it.label.lowercase() }) { _, c, a ->
            val name = if (a.mine) "${a.label} ${tr("kami_economy.market.auctions.mine")}" else a.label
            itemCell(c, stack(a), name, "auction:${a.id}", if (a.mine) Palette.textMuted else Palette.text)
        },
        Column.number(tr("kami_economy.market.auctions.col.bid"), 70, format = { Format.money(it) }) { maxOf(it.currentBid, it.startPrice).toLong() },
        Column.text(tr("kami_economy.market.auctions.col.buy_now"), 70, Align.RIGHT) { if (it.buyNowPrice > 0) Format.money(it.buyNowPrice.toLong()) else "–" },
        Column.number(tr("kami_economy.market.auctions.col.ends"), 70, format = { Format.duration(it - System.currentTimeMillis()) }) { it.expiresAt }
    )

    override fun draw(ui: Ui, r: Rect) {
        val snap = app.snap
        ui.table(r.dropBottom(16, 6), columns, snap.auctions, table, { it.id }, rowHeight = ROW_H, emptyText = tr("kami_economy.market.auctions.empty"))
        val bar = r.bottom(16)
        val pagerW = if (snap.auctionPages > 1) 96 else 0
        if (pagerW > 0) ui.pager(bar.right(pagerW), snap.auctionPage, snap.auctionPages, key = "auction-pager")?.let { app.request("auctions", it.toString()) }
        val row = bar.dropRight(pagerW, 8)
        val a = snap.auctions.firstOrNull { it.id in table.selected }
        if (a == null) {
            Draw.text(ui.g, Draw.fit(tr("kami_economy.market.auctions.hint"), row.w), row.x, row.y + 4, TextStyle.CAPTION)
            return
        }
        if (a.mine) {
            if (ui.button(row.left(buttonWidth(tr("kami_economy.market.auction.cancel"))), tr("kami_economy.market.auction.cancel"),
                    enabled = a.currentBidder.isEmpty(), disabledReason = tr("kami_economy.market.auction.cancel.bid"))) {
                app.request("auction_cancel", a.id.toString())
                table.clear()
            }
            return
        }
        val min = minBid(a)
        if (bidFor != a.id) { bidFor = a.id; bid.commit(min) }
        ui.numberField(row.left(110), bid, min, a.buyNowPrice.toLong().takeIf { it > 0 } ?: (min * 10), key = "bid")
        val bidLabel = tr("kami_economy.market.auction.bid")
        val bidR = Rect(row.x + 116, row.y, buttonWidth(bidLabel), row.h)
        if (ui.button(bidR, bidLabel, style = ButtonStyle.PRIMARY)) app.request("auction_bid", a.id.toString(), bid.value.toString())
        if (a.buyNowPrice > 0) {
            val label = tr("kami_economy.market.auction.buy_now", Format.money(a.buyNowPrice.toLong()))
            if (ui.button(Rect(bidR.right + 6, row.y, buttonWidth(label), row.h), label)) {
                app.request("auction_buy", a.id.toString())
                table.clear()
            }
        }
    }
}

internal class ItemPage(val app: MarketApp) : Page() {
    private enum class Res(val arg: String, val label: String) { RAW("raw", "1m"), HOURLY("hourly", "1h"), DAILY("daily", "1d") }

    private var item = ""
    private var mode = "buy"
    private var res = Res.RAW
    private var style = ChartStyle.CANDLE
    private val qty = NumberState(1)
    private val price = NumberState(1)

    override val title get() = app.stack(item).hoverName.string
    override val subtitle get() = tr("kami_economy.market.mode.$mode")

    private fun detail(): Detail? = app.snap.detail?.takeIf { it.item == item }
    private fun quote() = app.request("quote", item, qty.value.toString(), mode)

    private fun quoted() = mode == "buy" || mode == "market"

    override fun opened(route: Route) {
        val next = route.param("item") ?: return
        if (next != item) price.commit(1)
        item = next
        mode = route.param("mode") ?: "buy"
        qty.commit((route.int("qty") ?: 1).toLong())
        app.request("detail", item, res.arg)
        if (quoted()) quote()
    }

    override fun leaving(next: Route): Boolean {
        if (next.page != "item") app.request("detail", "")
        return true
    }

    private fun locked(d: Detail?) = mode == "sell" && (d?.myOrderAmount ?: 0) > 0

    private fun sellPrice(d: Detail?): Int = when {
        locked(d) -> d?.myOrderPrice ?: 1
        else -> price.value.toInt().coerceAtLeast(1)
    }

    override fun draw(ui: Ui, r: Rect) {
        val d = detail()
        val head = r.top(22)
        ui.itemSlot(head.left(22), app.stack(item), key = "detail:item")
        val modes = listOf(
            Option("buy", tr("kami_economy.market.mode.buy"), disabledReason = tr("kami_economy.market.buy.none").takeIf { d != null && d.available <= 0 }),
            Option("sell", tr("kami_economy.market.mode.sell"), disabledReason = tr("kami_economy.market.sell.none").takeIf { ownedAllowed(item) <= 0 })
        ) + listOfNotNull(
            Option("market", tr("kami_economy.market.mode.market"), disabledReason = tr("kami_economy.market.sell.none").takeIf { ownedAllowed(item) <= 0 }).takeIf { d?.stock != null || !d?.bids.isNullOrEmpty() },
            Option("bid", tr("kami_economy.market.mode.bid")),
            Option("edit", tr("kami_economy.market.mode.edit"), disabledReason = tr("kami_economy.market.mode.edit.none").takeIf { (d?.myOrderAmount ?: 0) <= 0 }),
            Option("vendors", tr("kami_economy.market.mode.vendors"), disabledReason = tr("kami_economy.market.vendors.none").takeIf { d?.vendors.isNullOrEmpty() })
        )
        val modeW = max(180, modes.sumOf { Draw.width(it.label) + 12 })
        val stockX = head.x + 30 + ui.money(head.x + 30, head.y + 7, (d?.price ?: 0).toLong()) + 10
        val stockText = d?.stock?.let { tr("kami_economy.market.stock.level", Format.money(it.price.toLong()), Format.number(it.lots)) }
            ?: tr("kami_economy.item.available", Format.number(d?.available ?: 0))
        val vendorText = d?.vendorPrice?.takeIf { it > 0 }?.let { tr("kami_economy.market.vendors.price", Format.money(it.toLong())) }
        val headText = listOfNotNull(stockText, vendorText).joinToString(" · ")
        Draw.text(ui.g, Draw.fit(headText, head.right - modeW - 8 - stockX), stockX, head.y + 7, Palette.textSecondary)

        ui.segmented(head.right(modeW).centered(modeW, 16), modes, mode, key = "mode")?.let { app.navigate(Route("item", mapOf("item" to item, "mode" to it)), record = false) }

        val tools = r.dropTop(28).top(16)
        ui.segmented(tools.left(96), Res.entries.map { Option(it, it.label) }, res, key = "res")?.let { res = it; app.request("detail", item, it.arg) }
        ui.segmented(tools.right(120), listOf(Option(ChartStyle.LINE, tr("kami_economy.market.chart.line")), Option(ChartStyle.CANDLE, tr("kami_economy.market.chart.candles"))), style, key = "style")
            ?.let { style = it }

        if (mode == "vendors") {
            vendorList(ui, Rect(r.x, tools.bottom + 6, r.w, r.bottom - tools.bottom - 6), d)
            return
        }
        val form = r.bottom(FORM_H)
        val history = d?.history.orEmpty().map { Ohlc(it.open.toDouble(), it.high.toDouble(), it.low.toDouble(), it.close.toDouble(), it.volume.toDouble(), it.at) }
        val body = Rect(r.x, tools.bottom + 6, r.w, form.y - tools.bottom - 14)
        ui.priceChart(body.dropRight(BOOK_W + 10), history, style)
        orderBook(ui, body.right(BOOK_W), d)
        form(ui, form, d)
    }

    private fun vendorList(ui: Ui, r: Rect, d: Detail?) {
        val vendors = d?.vendors.orEmpty()
        Draw.text(ui.g, Draw.fit(tr("kami_economy.market.vendors.hint"), r.w), r.x, r.y, Palette.textMuted)
        val list = r.dropTop(14)
        val player = Minecraft.getInstance().player
        val here = player?.level()?.dimension()?.location()?.toString()
        ui.scroll("vendors", list, vendors.size * ROW_H) { c ->
            vendors.forEachIndexed { i, v ->
                val y = c.y + i * ROW_H + 5
                v.flag?.let { Flags.draw(ui.g, Rect(c.x, y, 11, 8), v.color, it.pattern, it.emblem, it.secondary) } ?: Draw.fill(ui.g, Rect(c.x, y, 8, 8), v.color or 0xFF000000.toInt())
                Draw.text(ui.g, Draw.fit(v.country, c.w * 3 / 10 - 16), c.x + 14, y, if (v.embargo) Palette.danger else Palette.text)
                if (v.embargo) ui.tooltip("vendor:$i", Rect(c.x, y - 1, c.w * 3 / 10, 10), tr("kami_economy.market.embargo.vendor", v.country))
                val where = "${v.x}, ${v.y}, ${v.z}"
                Draw.text(ui.g, where, c.x + c.w * 3 / 10, y, Palette.textSecondary)
                val dist = if (player != null && here == v.dim) tr("kami_economy.market.vendors.distance", Format.number(player.position().distanceTo(net.minecraft.world.phys.Vec3(v.x + 0.5, v.y + 0.5, v.z + 0.5)).toLong()))
                    else tr("kami_economy.market.vendors.far")
                Draw.text(ui.g, Draw.fit(dist, c.w / 6), c.x + c.w * 11 / 20, y, Palette.textMuted)
                ui.money(c.x + c.w * 7 / 10, y, v.price.toLong())
                val kind = tr(if (v.sell) "kami_economy.market.vendors.sells" else "kami_economy.market.vendors.buys") +
                    if (v.stock >= 0) " · " + tr("kami_economy.market.vendors.stock", Format.number(v.stock)) else ""
                Draw.text(ui.g, Draw.fit(kind, c.w * 3 / 20), c.right - min(Draw.width(kind), c.w * 3 / 20), y, if (v.sell) Palette.danger else Palette.success)
            }
        }
    }

    private fun orderBook(ui: Ui, r: Rect, d: Detail?) {
        Draw.text(ui.g, Draw.fit(tr("kami_economy.market.book"), r.w), r.x, r.y, Palette.textSecondary)
        val asks = d?.asks.orEmpty().reversed()
        val rows = asks.map { it to Palette.danger } + d?.bids.orEmpty().map { it to Palette.success }
        if (rows.isEmpty()) Draw.text(ui.g, Draw.fit(tr("kami_economy.market.book.empty"), r.w), r.x, r.y + 14, Palette.textMuted)
        rows.forEachIndexed { i, (level, color) ->
            val y = r.y + 14 + i * 11 + if (i >= asks.size) 4 else 0
            if (y + 8 > r.bottom) return
            val embargo = level.relation == "embargo"
            level.flag?.let { Flags.draw(ui.g, Rect(r.x, y, 11, 8), level.color, it.pattern, it.emblem, it.secondary) }
            Draw.text(ui.g, Format.money(level.price.toLong()), r.x + 14, y, if (embargo) Palette.textMuted else color)
            val amount = when {
                level.market -> tr("kami_economy.market.book.market")
                embargo -> tr("kami_economy.market.embargo")
                else -> Format.number(level.amount)
            }
            Draw.text(ui.g, amount, r.right - Draw.width(amount), y, if (embargo) Palette.danger else Palette.textMuted)
            if (level.country.isNotEmpty()) ui.tooltip("book:$i", Rect(r.x, y - 1, r.w, 10),
                if (embargo) tr("kami_economy.market.embargo.tip", level.country) else level.country + " · " + tr("kami_economy.market.rate.${level.relation}"))
        }
    }

    private fun hasBid(d: Detail?) = (d?.myBidAmount ?: 0) > 0

    private fun bidPrice(d: Detail?): Int = if (hasBid(d)) d?.myBidPrice ?: 1 else price.value.toInt().coerceAtLeast(1)

    private fun escrow(d: Detail?): Long = qty.value / (d?.lot ?: 1).coerceAtLeast(1) * bidPrice(d)

    private fun crosses(d: Detail?) = (d?.asks?.firstOrNull()?.price ?: Int.MAX_VALUE) <= bidPrice(d)

    private fun stepItems(d: Detail?): Long {
        val lot = (d?.lot ?: 1).coerceAtLeast(1)
        return when (mode) {
            "buy" -> (d?.buyStep ?: lot).toLong()
            "market" -> (d?.stock?.step ?: lot).toLong()
            "bid" -> CleanStep.step(bidPrice(d).toLong(), listOf(app.snap.taxPct)).toLong() * lot
            else -> CleanStep.step(sellPrice(d).toLong(), listOf(app.snap.taxPct)).toLong() * lot
        }.coerceAtLeast(1)
    }

    private fun form(ui: Ui, r: Rect, d: Detail?) {
        val (fields, preview) = r.columns(listOf(0.4f, 0.6f), 16)
        val first = fields.top(28)
        val second = Rect(fields.x, first.bottom + 6, fields.w, 28)
        val lot = (d?.lot ?: 1).coerceAtLeast(1)
        val step = stepItems(d)
        if (mode != "edit" && !(mode == "bid" && hasBid(d))) {
            val stepText = if (step > 1) "  ·  " + tr("kami_economy.market.step", Format.number(step)) else ""
            ui.fieldLabel(first, tr("kami_economy.market.quantity") + stepText)
            val max = when (mode) {
                "buy" -> (d?.maxAffordable ?: 1).coerceAtLeast(1)
                "bid" -> MAX_QTY
                else -> ownedAllowed(item).coerceAtLeast(1)
            }
            if (d != null && qty.value % step != 0L) {
                qty.commit((qty.value / step * step).coerceAtLeast(step))
                if (quoted()) quote()
            }
            ui.numberField(first.bottom(16), qty, step, max.toLong().coerceAtLeast(step), step = step, key = "qty")?.let { if (quoted()) quote() }
        }
        val priceSlot = if (mode == "edit") first else second
        if (mode == "edit" || mode == "sell" && !locked(d) || mode == "bid" && !hasBid(d)) {
            val label = when {
                mode == "edit" -> tr("kami_economy.market.edit.new_price")
                lot > 1 -> tr("kami_economy.market.price_lot", lot)
                else -> tr("kami_economy.market.price")
            }
            ui.fieldLabel(priceSlot, label)
            ui.numberField(priceSlot.bottom(16), price, 1, MAX_PRICE, key = "price")
        }

        val lines = mutableListOf<Pair<String, Int>>()
        var rateTip: Pair<Int, String>? = null
        val n = qty.value.toInt()
        when (mode) {
            "buy" -> {
                val q = app.snap.quote?.takeIf { it.item == item && it.qty == n }
                if (q == null) lines += tr("kami_economy.market.calculating") to Palette.textMuted
                else {
                    if (q.filled < n) lines += tr("kami_economy.market.partial", Format.number(q.filled)) to Palette.warning
                    if (q.stock > 0) lines += tr("kami_economy.market.quote.stock", Format.number(q.stock)) to Palette.textSecondary
                    lines += tr("kami_economy.market.quote.price", Format.money(q.total - q.buyerTax)) to Palette.text
                    if (q.buyerTax > 0) lines += tr("kami_economy.market.quote.tax_buyer", app.snap.taxPct, Format.money(q.buyerTax)) to Palette.textMuted
                    if (q.tax > q.buyerTax) {
                        val rate = q.relation.takeIf { it.isNotEmpty() }?.let { " · " + tr("kami_economy.market.rate.$it") } ?: ""
                        rateTip = lines.size to q.relation
                        lines += tr("kami_economy.market.quote.tax_seller", if (q.taxPct > 0) q.taxPct else app.snap.taxPct, Format.money(q.tax - q.buyerTax)) + rate to Palette.textMuted
                    }
                    if (q.tariff > 0) lines += tr("kami_economy.market.quote.tariff", q.tariffPct, Format.money(q.tariff)) to Palette.textMuted
                    lines += tr("kami_economy.market.total", Format.money(q.total.toLong()), Format.money(q.avg.toLong())) to (if (q.total <= app.snap.funds) Palette.success else Palette.danger)
                    lines += tr("kami_economy.market.funds", Format.money(app.snap.funds)) to Palette.textMuted
                }
            }
            "sell" -> {
                val unit = sellPrice(d)
                val gross = unit.toLong() * (n / lot)
                val tax = CleanStep.charge(gross, app.snap.taxPct)
                if (locked(d)) lines += tr("kami_economy.market.price.existing", Format.money(unit.toLong())) to Palette.success
                lines += tr("kami_economy.market.gross", Format.money(gross.toLong())) to Palette.text
                lines += tr("kami_economy.market.tax", app.snap.taxPct, Format.money(tax.toLong())) to Palette.danger
                lines += tr("kami_economy.market.net", Format.money((gross - tax).toLong())) to Palette.success
            }
            "market" -> {
                val q = app.snap.quote?.takeIf { it.item == item && it.qty == n && it.market }
                val stock = d?.stock
                if (q == null) lines += tr("kami_economy.market.calculating") to Palette.textMuted
                else {
                    if (stock != null) lines += (if (stock.capLeft > 0) tr("kami_economy.market.stock.guaranteed", stock.capLeft, stock.cap) else tr("kami_economy.market.stock.cap_used")) to Palette.success
                    if (q.filled < n) lines += tr("kami_economy.market.sell.partial", Format.number(q.filled)) to Palette.warning
                    if (q.after > 0 && q.filled / lot > q.guaranteed) lines += tr("kami_economy.market.stock.after", Format.money(q.after.toLong())) to Palette.textSecondary
                    lines += tr("kami_economy.market.gross", Format.money(q.total + q.tax)) to Palette.text
                    lines += tr("kami_economy.market.tax", app.snap.taxPct, Format.money(q.tax)) to Palette.danger
                    lines += tr("kami_economy.market.net", Format.money(q.total)) to Palette.success
                }
            }
            "bid" -> {
                val total = escrow(d)
                if (hasBid(d)) lines += tr("kami_economy.market.bid.current", Format.number(d?.myBidAmount ?: 0), Format.money(bidPrice(d).toLong())) to Palette.text
                else {
                    if (crosses(d)) lines += tr("kami_economy.market.bid.crosses") to Palette.warning
                    lines += tr("kami_economy.market.bid.reserved", Format.money(total)) to (if (total <= app.snap.funds) Palette.success else Palette.danger)
                    lines += tr("kami_economy.market.bid.tax") to Palette.textMuted
                    lines += tr("kami_economy.market.funds", Format.money(app.snap.funds)) to Palette.textMuted
                }
            }
            else -> lines += tr("kami_economy.market.edit.current", Format.number(d?.myOrderAmount ?: 0), Format.money((d?.myOrderPrice ?: 0).toLong())) to Palette.text
        }
        lines.forEachIndexed { i, (text, color) -> Draw.text(ui.g, Draw.fit(text, preview.w), preview.x, preview.y + i * 12, color) }
        rateTip?.takeIf { it.second.isNotEmpty() }?.let { (i, relation) -> ui.tooltip("rate-tip", Rect(preview.x, preview.y + i * 12, preview.w, 10), tr("kami_economy.market.rate.$relation.tip")) }
    }

    private fun actionLabel(d: Detail?) = tr(when (mode) {
        "buy" -> "kami_economy.market.buy"
        "sell" -> "kami_economy.market.sell"
        "market" -> "kami_economy.market.sell_market"
        "bid" -> if (hasBid(d)) "kami_economy.market.bid.cancel" else "kami_economy.market.bid.place"
        else -> "kami_economy.market.update_price"
    })

    override fun actionsWidth() = if (mode == "vendors") 0 else buttonWidth(actionLabel(detail()))

    override fun actions(ui: Ui, r: Rect) {
        val d = detail()
        val n = qty.value.toInt()
        val lot = (d?.lot ?: 1).coerceAtLeast(1)
        if (mode == "vendors") return
        val label = actionLabel(d)
        when (mode) {
            "buy" -> {
                val q = app.snap.quote?.takeIf { it.item == item && it.qty == n }
                val reason = when {
                    q == null -> tr("kami_economy.market.buy.pending")
                    q.filled <= 0 -> tr("kami_economy.market.buy.none")
                    q.total > app.snap.funds -> tr("kami_economy.market.buy.funds")
                    else -> null
                }
                if (ui.actionButton(r, label, reason == null, reason)) { app.request("buy", item, n.toString()); app.closeDetail() }
            }
            "sell" -> {
                if (ui.actionButton(r, label, n in 1..ownedAllowed(item), tr("kami_economy.market.sell.none"))) {
                    app.request("sell", item, n.toString(), sellPrice(d).toString())
                    app.closeDetail()
                }
            }
            "market" -> {
                val q = app.snap.quote?.takeIf { it.item == item && it.qty == n && it.market }
                val reason = when {
                    n !in 1..ownedAllowed(item) -> tr("kami_economy.market.sell.none")
                    q == null -> tr("kami_economy.market.buy.pending")
                    q.filled <= 0 -> tr(if (d?.stock?.let { it.room <= 0 } == true) "kami_economy.market.stock.full" else "kami_economy.action.no_buyers")
                    q.total <= 0 -> tr("kami_economy.market.stock.worthless")
                    else -> null
                }
                if (ui.actionButton(r, label, reason == null, reason)) {
                    app.request("sell_market", item, n.toString())
                    app.closeDetail()
                }
            }
            "bid" -> if (hasBid(d)) {
                if (ui.actionButton(r, label)) app.request("cancel_bid", item)
            } else {
                val reason = when {
                    n <= 0 -> tr("kami_economy.action.invalid_quantity")
                    crosses(d) -> tr("kami_economy.market.bid.crosses")
                    escrow(d) > app.snap.funds -> tr("kami_economy.market.buy.funds")
                    else -> null
                }
                if (ui.actionButton(r, label, reason == null, reason)) {
                    app.request("bid", item, n.toString(), bidPrice(d).toString())
                    app.closeDetail()
                }
            }
            else -> if (ui.actionButton(r, label)) {
                app.request("reprice", item, price.value.coerceAtLeast(1).toString())
                app.closeDetail()
            }
        }
    }

    companion object {
        const val FORM_H = 62
        const val BOOK_W = 110
        const val MAX_QTY = 10_000
    }
}

internal class ListAuctionPage(val app: MarketApp) : Page() {
    private var item = ""
    private var slot = -1
    private val start = NumberState(1)
    private val buyNow = NumberState(0)

    override val title get() = tr("kami_economy.market.auction.list")
    override val subtitle get() = stack().hoverName.string

    private fun stack(): ItemStack = Minecraft.getInstance().player?.inventory?.getItem(slot)?.takeIf { slot >= 0 } ?: ItemStack.EMPTY

    override fun opened(route: Route) {
        item = route.param("item") ?: return
        slot = route.int("slot") ?: -1
        start.commit(1)
        buyNow.commit(0)
        app.request("detail", item)
    }

    override fun leaving(next: Route): Boolean {
        app.request("detail", "")
        return true
    }

    override fun draw(ui: Ui, r: Rect) {
        val stack = stack()
        ui.itemCell(r.top(22), stack, if (stack.isEmpty) item else stack.hoverName.string, "auction:item")
        Draw.text(ui.g, Draw.fit(tr("kami_economy.market.auction_only"), r.w), r.x, r.y + 30, Palette.textSecondary)
        val (a, b) = Rect(r.x, r.y + 48, minOf(r.w, 320), 28).columns(2, 12)
        ui.fieldLabel(a, tr("kami_economy.market.auction.start_price"))
        ui.numberField(a.bottom(16), start, 1, MAX_PRICE, key = "start")
        ui.fieldLabel(b, tr("kami_economy.market.auction.buy_now_optional"))
        ui.numberField(b.bottom(16), buyNow, 0, MAX_PRICE, key = "buy-now")
        Draw.text(ui.g, Draw.fit(tr("kami_economy.market.auction.fee", app.snap.auctionFeePct), r.w), r.x, r.y + 86, Palette.warning)
    }

    override fun actionsWidth() = buttonWidth(tr("kami_economy.market.auction.list"))

    override fun actions(ui: Ui, r: Rect) {
        val invalid = buyNow.value in 1..start.value
        if (ui.actionButton(r, tr("kami_economy.market.auction.list"), !invalid, tr("kami_economy.market.auction.buy_now.invalid"))) {
            app.request("auction_list", slot.toString(), start.value.toString(), buyNow.value.takeIf { it > start.value }?.toString() ?: "0")
            app.closeDetail()
        }
    }
}
