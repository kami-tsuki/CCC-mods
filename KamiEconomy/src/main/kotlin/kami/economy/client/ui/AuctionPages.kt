package kami.economy.client.ui

import kami.economy.economy.Blacklist
import kami.economy.economy.StackCodec
import kami.economy.net.AuctionLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Page
import kami.libs.ui.app.ReceiptKind
import kami.libs.ui.app.ReceiptLine
import kami.libs.ui.app.confirmDialog
import kami.libs.ui.app.receiptDialog
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack

private const val AUCTION_PANEL_W = 150
private const val SCOPE_W = 150
private const val SOON_MS = 300_000L

internal class AuctionsPage(app: MarketApp) : ListPage(app, "auctions") {
    private var mine = false
    private val table = TableState<AuctionLine>()
    private val stacks = HashMap<Long, ItemStack>()
    private val bid = NumberState(1)
    private var bidFor = -1L
    override val title get() = tr("kami_libs.common.auctions")
    override val help get() = helpFor("auctions:table", "kami_economy.help.auctions")

    private fun stack(a: AuctionLine) = stacks.getOrPut(a.id) {
        Minecraft.getInstance().level?.registryAccess()?.let { runCatching { StackCodec.decode(a.stackData, it) }.getOrNull() } ?: ItemStack.EMPTY
    }

    private fun minBid(a: AuctionLine) = maxOf(a.startPrice, a.currentBid + 1).toLong()

    private fun status(a: AuctionLine) = when {
        a.mine -> "yours"
        a.currentBidder == Minecraft.getInstance().player?.stringUUID -> "high"
        else -> ""
    }

    private val columns = listOf(
        Column<AuctionLine>(tr("kami_libs.common.item"), -1, sort = compareBy { it.label.lowercase() }) { _, c, a ->
            if (itemCell(c, stack(a), a.label, "auction:${a.id}", if (a.mine) Palette.brass else Palette.text)) table.selected.apply { clear(); add(a.id) }
        },
        Column.text<AuctionLine>(tr("kami_economy.col.seller"), 80, color = { Palette.textSecondary }) { it.sellerName },
        Column.number<AuctionLine>(tr("kami_economy.col.bid"), 70, format = { Format.money(it) }) { maxOf(it.currentBid, it.startPrice).toLong() },
        Column.text<AuctionLine>(tr("kami_economy.market.auctions.col.buy_now"), 70, Align.RIGHT) { if (it.buyNowPrice > 0) Format.money(it.buyNowPrice.toLong()) else "–" },
        Column.number<AuctionLine>(tr("kami_economy.market.auctions.col.ends"), 70,
            color = { if (it.expiresAt - System.currentTimeMillis() < SOON_MS) Palette.danger else Palette.text },
            format = { Format.duration(it - System.currentTimeMillis()) }) { it.expiresAt },
        Column.text<AuctionLine>(tr("kami_libs.common.status"), 90, color = { Palette.success }) {
            status(it).let { s -> if (s.isEmpty()) "" else tr("kami_economy.auction.status.$s") }
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        val bar = r.top(SMALL_H)
        searchBar(ui, bar.dropRight(SCOPE_W, 6))
        ui.segmented(bar.right(SCOPE_W), listOf(Option(false, tr("kami_libs.common.all")), Option(true, tr("kami_economy.scope.mine"))), mine, key = "scope")?.let { mine = it; table.clear() }
        val rows = if (ready) app.snap.auctions.filter { !mine || it.mine } else emptyList()
        val area = body(ui, r.dropTop(SMALL_H, 6))
        val selected = rows.firstOrNull { it.id in table.selected }
        val tableArea = if (selected != null) area.dropRight(AUCTION_PANEL_W, 6) else area
        ui.anchor("auctions:table", tableArea)
        ui.table(tableArea, columns, rows, table, { it.id }, rowHeight = ROW_H, emptyText = tr("kami_economy.market.auctions.empty"))
        selected?.let { panel(ui, area.right(AUCTION_PANEL_W), it) }
    }

    private fun panel(ui: Ui, r: Rect, a: AuctionLine) {
        val flow = ui.sidePanel(r)
        val head = flow.take(22)
        ui.itemSlot(head.left(22), stack(a), key = "auction-panel-item")
        Draw.text(ui.g, Draw.fit(a.label, head.w - 28), head.x + 26, head.y + 7, TextStyle.HEADING)
        ui.property(flow.take(12), tr("kami_economy.col.seller"), a.sellerName)
        ui.property(flow.take(12), tr("kami_economy.col.bid"), Format.money(maxOf(a.currentBid, a.startPrice).toLong()))
        ui.property(flow.take(12), tr("kami_economy.market.auctions.col.ends"), Format.duration(a.expiresAt - System.currentTimeMillis()))
        if (a.mine) {
            if (ui.button(flow.take(SMALL_H), tr("kami_economy.market.auction.cancel"), Icons.CROSS, style = ButtonStyle.DANGER,
                    enabled = a.currentBidder.isEmpty(), disabledReason = tr("kami_economy.market.auction.cancel.bid"), key = "auction-cancel"))
                confirmDialog(app::open, tr("kami_economy.auction.cancel.title"), a.label, Icons.CROSS, listOf(Consequence(tr("kami_economy.auction.cancel.body"))),
                    tr("kami_economy.cancel.confirm"), danger = true) { app.request("auction_cancel", a.id.toString()); table.clear() }
            return
        }
        val min = minBid(a)
        if (bidFor != a.id) { bidFor = a.id; bid.commit(min) }
        val reason = tr("kami_libs.lock.no_country")
        ui.fieldLabel(flow.take(9), tr("kami_economy.auction.your_bid"))
        ui.numberField(flow.take(CONTROL_H), bid, min, MAX_PRICE, key = "bid")
        if (ui.button(flow.take(SMALL_H), tr("kami_economy.market.auction.bid"), style = ButtonStyle.PRIMARY, enabled = app.snap.citizen, disabledReason = reason, key = "bid-go")) confirm(a, bid.value.toInt(), false)
        if (a.buyNowPrice > 0 && ui.button(flow.take(SMALL_H), tr("kami_economy.market.auction.buy_now", Format.money(a.buyNowPrice.toLong())), enabled = app.snap.citizen, disabledReason = reason, key = "buy-go")) confirm(a, a.buyNowPrice, true)
    }

    private fun confirm(a: AuctionLine, amount: Int, buyNow: Boolean) {
        val funds = app.snap.funds
        val lines = buildList {
            add(ReceiptLine(tr("kami_libs.common.item"), a.label))
            add(ReceiptLine(tr(if (buyNow) "kami_economy.auction.buy_price" else "kami_economy.auction.your_bid"), Format.money(amount.toLong())))
            add(ReceiptLine(tr("kami_economy.receipt.pay"), Format.money(amount.toLong()), ReceiptKind.TOTAL))
            if (!buyNow) add(ReceiptLine("", tr("kami_economy.auction.refund"), ReceiptKind.NOTE))
            add(ReceiptLine(tr("kami_economy.receipt.balance"), Format.money(funds) + " → " + Format.money(funds - amount)))
        }
        receiptDialog(
            app::open, tr(if (buyNow) "kami_economy.auction.receipt.buy" else "kami_economy.auction.receipt.bid"), Icons.SCALES, lines,
            tr(if (buyNow) "kami_economy.auction.buy" else "kami_economy.market.auction.bid"),
            warning = tr("kami_economy.market.buy.funds").takeIf { funds < amount },
            stale = { if (app.snap.auctions.any { it.id == a.id && it.currentBid == a.currentBid }) null else tr("kami_economy.receipt.stale") }
        ) {
            if (buyNow) {
                app.request("auction_buy", a.id.toString())
                table.clear()
            } else {
                app.request("auction_bid", a.id.toString(), amount.toString())
            }
        }
    }
}

internal class AuctionCreatePage(val app: MarketApp) : Page() {
    private val table = TableState<Stored>()
    private val start = NumberState(1)
    private val buyNow = NumberState(0)
    private var chosen = -1
    override val title get() = tr("kami_economy.nav.create")
    override val help get() = listOf(Callout("create:table", tr("kami_economy.help.create"), tr("kami_economy.help.create.desc")))

    private val columns = listOf(
        Column<Stored>(tr("kami_libs.common.item"), -1, sort = compareBy { it.stack.hoverName.string.lowercase() }) { _, c, e ->
            if (itemCell(c, e.stack, e.stack.hoverName.string, "create:${e.slot}")) table.selected.apply { clear(); add(e.slot) }
        },
        Column.number<Stored>(tr("kami_libs.common.amount"), 60) { it.stack.count.toLong() }
    )

    override fun draw(ui: Ui, r: Rect) {
        val rows = app.carried.get().auctionOnly
        val selected = rows.firstOrNull { it.slot in table.selected }
        if ((selected?.slot ?: -1) != chosen) { chosen = selected?.slot ?: -1; start.commit(1); buyNow.commit(0) }
        Draw.text(ui.g, Draw.fit(tr("kami_economy.market.auction_only"), r.w), r.x, r.y, Palette.textSecondary)
        val area = r.dropTop(16)
        val tableArea = if (selected != null) area.dropRight(AUCTION_PANEL_W + 20, 6) else area
        ui.anchor("create:table", tableArea)
        ui.table(tableArea, columns, rows, table, { it.slot }, rowHeight = ROW_H, emptyText = tr("kami_economy.create.empty"))
        selected?.let { form(ui, area.right(AUCTION_PANEL_W + 20), it) }
    }

    private fun form(ui: Ui, r: Rect, e: Stored) {
        val flow = ui.sidePanel(r)
        val head = flow.take(22)
        ui.itemSlot(head.left(22), e.stack, key = "create-item")
        Draw.text(ui.g, Draw.fit(e.stack.hoverName.string, head.w - 28), head.x + 26, head.y + 7, TextStyle.HEADING)
        ui.fieldLabel(flow.take(9), tr("kami_economy.market.auction.start_price"))
        ui.numberField(flow.take(CONTROL_H), start, 1, MAX_PRICE, key = "start")
        ui.fieldLabel(flow.take(9), tr("kami_economy.market.auction.buy_now_optional"))
        ui.numberField(flow.take(CONTROL_H), buyNow, 0, MAX_PRICE, key = "buy-now")
        val fee = tr("kami_economy.market.auction.fee", app.snap.auctionFeePct)
        val note = flow.take(Draw.paragraphHeight(fee, r.w - 16))
        Draw.paragraph(ui.g, fee, note.x, note.y, note.w, Palette.warning)
        val invalid = buyNow.value in 1..start.value
        val label = tr("kami_economy.market.auction.list")
        if (ui.lockedButton(flow.take(SMALL_H), label, app.slotLock(app.snap.auctionSlots), style = ButtonStyle.PRIMARY, enabled = app.snap.citizen && !invalid,
                disabledReason = if (!app.snap.citizen) tr("kami_libs.lock.no_country") else tr("kami_economy.market.auction.buy_now.invalid"), key = "create-go")) confirm(e)
    }

    private fun confirm(e: Stored) {
        val startPrice = start.value
        val buy = buyNow.value.takeIf { it > startPrice } ?: 0L
        val lines = buildList {
            add(ReceiptLine(e.stack.hoverName.string, unitsText(e.stack.count, e.stack.maxStackSize)))
            add(ReceiptLine(tr("kami_economy.market.auction.start_price"), Format.money(startPrice)))
            if (buy > 0) add(ReceiptLine(tr("kami_economy.auction.buy_price"), Format.money(buy)))
            add(ReceiptLine("", tr("kami_economy.market.auction.fee", app.snap.auctionFeePct), ReceiptKind.NOTE))
        }
        receiptDialog(app::open, tr("kami_economy.auction.receipt.list"), Icons.SCALES, lines, tr("kami_economy.market.auction.list")) {
            app.request("auction_list", Blacklist.itemId(e.stack), e.stack.count.toString(), startPrice.toString(), buy.toString())
        }
    }
}
