package kami.economy.client.ui

import kami.economy.economy.Blacklist
import kami.economy.economy.Classification
import kami.economy.economy.StackCodec
import kami.economy.net.AuctionLine
import kami.economy.net.Detail
import kami.economy.net.OrderLine
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

    private val columns = listOf(
        Column<OrderLine>(tr("kami_economy.market.sell.col.item"), -1, sort = compareBy { app.stack(it.item).hoverName.string.lowercase() }) { _, c, o ->
            if (itemCell(c, app.stack(o.item), app.stack(o.item).hoverName.string, "order:${o.item}")) app.openItem(o.item, "edit")
        },
        Column.number(tr("kami_economy.market.sell.col.count"), 50) { it.amount.toLong() },
        Column.number(tr("kami_economy.market.sell.col.price"), 80, format = { Format.money(it) }) { it.price.toLong() },
        Column<OrderLine>("", 2 * ROW_H + 2) { _, c, o ->
            if (iconButton(Rect(c.x, c.y, c.h, c.h), Icons.EDIT, tr("kami_economy.market.orders.edit.tooltip"), key = "edit:${o.item}")) app.openItem(o.item, "edit")
            if (iconButton(Rect(c.right - c.h, c.y, c.h, c.h), Icons.CROSS, tr("kami_economy.market.orders.cancel.tooltip"), key = "cancel:${o.item}")) app.request("cancel", o.item)
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        val events = ui.table(r.dropBottom(10, 4), columns, app.snap.orders, table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_economy.market.orders.empty"))
        events.opened?.let { app.openItem(it.item, "edit") }
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
    private fun quote() = app.request("quote", item, qty.value.toString())

    override fun opened(route: Route) {
        val next = route.param("item") ?: return
        if (next != item) price.commit(1)
        item = next
        mode = route.param("mode") ?: "buy"
        qty.commit((route.int("qty") ?: 1).toLong())
        app.request("detail", item, res.arg)
        if (mode == "buy") quote()
    }

    override fun leaving(next: Route): Boolean {
        if (next.page != "item") app.request("detail", "")
        return true
    }

    private fun locked(d: Detail?) = mode == "sell" && (d?.instantSell == true || (d?.myOrderAmount ?: 0) > 0)

    private fun sellPrice(d: Detail?): Int = when {
        d?.instantSell == true -> d.price
        locked(d) -> d?.myOrderPrice ?: 1
        else -> price.value.toInt().coerceAtLeast(1)
    }

    override fun draw(ui: Ui, r: Rect) {
        val d = detail()
        val head = r.top(22)
        ui.itemSlot(head.left(22), app.stack(item), key = "detail:item")
        val modes = listOf(
            Option("buy", tr("kami_economy.market.mode.buy"), disabledReason = tr("kami_economy.market.buy.none").takeIf { d != null && d.available <= 0 && !d.infinite }),
            Option("sell", tr("kami_economy.market.mode.sell"), disabledReason = tr("kami_economy.market.sell.none").takeIf { ownedAllowed(item) <= 0 }),
            Option("edit", tr("kami_economy.market.mode.edit"), disabledReason = tr("kami_economy.market.mode.edit.none").takeIf { (d?.myOrderAmount ?: 0) <= 0 })
        )
        val modeW = max(180, modes.sumOf { Draw.width(it.label) + 12 })
        val stockX = head.x + 30 + ui.money(head.x + 30, head.y + 7, (d?.price ?: 0).toLong()) + 10
        if (d?.infinite == true) {
            Draw.text(ui.g, "∞", stockX, head.y + 7, Palette.success)
            ui.tooltip("detail:stock", Rect(stockX, head.y, 10, 22), tr("kami_economy.item.unlimited"))
        } else Draw.text(ui.g, Draw.fit(tr("kami_economy.item.available", Format.number(d?.available ?: 0)), head.right - modeW - 8 - stockX), stockX, head.y + 7, Palette.textSecondary)

        ui.segmented(head.right(modeW).centered(modeW, 16), modes, mode, key = "mode")?.let { app.navigate(Route("item", mapOf("item" to item, "mode" to it)), record = false) }

        val tools = r.dropTop(28).top(16)
        ui.segmented(tools.left(96), Res.entries.map { Option(it, it.label) }, res, key = "res")?.let { res = it; app.request("detail", item, it.arg) }
        ui.segmented(tools.right(120), listOf(Option(ChartStyle.LINE, tr("kami_economy.market.chart.line")), Option(ChartStyle.CANDLE, tr("kami_economy.market.chart.candles"))), style, key = "style")
            ?.let { style = it }

        val form = r.bottom(FORM_H)
        val history = d?.history.orEmpty().map { Ohlc(it.open.toDouble(), it.high.toDouble(), it.low.toDouble(), it.close.toDouble(), it.volume.toDouble(), it.at) }
        ui.priceChart(Rect(r.x, tools.bottom + 6, r.w, form.y - tools.bottom - 14), history, style)
        form(ui, form, d)
    }

    private fun form(ui: Ui, r: Rect, d: Detail?) {
        val (fields, preview) = r.columns(listOf(0.4f, 0.6f), 16)
        val first = fields.top(28)
        val second = Rect(fields.x, first.bottom + 6, fields.w, 28)
        if (mode != "edit") {
            ui.fieldLabel(first, tr("kami_economy.market.quantity"))
            val max = if (mode == "buy") (d?.maxAffordable ?: 1).coerceAtLeast(1) else ownedAllowed(item).coerceAtLeast(1)
            ui.numberField(first.bottom(16), qty, 1, max.toLong(), key = "qty")?.let { if (mode == "buy") quote() }
        }
        val priceSlot = if (mode == "edit") first else second
        if (mode == "edit" || mode == "sell" && !locked(d)) {
            ui.fieldLabel(priceSlot, tr(if (mode == "edit") "kami_economy.market.edit.new_price" else "kami_economy.market.price"))
            ui.numberField(priceSlot.bottom(16), price, 1, MAX_PRICE, key = "price")
        }

        val lines = mutableListOf<Pair<String, Int>>()
        val n = qty.value.toInt()
        when (mode) {
            "buy" -> {
                val q = app.snap.quote?.takeIf { it.item == item && it.qty == n }
                if (q == null) lines += tr("kami_economy.market.calculating") to Palette.textMuted
                else {
                    if (q.filled < n) lines += tr("kami_economy.market.partial", Format.number(q.filled)) to Palette.warning
                    lines += tr("kami_economy.market.total", Format.money(q.total.toLong()), Format.money(q.avg.toLong())) to (if (q.total <= app.snap.funds) Palette.success else Palette.danger)
                    lines += tr("kami_economy.market.funds", Format.money(app.snap.funds)) to Palette.textMuted
                }
            }
            "sell" -> {
                val unit = sellPrice(d)
                val gross = unit * n
                val tax = gross * app.snap.taxPct / 100
                if (d?.instantSell == true) lines += tr("kami_economy.market.price.instant", Format.money(unit.toLong())) to Palette.success
                else if (locked(d)) lines += tr("kami_economy.market.price.existing", Format.money(unit.toLong())) to Palette.success
                lines += tr("kami_economy.market.gross", Format.money(gross.toLong())) to Palette.text
                lines += tr("kami_economy.market.tax", app.snap.taxPct, Format.money(tax.toLong())) to Palette.danger
                lines += tr("kami_economy.market.net", Format.money((gross - tax).toLong())) to Palette.success
            }
            else -> lines += tr("kami_economy.market.edit.current", Format.number(d?.myOrderAmount ?: 0), Format.money((d?.myOrderPrice ?: 0).toLong())) to Palette.text
        }
        lines.forEachIndexed { i, (text, color) -> Draw.text(ui.g, Draw.fit(text, preview.w), preview.x, preview.y + i * 12, color) }
    }

    private fun actionLabel(d: Detail?) = tr(when (mode) {
        "buy" -> "kami_economy.market.buy"
        "sell" -> if (d?.instantSell == true) "kami_economy.market.sell_now" else "kami_economy.market.sell"
        else -> "kami_economy.market.update_price"
    })

    override fun actionsWidth() = buttonWidth(actionLabel(detail()))

    override fun actions(ui: Ui, r: Rect) {
        val d = detail()
        val n = qty.value.toInt()
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
            else -> if (ui.actionButton(r, label)) {
                app.request("reprice", item, price.value.coerceAtLeast(1).toString())
                app.closeDetail()
            }
        }
    }

    companion object {
        const val FORM_H = 62
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
