package kami.economy.client.ui

import kami.economy.client.admin.MarketSection
import kami.economy.net.Net
import kami.economy.net.OrderRow
import kami.libs.economy.Movement
import kami.libs.economy.Statement
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavBadge
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Personal
import kami.libs.ui.app.segmentWidth
import kami.libs.ui.app.PersonalSection
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.label
import kami.libs.ui.text.partyLabel
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft

private val market get() = MarketApp.instance

private fun connected() = Minecraft.getInstance().connection?.hasChannel(Net.act("open").type()) == true

private fun openMarket(item: String, side: String = "buy", kind: String = "instant") {
    if (!connected()) return
    market.openItem(item, side, kind)
    MarketApp.open()
}

private fun myOrders(): List<OrderRow>? {
    if (!market.loaded) return null
    val snap = market.snap
    if (!Personal.viewing) return snap.mine
    return snap.subjectOrders.takeIf { snap.subject == Personal.subjectArg }
}

object PersonalPages {
    private const val orders = "economy.orders"
    private const val trades = "economy.trades"
    private const val watch = "economy.watch"

    fun register() = Personal.register(PersonalSection(
        "economy", 40, { "kami_libs.common.market" },
        {
            if (!connected()) emptyList() else listOf(
                NavItem(orders, tr("kami_economy.nav.my_orders"), Icons.SCROLL, { myOrders()?.size?.takeIf { it > 0 }?.let { NavBadge(it, Severity.INFO) } }),
                NavItem(trades, tr("kami_economy.personal.trades"), Icons.LEDGER),
                NavItem(watch, tr("kami_economy.dash.watch"), Icons.STAR)
            )
        },
        { host, id ->
            when (id) {
                orders -> MyOrdersPage(host)
                trades -> TradesPage()
                watch -> WatchPage()
                else -> null
            }
        },
        { id ->
            if (!connected()) Unit
            else if (id == "" || id == orders) market.view("my_orders", subject = Personal.subjectArg)
            else if (id == watch) market.view("watch", subject = Personal.subjectArg)
        }
    ))
}

private class MyOrdersPage(private val host: KamiApp) : Page() {
    private val table = TableState<OrderRow>()
    override val title get() = tr("kami_economy.nav.my_orders")

    private val box = SearchBox(MarketSection::stack)

    private val columns by localized { orderColumns(market, side = true, owner = false, fill = false, open = { openMarket(it.item, if (it.bid) "buy" else "sell", "order") }) + listOf(
        Column<OrderRow>("", 2 * (ROW_H - 2) + 4) { _, c, o ->
            val size = c.h - 2
            if (!Personal.viewing) {
                if (iconButton(Rect(c.x, c.y + 1, size, size), Icons.EDIT, tr("kami_economy.reprice.action"), key = "my-edit:${o.id}")) market.reprice(host, o)
                if (iconButton(Rect(c.right - size, c.y + 1, size, size), Icons.CROSS, tr("kami_economy.cancel.action"), key = "my-cancel:${o.id}")) market.cancel(host, o)
            }
        }
    ) }

    override fun draw(ui: Ui, r: Rect) {
        ui.searchField(r.top(SMALL_H).left(SEARCH_MAX), box.text)
        val orders = myOrders()
        val rows = orders?.let { box.filter(ui, "my-orders:rows", it) { o -> box.hit(o.item) } }.orEmpty()
        ui.table(r.dropTop(SMALL_H, 6), columns, rows, table, { it.id }, rowHeight = ROW_H, emptyText = tr("kami_libs.table.empty"), key = "my-orders", loading = orders == null)
    }
}

private class TradesPage : Page() {
    private val table = TableState<Movement>().apply { sortColumn = 0; descending = true }
    private val box = SearchBox(MarketSection::stack)
    private var side = Side.ALL
    override val title get() = tr("kami_economy.personal.trades")

    private val columns by localized { listOf(
        Column.number<Movement>(tr("kami_economy.col.age"), 50, format = { Format.ago(it) }) { it.at },
        itemColumn<Movement>({ it.item }, { "trade:${it.at}:${it.item}" }) { openMarket(it.item) },
        Column.text<Movement>(tr("kami_libs.common.type"), 70, color = { Palette.textSecondary }) { it.flow.label },
        Column.number<Movement>(tr("kami_economy.personal.col.qty"), 44) { it.qty.toLong() },
        Column.text<Movement>(tr("kami_economy.personal.col.party"), 80, color = { Palette.textSecondary }) { partyLabel(it.counterparty).ifEmpty { "-" } },
        Column.number<Movement>(tr("kami_libs.common.amount"), 70, color = { if (it.amount >= 0) Palette.success else Palette.danger }, format = { Format.signedMoney(it) }) { it.amount }
    ) }

    override fun draw(ui: Ui, r: Rect) {
        val loading = Personal.loading
        val bar = r.top(SMALL_H)
        val options = Side.entries.map { Option(it, tr(it.label)) }
        val seg = bar.right(segmentWidth(options))
        ui.searchField(bar.dropRight(seg.w, BAR_GAP).left(SEARCH_MAX), box.text)
        ui.segmented(seg, options, side, key = "trade-side")?.let { side = it }
        val area = r.dropTop(SMALL_H, 6)
        val all = ui.filtered("trades:all", listOf(Personal.account?.movements)) { Personal.account?.movements.orEmpty().filter { it.item.isNotEmpty() && !it.item.startsWith(Statement.NOTE) } }
        val found = box.filter(ui, "trades:rows", all) { box.hit(it.item, partyLabel(it.counterparty)) }
        val rows = if (side == Side.ALL) found else ui.filtered("trades:side", listOf(side, found)) { found.filter { (it.amount < 0) == (side == Side.BUY) } }
        ui.table(area, columns, rows, table, { "${it.at}:${it.flow}:${it.amount}:${it.item}" }, rowHeight = ROW_H, emptyText = tr("kami_economy.personal.trades.empty"), key = "trades", loading = loading)
        ui.staleRows(area, loading && rows.isNotEmpty())
    }
}

private class WatchPage : Page() {
    private val list = WatchList(market, "watch", true, ::openMarket)
    override val title get() = tr("kami_economy.dash.watch")

    override fun draw(ui: Ui, r: Rect) {
        val snap = market.cached("watch")
        if (snap != null && snap.watch.isEmpty() && !market.loading("watch")) {
            if (ui.emptyState(r, tr("kami_economy.personal.watch.none"), tr("kami_economy.dash.watch.empty"), action = tr("kami_economy.personal.watch.browse"), actionIcon = Icons.COIN, key = "watch-empty") && connected())
                MarketApp.open()
            return
        }
        list.draw(ui, r, snap, market.loading("watch"))
    }
}
