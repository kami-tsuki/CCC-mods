package kami.economy.client.ui

import kami.economy.economy.Blacklist
import kami.economy.economy.Classification
import kami.economy.net.HeldRow
import kami.economy.net.OrderRow
import kami.economy.net.Row
import kami.economy.net.VendorLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3

private const val SEARCH_DELAY = 250L
private const val PANEL_W = 130

internal abstract class ListPage(val app: MarketApp, private val view: String) : Page() {
    protected val search = TextState()
    private var sendAt = 0L
    private var focusSearch = false

    override fun opened(route: Route) {
        search.set("")
        focusSearch = route.param("search") != null
        app.view(view)
    }

    protected fun searchBar(ui: Ui, r: Rect) {
        if (focusSearch) { ui.focus = ui.id("search"); focusSearch = false }
        val now = System.currentTimeMillis()
        if (ui.searchField(r, search)) sendAt = now + SEARCH_DELAY
        if (sendAt > 0 && now >= sendAt) { sendAt = 0; app.view(view, search.text) }
    }

    protected fun body(ui: Ui, area: Rect): Rect {
        if (!app.snap.truncated) return area
        Draw.text(ui.g, Draw.fit(tr("kami_economy.list.truncated"), area.w), area.x, area.bottom - 8, Palette.textMuted)
        return area.dropBottom(14)
    }

    protected fun help(anchor: String, key: String) = listOf(Callout(anchor, tr(key), tr("$key.desc")))
}

internal class MarketPage(app: MarketApp) : ListPage(app, "market") {
    private enum class Filter { ALL, STARTER, SELLERS, BUYERS, HELD }

    private var filter = Filter.ALL
    private var held: Map<String, Int> = emptyMap()
    private val table = TableState<Row>()
    override val title get() = tr("kami_libs.common.market")
    override val help get() = listOf(
        Callout("market:filters", tr("kami_economy.help.market.filters"), tr("kami_economy.help.market.filters.desc")),
        Callout("market:table", tr("kami_economy.help.market.table"), tr("kami_economy.help.market.table.desc"))
    )

    private val columns = listOf(
        Column<Row>(tr("kami_libs.common.item"), -1, sort = compareBy { app.stack(it.item).hoverName.string.lowercase() }) { _, c, row ->
            if (itemCell(c, app.stack(row.item), app.stack(row.item).hoverName.string, "market:${row.item}")) app.openItem(row.item)
            tooltip("market-tip:${row.item}", c) { rowTip(row) }
        },
        Column.number<Row>(tr("kami_economy.col.lot"), 36) { it.lot.toLong() },
        Column.number<Row>(tr("kami_economy.col.buy"), 66, tip = tr("kami_economy.col.per_lot"), format = ::priceText) { it.buy.toLong() },
        Column.number<Row>(tr("kami_economy.col.sell"), 66, tip = tr("kami_economy.col.per_lot"), format = ::priceText) { it.sell.toLong() },
        Column.number<Row>(tr("kami_economy.col.available"), 52) { it.available.toLong() },
        Column.number<Row>(tr("kami_economy.col.demand"), 48) { it.demand.toLong() },
        Column.number<Row>(tr("kami_economy.col.change"), 52, tip = tr("kami_economy.col.change.tip"), color = { if (it.change > 0) Palette.success else if (it.change < 0) Palette.danger else Palette.textMuted }, format = ::permille) { it.change.toLong() },
        Column.number<Row>(tr("kami_economy.col.traded"), 56) { it.sold + it.bought },
        Column<Row>("", ROW_H) { _, c, row ->
            if (row.item in held && iconButton(Rect(c.x, c.y, c.h, c.h), Icons.COIN, tr("kami_economy.market.sell_now"), key = "sell:${row.item}")) app.openItem(row.item, "sell", "instant")
        }
    )

    private fun rowTip(row: Row) = Tip(app.stack(row.item).hoverName.string, listOfNotNull(
        row.last.takeIf { it > 0 }?.let { tr("kami_economy.tip.last", Format.money(it.toLong())) to Palette.textSecondary },
        tr("kami_economy.tip.volume", Format.number(row.volume)) to Palette.textSecondary,
        tr("kami_economy.tip.totals", Format.number(row.sold), Format.number(row.bought)) to Palette.textSecondary,
        tr("kami_economy.tip.lot", row.lot) to Palette.textSecondary,
        row.vendorPrice.takeIf { it > 0 }?.let { tr("kami_economy.market.vendors.price", Format.money(it.toLong())) to Palette.textSecondary },
        row.myAsk.takeIf { it > 0 }?.let { tr("kami_economy.tip.my_ask", Format.money(it.toLong())) to Palette.brass },
        row.myBid.takeIf { it > 0 }?.let { tr("kami_economy.tip.my_bid", Format.money(it.toLong())) to Palette.brass }
    ))

    private val inventory = Throttled {
        val map = HashMap<String, Int>()
        Minecraft.getInstance().player?.inventory?.items?.forEach {
            if (!it.isEmpty && Blacklist.classify(it) == Classification.ALLOWED) map.merge(Blacklist.itemId(it), it.count, Int::plus)
        }
        map
    }

    override fun draw(ui: Ui, r: Rect) {
        held = inventory.get()
        searchBar(ui, r.top(SMALL_H))
        val chips = r.dropTop(SMALL_H, 4).top(SMALL_H)
        ui.anchor("market:filters", chips)
        ui.segmented(chips, Filter.entries.map { Option(it, tr("kami_economy.market.filter.${it.name.lowercase()}")) }, filter, key = "filter")?.let { filter = it }
        val area = body(ui, r.dropTop(2 * SMALL_H + 10))
        ui.anchor("market:table", area)
        val rows = app.snap.rows.filter {
            when (filter) {
                Filter.ALL -> true
                Filter.STARTER -> it.starter
                Filter.SELLERS -> it.available > 0
                Filter.BUYERS -> it.demand > 0
                Filter.HELD -> it.item in held
            }
        }
        ui.table(area, columns, rows, table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_libs.common.nothing_found")).opened?.let { app.openItem(it.item) }
    }
}

internal class InstantPage(val app: MarketApp) : Page() {
    private class Pending(val item: String, val qty: Int, val lot: Int)

    private val table = TableState<HeldRow>()
    private var pending: Pending? = null
    override val title get() = tr("kami_economy.nav.instant")
    override val help get() = listOf(Callout("instant:table", tr("kami_economy.help.instant"), tr("kami_economy.help.instant.desc")))

    private val columns = listOf(
        Column<HeldRow>(tr("kami_libs.common.item"), -1, sort = compareBy { app.stack(it.item).hoverName.string.lowercase() }) { _, c, h ->
            if (itemCell(c, app.stack(h.item), app.stack(h.item).hoverName.string, "instant:${h.item}")) app.openItem(h.item, "sell", "instant")
        },
        unitsColumn<HeldRow>(tr("kami_economy.col.held"), 90, { app.stack(it.item).maxStackSize }) { it.held },
        Column.number<HeldRow>(tr("kami_economy.col.sell"), 66, tip = tr("kami_economy.col.per_lot"), format = ::priceText) { it.sell.toLong() },
        Column.number<HeldRow>(tr("kami_economy.col.receive"), 70, tip = tr("kami_economy.col.receive.tip"), color = { if (it.net > 0) Palette.success else Palette.textMuted }, format = ::priceText) { it.net },
        Column.text<HeldRow>(tr("kami_economy.col.source"), 80, color = { if (it.source == "none") Palette.danger else Palette.textSecondary }) { tr("kami_economy.source.${it.source}") },
        Column<HeldRow>("", 54) { _, c, h ->
            val reason = if (!app.snap.citizen) tr("kami_libs.lock.no_country") else tr("kami_economy.action.no_buyers").takeIf { h.net <= 0 }
            if (button(Rect(c.x, c.y + 1, c.w, c.h - 2), tr("kami_economy.instant.sell_all"), enabled = reason == null, disabledReason = reason, key = "all:${h.item}")) {
                pending = Pending(h.item, h.cap, h.lot)
                app.request("quote", "sell_market", h.item, h.cap.toString(), "0")
            }
        }
    )

    override fun opened(route: Route) {
        pending = null
        app.view("instant")
    }

    private val auctionOnly = Throttled { Minecraft.getInstance().player?.inventory?.items?.count { !it.isEmpty && Blacklist.classify(it) == Classification.AUCTION_ONLY } ?: 0 }

    override fun draw(ui: Ui, r: Rect) {
        pending?.let { p ->
            app.snap.quote?.takeIf { it.mode == "sell_market" && it.item == p.item && it.qty == p.qty }?.let { q ->
                pending = null
                confirmQuote(app, q, p.lot)
            }
        }
        val extra = auctionOnly.get()
        val footer = if (extra > 0) 14 else 0
        val area = r.dropBottom(footer)
        ui.anchor("instant:table", area)
        ui.table(area, columns, app.snap.held, table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_economy.instant.empty")).opened?.let { app.openItem(it.item, "sell", "instant") }
        if (extra > 0) {
            val text = tr("kami_economy.instant.auction_only", extra)
            Draw.text(ui.g, text, r.x, r.bottom - 8, Palette.textMuted)
            if (ui.link(r.x + Draw.width(text) + 6, r.bottom - 8, tr("kami_economy.instant.auction_link"))) app.navigate(Route("auction_create"))
        }
    }
}

internal class OrdersPage(app: MarketApp, private val bid: Boolean, private var mine: Boolean) : ListPage(app, if (bid) "buy_orders" else "sell_orders") {
    private val table = TableState<OrderRow>()
    override val title get() = tr(if (!bid) "kami_economy.nav.sell_orders" else if (mine) "kami_economy.nav.my_bids" else "kami_economy.nav.buy_orders")
    override val help get() = help("orders:table", "kami_economy.help.orders")

    private fun side(o: OrderRow) = if (o.bid == o.mine) "buy" else "sell"

    private fun open(o: OrderRow) = app.openItem(o.item, side(o), if (o.mine) "order" else "instant")

    private val columns = buildList {
        add(Column<OrderRow>(tr("kami_libs.common.item"), -1, sort = compareBy { app.stack(it.item).hoverName.string.lowercase() }) { _, c, o ->
            if (itemCell(c, app.stack(o.item), app.stack(o.item).hoverName.string, "order:${o.id}", if (o.mine) Palette.brass else Palette.text)) open(o)
        })
        add(Column.text<OrderRow>(tr("kami_economy.col.owner"), 80, color = { if (it.mine) Palette.brass else Palette.textSecondary }) { it.ownerName })
        add(Column<OrderRow>(tr("kami_libs.common.country"), 90, sort = compareBy { it.country.lowercase() }) { _, c, o ->
            val y = c.y + (c.h - 8) / 2
            flag(Rect(c.x, y, 11, 8), o.color, o.flag)
            Draw.text(g, Draw.fit(o.country, c.w - 14), c.x + 14, y, if (o.relation == "embargo") Palette.danger else Palette.textSecondary)
            if (o.country.isNotEmpty()) tooltip("order-country:${o.id}", c, app.countryTip(o.country, o.relation))
        })
        add(Column.number<OrderRow>(tr("kami_economy.col.price"), 64, tip = tr("kami_economy.col.per_lot"), format = { Format.money(it) }) { it.price.toLong() })
        add(unitsColumn<OrderRow>(tr("kami_libs.common.amount"), 90, { app.stack(it.item).maxStackSize }) { it.amount })
        add(Column.number<OrderRow>(tr("kami_economy.col.age"), 50, format = { Format.ago(it) }) { it.placedAt })
        add(Column.text<OrderRow>(tr("kami_libs.common.status"), 64, color = { if (it.best) Palette.success else Palette.warning }) {
            tr(if (it.best) "kami_economy.status.best" else if (it.mine) "kami_economy.status.undercut" else "kami_economy.status.none")
        })
        if (bid) add(Column<OrderRow>("", 40) { _, c, o ->
            if (!o.mine && button(Rect(c.x, c.y + 1, c.w, c.h - 2), tr("kami_economy.orders.fill"), key = "fill:${o.id}")) open(o)
        })
    }

    override fun draw(ui: Ui, r: Rect) {
        val bar = r.top(SMALL_H)
        searchBar(ui, bar.dropRight(150, 6))
        ui.segmented(bar.right(150), listOf(Option(false, tr("kami_libs.common.all")), Option(true, tr("kami_economy.scope.mine"))), mine, key = "scope")?.let { mine = it; table.clear() }
        val rows = (if (mine) app.snap.mine else app.snap.orders).filter { it.bid == bid }
        val area = body(ui, r.dropTop(SMALL_H, 6))
        val selected = rows.firstOrNull { it.mine && it.id in table.selected }
        val tableArea = if (selected != null) area.dropRight(PANEL_W, 6) else area
        ui.anchor("orders:table", tableArea)
        ui.table(tableArea, columns, rows, table, { it.id }, rowHeight = ROW_H, severity = { if (it.mine) Severity.INFO else null }, emptyText = tr("kami_economy.orders.empty")).opened?.let(::open)
        selected?.let { panel(ui, area.right(PANEL_W), it) }
    }

    private fun panel(ui: Ui, r: Rect, o: OrderRow) {
        val flow = ui.sidePanel(r)
        val head = flow.take(22)
        ui.itemSlot(head.left(22), app.stack(o.item), key = "orders-panel-item")
        Draw.text(ui.g, Draw.fit(app.stack(o.item).hoverName.string, head.w - 28), head.x + 26, head.y + 7, TextStyle.HEADING)
        ui.property(flow.take(12), tr("kami_economy.col.price"), Format.money(o.price.toLong()))
        ui.property(flow.take(12), tr("kami_libs.common.amount"), unitsText(o.amount, app.stack(o.item).maxStackSize))
        if (ui.button(flow.take(SMALL_H), tr("kami_economy.reprice.action"), Icons.EDIT, key = "panel-reprice")) app.reprice(o)
        if (ui.button(flow.take(SMALL_H), tr("kami_economy.cancel.action"), Icons.CROSS, style = ButtonStyle.DANGER, key = "panel-cancel")) app.cancel(o)
    }
}

internal class VendorsPage(app: MarketApp) : ListPage(app, "vendors") {
    private val table = TableState<VendorLine>()
    override val title get() = tr("kami_economy.nav.vendors")
    override val help get() = help("vendors:table", "kami_economy.help.vendors")

    private fun where(v: VendorLine): String {
        val player = Minecraft.getInstance().player ?: return v.dim
        val near = if (player.level().dimension().location().toString() == v.dim)
            tr("kami_libs.common.distance", Format.number(player.position().distanceTo(Vec3(v.x + 0.5, v.y + 0.5, v.z + 0.5)).toLong()))
        else tr("kami_economy.market.vendors.far")
        return "${v.dim} · $near"
    }

    private val columns = listOf(
        Column<VendorLine>(tr("kami_libs.common.item"), -1, sort = compareBy { app.stack(it.item).hoverName.string.lowercase() }) { _, c, v ->
            if (itemCell(c, app.stack(v.item), app.stack(v.item).hoverName.string, "vendor:${v.item}:${v.x},${v.y},${v.z}")) app.openItem(v.item)
        },
        Column.text<VendorLine>(tr("kami_economy.col.owner"), 80, color = { Palette.textSecondary }) { it.owner },
        Column<VendorLine>(tr("kami_libs.common.country"), 90, sort = compareBy { it.country.lowercase() }) { _, c, v ->
            val y = c.y + (c.h - 8) / 2
            flag(Rect(c.x, y, 11, 8), v.color, v.flag)
            Draw.text(g, Draw.fit(v.country, c.w - 14), c.x + 14, y, if (v.embargo) Palette.danger else Palette.textSecondary)
            if (v.embargo) tooltip("vendor-embargo:${v.x},${v.y},${v.z}", c, tr("kami_economy.market.embargo.vendor", v.country))
        },
        Column.number<VendorLine>(tr("kami_economy.col.price"), 64, tip = tr("kami_economy.col.per_lot"), format = { Format.money(it) }) { it.price.toLong() },
        Column.text<VendorLine>(tr("kami_economy.col.kind"), 48, color = { if (it.sell) Palette.danger else Palette.success }) { tr(if (it.sell) "kami_economy.market.vendors.sells" else "kami_economy.market.vendors.buys") },
        Column.number<VendorLine>(tr("kami_economy.col.stock"), 48, format = { if (it >= 0) Format.number(it) else "–" }) { it.stock.toLong() },
        Column<VendorLine>(tr("kami_economy.col.location"), 100) { _, c, v ->
            Draw.text(g, Draw.fit("${v.x}, ${v.y}, ${v.z}", c.w), c.x, c.y + (c.h - 8) / 2, Palette.textSecondary)
            tooltip("vendor-where:${v.x},${v.y},${v.z}", c) { Tip.text(where(v)) }
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        searchBar(ui, r.top(SMALL_H))
        val area = body(ui, r.dropTop(SMALL_H, 6))
        ui.anchor("vendors:table", area)
        ui.table(area, columns, app.snap.vendors, table, { "${it.item}|${it.dim}|${it.x},${it.y},${it.z}" }, rowHeight = ROW_H,
            severity = { if (it.embargo) Severity.DANGER else null }, emptyText = tr("kami_economy.vendors.empty")).opened?.let { app.openItem(it.item) }
    }
}
