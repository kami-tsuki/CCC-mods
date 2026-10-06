package kami.economy.client.ui

import kami.economy.client.ClientHooks
import kami.economy.client.MarketPrefs
import kami.economy.net.Net
import kami.economy.net.OrderRow
import kami.economy.net.Row
import kami.libs.economy.Movement
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavBadge
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Personal
import kami.libs.ui.app.PersonalSection
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.label
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft

private val market get() = MarketApp.instance

private fun connected() = Minecraft.getInstance().connection?.hasChannel(Net.act("open").type()) == true

private fun openMarket(item: String, side: String = "buy", kind: String = "instant") {
    if (!connected()) return
    market.openItem(item, side, kind)
    ClientHooks.request("open")
}

private fun Ui.waiting(r: Rect) {
    emptyState(r, tr("kami_economy.market.calculating"), "")
}

object PersonalPages {
    private const val orders = "economy.orders"
    private const val trades = "economy.trades"
    private const val watch = "economy.watch"

    fun register() = Personal.register(PersonalSection(
        "economy", 40, { "kami_economy.personal.group" },
        {
            if (!connected()) emptyList() else listOf(
                NavItem(orders, tr("kami_economy.nav.my_orders"), Icons.SCROLL, { market.takeIf { it.loaded }?.snap?.mine?.size?.takeIf { it > 0 }?.let { NavBadge(it, Severity.INFO) } }),
                NavItem(trades, tr("kami_economy.personal.trades"), Icons.LEDGER),
                NavItem(watch, tr("kami_economy.personal.watch"), Icons.STAR)
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
            else if (id == "" || id == orders) market.view("my_orders")
            else if (id == watch) market.view("watch", MarketPrefs.prefs.watch.joinToString(","))
        }
    ))
}

private class MyOrdersPage(private val host: KamiApp) : Page() {
    private val table = TableState<OrderRow>()
    override val title get() = tr("kami_economy.nav.my_orders")

    private val columns = listOf(
        Column<OrderRow>(tr("kami_libs.common.item"), -1, sort = compareBy { market.stack(it.item).hoverName.string.lowercase() }) { _, c, o ->
            if (itemCell(c, market.stack(o.item), market.stack(o.item).hoverName.string, "my-order:${o.id}")) openMarket(o.item, if (o.bid) "buy" else "sell", "order")
        },
        Column.text<OrderRow>(tr("kami_economy.col.side"), 44, color = { if (it.bid) Palette.success else Palette.danger }) { tr(if (it.bid) "kami_economy.side.buy" else "kami_economy.side.sell") },
        Column.number<OrderRow>(tr("kami_economy.col.price"), 64, tip = tr("kami_economy.col.per_lot"), format = { Format.money(it) }) { it.price.toLong() },
        unitsColumn<OrderRow>(tr("kami_libs.common.amount"), 90, { market.stack(it.item).maxStackSize }) { it.amount },
        Column.text<OrderRow>(tr("kami_libs.common.status"), 64, color = { if (it.best) Palette.success else Palette.warning }) { tr(if (it.best) "kami_economy.status.best" else "kami_economy.status.undercut") },
        Column<OrderRow>("", 2 * (ROW_H - 2) + 4) { _, c, o ->
            val size = c.h - 2
            if (iconButton(Rect(c.x, c.y + 1, size, size), Icons.EDIT, tr("kami_economy.reprice.action"), key = "my-edit:${o.id}")) market.reprice(host, o)
            if (iconButton(Rect(c.right - size, c.y + 1, size, size), Icons.CROSS, tr("kami_economy.cancel.action"), key = "my-cancel:${o.id}")) market.cancel(host, o)
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        if (!market.loaded) return ui.waiting(r)
        ui.table(r, columns, market.snap.mine, table, { it.id }, rowHeight = ROW_H, emptyText = tr("kami_economy.orders.empty"), key = "my-orders")
    }
}

private class TradesPage : Page() {
    private val table = TableState<Movement>()
    override val title get() = tr("kami_economy.personal.trades")

    private val columns = listOf(
        Column.number<Movement>(tr("kami_economy.col.age"), 50, format = { Format.ago(it) }) { it.at },
        Column<Movement>(tr("kami_libs.common.item"), -1, sort = compareBy { market.stack(it.item).hoverName.string.lowercase() }) { _, c, m ->
            if (itemCell(c, market.stack(m.item), market.stack(m.item).hoverName.string, "trade:${m.at}:${m.item}")) openMarket(m.item)
        },
        Column.text<Movement>(tr("kami_economy.personal.col.kind"), 70, color = { Palette.textSecondary }) { it.flow.label },
        Column.number<Movement>(tr("kami_economy.personal.col.qty"), 44) { it.qty.toLong() },
        Column.text<Movement>(tr("kami_economy.personal.col.party"), 80, color = { Palette.textSecondary }) { it.counterparty },
        Column.number<Movement>(tr("kami_libs.common.amount"), 70, color = { if (it.amount >= 0) Palette.success else Palette.danger }, format = { Format.signedMoney(it) }) { it.amount }
    )

    override fun draw(ui: Ui, r: Rect) {
        val rows = Personal.account?.movements.orEmpty().filter { it.item.isNotEmpty() }
        ui.table(r, columns, rows, table, { "${it.at}:${it.flow}:${it.amount}:${it.item}" }, rowHeight = ROW_H, emptyText = tr("kami_economy.personal.trades.empty"), key = "trades")
    }
}

private class WatchPage : Page() {
    private val table = TableState<Row>()
    override val title get() = tr("kami_economy.personal.watch")

    private val columns = listOf(
        Column<Row>(tr("kami_libs.common.item"), -1, sort = compareBy { market.stack(it.item).hoverName.string.lowercase() }) { _, c, row ->
            if (itemCell(c, market.stack(row.item), market.stack(row.item).hoverName.string, "watch:${row.item}")) openMarket(row.item)
        },
        Column.number<Row>(tr("kami_economy.personal.col.last"), 64, tip = tr("kami_economy.col.per_lot"), format = ::priceText) { it.last.toLong() },
        Column.number<Row>(tr("kami_economy.col.change"), 52, tip = tr("kami_economy.col.change.tip"), color = { if (it.change > 0) Palette.success else if (it.change < 0) Palette.danger else Palette.textMuted }, format = ::permille) { it.change.toLong() },
        Column<Row>("", ROW_H) { _, c, row ->
            if (iconButton(Rect(c.x, c.y + 1, c.h - 2, c.h - 2), Icons.CROSS, tr("kami_economy.watch.unpin"), key = "unpin:${row.item}")) {
                MarketPrefs.prefs.watch.remove(row.item)
                MarketPrefs.save()
            }
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        val watched = MarketPrefs.prefs.watch
        if (!market.loaded) return ui.waiting(r)
        val rows = if (market.snap.view == "watch") market.snap.rows.filter { it.item in watched } else emptyList()
        ui.table(r, columns, rows, table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_economy.personal.watch.empty"), key = "watch")
    }
}
