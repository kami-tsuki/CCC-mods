package kami.economy.client.ui

import kami.economy.client.ClientHooks
import kami.economy.economy.Blacklist
import kami.economy.economy.Classification
import kami.economy.net.Candle
import kami.economy.net.Snap
import kami.libs.ui.KamiScreen
import kami.libs.ui.graph.ChartStyle
import kami.libs.ui.graph.Ohlc
import kami.libs.ui.graph.PriceChart
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class MarketScreen(first: Snap) : KamiScreen(Component.literal("Market")) {
    private enum class Tab(val label: String) { BROWSE("Browse"), SELL("Sell"), ORDERS("My Orders"), AUCTIONS("Auctions") }
    private enum class Mode { BUY, SELL, EDIT }
    private enum class HistoryRes(val arg: String, val label: String) { RAW("raw", "1m"), HOURLY("hourly", "1h"), DAILY("daily", "1d") }

    private sealed class Detail(val item: String) {
        class Item(item: String, var mode: Mode) : Detail(item)
        class ListAuction(val slot: Int, val stack: ItemStack, item: String) : Detail(item)
    }

    var snap = first
        private set
    private var tab = Tab.BROWSE
    private var left = 0
    private var top = 0
    private val pw = 360
    private val ph = 380
    private var sellPrice = "1"
    private var auctionStart = "1"
    private var auctionBuyNow = ""
    private var bidAmount = ""
    private var selectedAuction = -1L
    private var messageUntil = 0L
    private var pageLabel = ""
    private var chartStyle = ChartStyle.CANDLE
    private var historyRes = HistoryRes.RAW
    private var detail: Detail? = null
    private var qty = 1

    fun update(next: Snap) {
        if (next.msg.isNotEmpty()) messageUntil = System.currentTimeMillis() + 3500
        snap = next
        rebuildAll()
    }

    private fun act(name: String, vararg args: String) = ClientHooks.request(name, *args)

    private fun itemId(stack: ItemStack): String = Blacklist.itemId(stack)

    private fun stackFor(id: String): ItemStack {
        val baseId = id.substringBefore('#')
        val extra = id.substringAfter('#', "").takeIf { '#' in id }
        val item = ResourceLocation.tryParse(baseId)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: Items.BARRIER
        val stack = ItemStack(item)
        if (extra != null && baseId == "tacz:ammo") {
            val tag = net.minecraft.nbt.CompoundTag()
            tag.putString("AmmoId", extra)
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(tag))
        }
        return stack
    }

    private fun ownedAllowed(item: String): Int {
        val inv = minecraft?.player?.inventory ?: return 0
        return inv.items.filter { !it.isEmpty && itemId(it) == item && Blacklist.classify(it) == Classification.ALLOWED }.sumOf { it.count }
    }

    override fun init() {
        left = (width - pw) / 2
        top = (height - ph) / 2
        rebuildAll()
    }

    private fun openItem(item: String, mode: Mode) {
        val d = detail
        if (d is Detail.Item && d.item == item) {
            d.mode = mode
        } else {
            detail = Detail.Item(item, mode)
            sellPrice = "1"
        }
        qty = when (mode) {
            Mode.BUY -> 1
            Mode.SELL -> 1
            Mode.EDIT -> snap.detail?.takeIf { it.item == item }?.myOrderAmount ?: 1
        }
        act("detail", item, historyRes.arg)
        if (mode == Mode.BUY) act("quote", item, qty.toString())
        rebuildAll()
    }

    private fun setHistoryRes(res: HistoryRes, item: String) {
        historyRes = res
        act("detail", item, historyRes.arg)
        rebuildAll()
    }

    private fun closeDetail() {
        detail = null
        act("detail", "")
        rebuildAll()
    }

    private fun rebuildAll() {
        clearWidgets()
        val d = detail
        if (d != null) {
            detailWidgets(d)
            return
        }

        val tabW = 78
        Tab.entries.forEachIndexed { i, t ->
            addRenderableWidget(
                Button.builder(Component.literal(t.label)) { tab = t; selectedAuction = -1L; rebuildAll() }
                    .bounds(left + 8 + i * (tabW + 3), top + 8, tabW, 18)
                    .build()
            ).active = tab != t
        }

        when (tab) {
            Tab.BROWSE -> browseTab()
            Tab.SELL -> sellTab()
            Tab.ORDERS -> ordersTab()
            Tab.AUCTIONS -> auctionsTab()
        }
    }

    private val contentTop get() = top + 34
    private val contentBottom get() = top + ph - 26
    private val contentLeft get() = left + 8
    private val contentRight get() = left + pw - 8

    private val modeBtnY get() = contentTop
    private val chartBtnY get() = contentTop + 16
    private val iconY get() = contentTop + 34
    private val priceLineY get() = contentTop + 52
    private val chartY get() = contentTop + 64
    private val chartH get() = 112
    private val qtyTextY get() = chartY + chartH + 6
    private val stepY get() = qtyTextY + 12
    private val priceFieldY get() = stepY + 20

    private fun browseTab() {
        val box = EditBox(font, contentLeft, contentTop, 220, 16, Component.literal("search"))
        box.value = snap.query
        box.setResponder { act("search", it, snap.sort, "0") }
        addRenderableWidget(box)

        val sortLabel = when (snap.sort) { "price" -> "Price"; "stock" -> "Stock"; else -> "Name" }
        addRenderableWidget(
            Button.builder(Component.literal("Sort: $sortLabel")) {
                val next = when (snap.sort) { "name" -> "price"; "price" -> "stock"; else -> "name" }
                act("search", snap.query, next, "0")
            }.bounds(contentRight - 100, contentTop, 100, 16).build()
        )

        val gridTop = contentTop + 22
        val cell = 34
        val gap = 2
        val cols = ((contentRight - contentLeft) / (cell + gap)).coerceAtLeast(1)
        snap.rows.forEachIndexed { i, row ->
            val cx = contentLeft + (i % cols) * (cell + gap)
            val cy = gridTop + (i / cols) * (cell + gap)
            if (cy + cell > contentBottom) return@forEachIndexed
            addRenderableWidget(ItemCell(cx, cy, cell, stackFor(row.item), row.price, row.available, row.infinite) {
                openItem(row.item, Mode.BUY)
            })
        }

        pageLabel = "Page ${snap.page + 1} / ${snap.pages}"
        if (snap.page > 0) addRenderableWidget(Button.builder(Component.literal("<")) { act("search", snap.query, snap.sort, (snap.page - 1).toString()) }.bounds(contentLeft, contentBottom + 4, 30, 16).build())
        if (snap.page < snap.pages - 1) addRenderableWidget(Button.builder(Component.literal(">")) { act("search", snap.query, snap.sort, (snap.page + 1).toString()) }.bounds(contentRight - 30, contentBottom + 4, 30, 16).build())
    }

    private class SellEntry(val item: String, val stack: ItemStack, val count: Int, val slot: Int, val cls: Classification)

    private fun sellEntries(): List<SellEntry> {
        val inv = minecraft?.player?.inventory ?: return emptyList()
        val entries = mutableListOf<SellEntry>()
        val grouped = HashMap<String, Int>()
        for (i in 0 until inv.items.size) {
            val stack = inv.items[i]
            if (stack.isEmpty) continue
            val cls = Blacklist.classify(stack)
            if (cls == Classification.BLOCKED) continue
            val id = itemId(stack)
            if (cls == Classification.ALLOWED) {
                val idx = grouped[id]
                if (idx != null) {
                    val e = entries[idx]
                    entries[idx] = SellEntry(e.item, e.stack, e.count + stack.count, e.slot, e.cls)
                } else {
                    grouped[id] = entries.size
                    entries += SellEntry(id, stack, stack.count, i, cls)
                }
            } else {
                entries += SellEntry(id, stack, stack.count, i, cls)
            }
        }
        return entries
    }

    private fun sellTab() {
        val entries = sellEntries()
        val gridTop = contentTop
        val cell = 34
        val gap = 2
        val cols = ((contentRight - contentLeft) / (cell + gap)).coerceAtLeast(1)
        entries.forEachIndexed { idx, e ->
            val cx = contentLeft + (idx % cols) * (cell + gap)
            val cy = gridTop + (idx / cols) * (cell + gap)
            if (cy + cell > contentBottom) return@forEachIndexed
            val display = e.stack.copy().also { it.count = e.count }
            addRenderableWidget(SellCell(cx, cy, cell, display) {
                if (e.cls == Classification.AUCTION_ONLY) {
                    detail = Detail.ListAuction(e.slot, e.stack.copy(), e.item)
                    act("detail", e.item)
                    rebuildAll()
                } else {
                    openItem(e.item, Mode.SELL)
                }
            })
        }
        if (entries.isEmpty()) return
    }

    private fun ordersTab() {
        val rowH = 20
        snap.orders.forEachIndexed { i, o ->
            val y = contentTop + i * (rowH + 2)
            if (y + rowH > contentBottom) return@forEachIndexed
            addRenderableWidget(OrderRow(contentLeft, y, contentRight - contentLeft, rowH, stackFor(o.item), o.amount, o.price,
                onEdit = { openItem(o.item, Mode.EDIT) },
                onCancel = { act("cancel", o.item) }
            ))
        }
    }

    private fun auctionsTab() {
        val rowH = 18
        val listBottom = if (selectedAuction >= 0) contentBottom - 42 else contentBottom
        snap.auctions.forEachIndexed { i, a ->
            val y = contentTop + i * (rowH + 2)
            if (y + rowH > listBottom) return@forEachIndexed
            addRenderableWidget(AuctionRow(contentLeft, y, contentRight - contentLeft, rowH, a) { selectedAuction = a.id; rebuildAll() })
        }
        if (snap.auctionPage > 0) addRenderableWidget(Button.builder(Component.literal("<")) { act("auctions", (snap.auctionPage - 1).toString()) }.bounds(contentLeft, contentBottom + 4, 30, 16).build())
        if (snap.auctionPage < snap.auctionPages - 1) addRenderableWidget(Button.builder(Component.literal(">")) { act("auctions", (snap.auctionPage + 1).toString()) }.bounds(contentRight - 30, contentBottom + 4, 30, 16).build())

        val a = snap.auctions.firstOrNull { it.id == selectedAuction } ?: return
        val y = contentBottom - 38
        if (a.mine) {
            addRenderableWidget(Button.builder(Component.literal("Cancel listing")) { act("auction_cancel", a.id.toString()); selectedAuction = -1L }.bounds(contentLeft, y, 150, 16).build()).active = a.currentBidder.isEmpty()
        } else {
            val minBid = maxOf(a.startPrice, a.currentBid + 1)
            numberField(contentLeft, y, contentRight - contentLeft, 16, bidAmount.toIntOrNull() ?: minBid, minBid, { a.buyNowPrice.takeIf { it > 0 } ?: (minBid * 10) }) { v -> bidAmount = v.toString() }
            addRenderableWidget(Button.builder(Component.literal("Bid")) {
                val amount = bidAmount.toIntOrNull() ?: return@builder
                act("auction_bid", a.id.toString(), amount.toString())
            }.bounds(contentLeft, y + 18, 60, 16).build())
            if (a.buyNowPrice > 0) {
                addRenderableWidget(Button.builder(Component.literal("Buy now (${Ui.fmt(a.buyNowPrice)})")) {
                    act("auction_buy", a.id.toString())
                    selectedAuction = -1L
                }.bounds(contentLeft + 64, y + 18, 150, 16).build())
            }
        }
    }

    private fun maxQty(d: Detail.Item): Int = when (d.mode) {
        Mode.BUY -> snap.detail?.maxAffordable?.coerceAtLeast(1) ?: qty
        Mode.SELL -> ownedAllowed(d.item)
        Mode.EDIT -> qty
    }

    private fun stepQty(newQty: Int, d: Detail.Item) {
        if (d.mode == Mode.EDIT) return
        qty = newQty.coerceIn(1, maxQty(d).coerceAtLeast(1))
        if (d.mode == Mode.BUY) act("quote", d.item, qty.toString())
        rebuildAll()
    }

    private fun detailWidgets(d: Detail) {
        if (d is Detail.ListAuction) {
            listAuctionWidgets(d)
            return
        }
        d as Detail.Item

        val canBuy = snap.detail?.let { it.available > 0 || it.infinite } == true
        val canSell = ownedAllowed(d.item) > 0
        val canEdit = snap.detail?.myOrderAmount ?: 0 > 0
        val modes = listOfNotNull(
            Mode.BUY.takeIf { canBuy },
            Mode.SELL.takeIf { canSell },
            Mode.EDIT.takeIf { canEdit }
        )
        modes.forEachIndexed { i, m ->
            addRenderableWidget(
                Button.builder(Component.literal(m.name.lowercase().replaceFirstChar(Char::uppercase))) { openItem(d.item, m) }
                    .bounds(contentLeft + i * 62, modeBtnY, 58, 14).build()
            ).active = d.mode != m
        }

        HistoryRes.entries.forEachIndexed { i, res ->
            addRenderableWidget(
                Button.builder(Component.literal(res.label)) { setHistoryRes(res, d.item) }
                    .bounds(contentLeft + i * 32, chartBtnY, 28, 14).build()
            ).active = historyRes != res
        }

        listOf(ChartStyle.LINE to "Line", ChartStyle.CANDLE to "Candles").forEachIndexed { i, (style, label) ->
            addRenderableWidget(
                Button.builder(Component.literal(label)) { chartStyle = style; rebuildAll() }
                    .bounds(contentRight - 2 * 54 + i * 54, chartBtnY, 50, 14).build()
            ).active = chartStyle != style
        }

        val instantSell = d.mode == Mode.SELL && snap.detail?.instantSell == true
        val locked = d.mode == Mode.SELL && (instantSell || (snap.detail?.myOrderAmount ?: 0) > 0)

        if (d.mode != Mode.EDIT) {
            numberField(contentLeft, stepY, contentRight - contentLeft, 16, qty, 1, { maxQty(d) }) { v -> stepQty(v, d) }
        }

        if (d.mode == Mode.SELL && !locked) {
            numberField(contentLeft, priceFieldY, contentRight - contentLeft, 16, sellPrice.toIntOrNull() ?: 1, 1, { 1_000_000 }) { v -> sellPrice = v.toString() }
        }
        if (d.mode == Mode.EDIT) {
            numberField(contentLeft, stepY, contentRight - contentLeft, 16, sellPrice.toIntOrNull() ?: 1, 1, { 1_000_000 }) { v -> sellPrice = v.toString() }
        }

        val actionY = contentBottom - 18
        addRenderableWidget(Button.builder(Component.literal("Back")) { closeDetail() }.bounds(contentLeft, actionY, 60, 16).build())
        when (d.mode) {
            Mode.BUY -> {
                val q = snap.quote?.takeIf { it.item == d.item && it.qty == qty }
                val afford = q != null && q.filled > 0 && q.total <= snap.funds
                addRenderableWidget(Button.builder(Component.literal("Buy")) {
                    act("buy", d.item, qty.toString())
                    closeDetail()
                }.bounds(contentRight - 80, actionY, 80, 16).build()).active = afford
            }
            Mode.SELL -> {
                addRenderableWidget(Button.builder(Component.literal(if (instantSell) "Sell instantly" else "Sell")) {
                    val p = if (locked) (snap.detail?.let { if (instantSell) it.price else it.myOrderPrice } ?: 1) else sellPrice.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    act("sell", d.item, qty.toString(), p.toString())
                    closeDetail()
                }.bounds(contentRight - 100, actionY, 100, 16).build()).active = qty in 1..ownedAllowed(d.item)
            }
            Mode.EDIT -> {
                addRenderableWidget(Button.builder(Component.literal("Update price")) {
                    val p = sellPrice.toIntOrNull()?.coerceAtLeast(1) ?: return@builder
                    act("reprice", d.item, p.toString())
                    closeDetail()
                }.bounds(contentRight - 110, actionY, 110, 16).build())
            }
        }
    }

    private fun listAuctionWidgets(d: Detail.ListAuction) {
        val startY = contentTop + 40
        val start = EditBox(font, contentLeft, startY, 80, 16, Component.literal("start price"))
        start.value = auctionStart
        start.setFilter { it.all(Char::isDigit) }
        start.setResponder { auctionStart = it }
        addRenderableWidget(start)

        val buyNow = EditBox(font, contentLeft + 90, startY, 80, 16, Component.literal("buy now (optional)"))
        buyNow.value = auctionBuyNow
        buyNow.setFilter { it.all(Char::isDigit) }
        buyNow.setResponder { auctionBuyNow = it }
        addRenderableWidget(buyNow)

        val actionY = contentBottom - 18
        addRenderableWidget(Button.builder(Component.literal("Back")) { closeDetail() }.bounds(contentLeft, actionY, 60, 16).build())
        addRenderableWidget(Button.builder(Component.literal("List for auction")) {
            val startPrice = auctionStart.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val buyNowPrice = auctionBuyNow.toIntOrNull()?.takeIf { it > startPrice }
            act("auction_list", d.slot.toString(), startPrice.toString(), (buyNowPrice ?: 0).toString())
            closeDetail()
        }.bounds(contentRight - 130, actionY, 130, 16).build())
    }

    private fun ohlc(): List<Ohlc> = (snap.detail?.history ?: emptyList()).map { Ohlc(it.open.toDouble(), it.high.toDouble(), it.low.toDouble(), it.close.toDouble(), it.volume.toDouble(), it.at) }

    private fun drawDetail(g: GuiGraphics, d: Detail, mx: Int, my: Int) {
        val stack = if (d is Detail.ListAuction) d.stack else stackFor(d.item)
        g.renderItem(stack, contentLeft, iconY)
        g.drawString(font, stack.hoverName, contentLeft + 22, iconY + 4, Ui.TEXT, false)

        if (d is Detail.ListAuction) {
            g.drawString(font, "This item can only be traded through the auction house.", contentLeft, iconY + 18, Ui.DIM, false)
            g.drawString(font, "Starting price", contentLeft, iconY + 30, Ui.DIM, false)
            g.drawString(font, "Buy now (optional)", contentLeft + 90, iconY + 30, Ui.DIM, false)
            g.drawString(font, "Auction fee on sale: ${snap.auctionFeePct}%", contentLeft, iconY + 58, Ui.WARN, false)
            return
        }
        d as Detail.Item

        val price = snap.detail?.price ?: 0
        CoinIcon.render(g, contentLeft, priceLineY)
        g.drawString(font, "${Ui.fmt(price)} / unit", contentLeft + 18, priceLineY + 4, Ui.ACCENT, false)
        val infiniteSupply = snap.detail?.infinite == true
        val stockX = contentLeft + 18 + font.width("${Ui.fmt(price)} / unit") + 10
        val stockText = if (infiniteSupply) "∞ unlimited" else "${Ui.fmt(snap.detail?.available ?: 0)} left"
        g.drawString(font, stockText, stockX, priceLineY + 4, if (infiniteSupply) Ui.GOOD else Ui.DIM, false)

        val data = ohlc()
        PriceChart.draw(g, contentLeft, chartY, contentRight - contentLeft, chartH, data, chartStyle, mx, my)

        if (d.mode != Mode.EDIT) g.drawString(font, "Quantity: ${Ui.fmt(qty)}", contentLeft, qtyTextY, Ui.TEXT, false)

        val previewY = (if (d.mode == Mode.SELL) priceFieldY + 24 else stepY + 24)
        when (d.mode) {
            Mode.BUY -> {
                val q = snap.quote?.takeIf { it.item == d.item && it.qty == qty }
                if (q == null) {
                    g.drawString(font, "Calculating...", contentLeft, previewY, Ui.DIM, false)
                } else {
                    if (q.filled < qty) g.drawString(font, "Only ${Ui.fmt(q.filled)} available to buy right now.", contentLeft, previewY, Ui.WARN, false)
                    val totalColor = if (q.total <= snap.funds) Ui.GOOD else Ui.BAD
                    CoinIcon.render(g, contentLeft, previewY + 14)
                    g.drawString(font, "Total: ${Ui.fmt(q.total)}  (avg ${Ui.fmt(q.avg)}/unit)", contentLeft + 18, previewY + 18, totalColor, false)
                    CoinIcon.render(g, contentLeft, previewY + 32)
                    g.drawString(font, "Your funds: ${Ui.fmt(snap.funds.toInt())}", contentLeft + 18, previewY + 36, Ui.DIM, false)
                }
            }
            Mode.SELL -> {
                val instantSell = snap.detail?.instantSell == true
                val locked = instantSell || (snap.detail?.myOrderAmount ?: 0) > 0
                val unit = if (locked) (snap.detail?.let { if (instantSell) it.price else it.myOrderPrice } ?: 1) else sellPrice.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val gross = unit * qty
                val tax = gross * snap.taxPct / 100
                val net = gross - tax
                val label = when {
                    instantSell -> "Instant system price per unit: $unit"
                    locked -> "Adding to your existing listing at: $unit"
                    else -> "Price per unit: $unit"
                }
                g.drawString(font, label, contentLeft, previewY - 20, if (locked) Ui.GOOD else Ui.TEXT, false)
                CoinIcon.render(g, contentLeft, previewY)
                g.drawString(font, "Gross: ${Ui.fmt(gross)}", contentLeft + 18, previewY + 4, Ui.TEXT, false)
                g.drawString(font, "Tax (${snap.taxPct}%): -${Ui.fmt(tax)}", contentLeft, previewY + 16, Ui.BAD, false)
                CoinIcon.render(g, contentLeft, previewY + 30)
                g.drawString(font, "You receive: ${Ui.fmt(net)}", contentLeft + 18, previewY + 34, Ui.GOOD, false)
            }
            Mode.EDIT -> {
                val amount = snap.detail?.myOrderAmount ?: 0
                val current = snap.detail?.myOrderPrice ?: 0
                g.drawString(font, "You have $amount listed at $current spurs each.", contentLeft, qtyTextY, Ui.TEXT, false)
                g.drawString(font, "New price per unit:", contentLeft, previewY - 4, Ui.DIM, false)
            }
        }
    }

    override fun removed() {
        act("close")
        super.removed()
    }

    override fun render(g: GuiGraphics, mx: Int, my: Int, pt: Float) {
        renderBackground(g, mx, my, pt)
        Ui.panel(g, left, top, pw, ph)
        g.fill(left, top, left + pw, top + 30, Ui.HEADER)
        g.drawString(font, if (detail != null) "Market — Item Detail" else "Market", left + 8, top + 30 - 12, Ui.TEXT, false)

        val d = detail
        if (d != null) {
            drawDetail(g, d, mx, my)
        } else {
            if (tab == Tab.ORDERS && snap.orders.isEmpty()) g.drawCenteredString(font, "You have no open listings.", left + pw / 2, contentTop + 20, Ui.DIM)
            if (tab == Tab.BROWSE && snap.rows.isEmpty()) g.drawCenteredString(font, "No items match your search.", left + pw / 2, contentTop + 44, Ui.DIM)
            if (tab == Tab.BROWSE) g.drawCenteredString(font, pageLabel, left + pw / 2, contentBottom + 8, Ui.DIM)
            if (tab == Tab.AUCTIONS && snap.auctions.isEmpty()) g.drawCenteredString(font, "No open auctions.", left + pw / 2, contentTop + 20, Ui.DIM)
            if (tab == Tab.SELL && sellEntries().isEmpty()) g.drawCenteredString(font, "Your inventory is empty.", left + pw / 2, contentTop + 20, Ui.DIM)
        }

        super.render(g, mx, my, pt)

        val footerY = top + ph - 20
        g.fill(left, footerY, left + pw, top + ph, Ui.HEADER)
        CoinIcon.render(g, left + 8, footerY + 2)
        g.drawString(font, Ui.fmt(snap.funds.toInt()), left + 8 + CoinIcon.SIZE + 3, footerY + 5, Ui.ACCENT, false)

        if (snap.msg.isNotEmpty() && System.currentTimeMillis() < messageUntil) {
            drawToast(g, footerY, snap.msg, if (snap.ok) Ui.GOOD else Ui.BAD)
        }
    }

    private fun drawToast(g: GuiGraphics, footerY: Int, text: String, color: Int) {
        val w = font.width(text) + 12
        val x = left + pw - w - 6
        g.fill(x, footerY + 2, x + w, footerY + 16, Ui.PANEL)
        g.drawString(font, text, x + 6, footerY + 5, color, false)
    }
}
