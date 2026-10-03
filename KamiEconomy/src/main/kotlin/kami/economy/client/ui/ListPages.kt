package kami.economy.client.ui

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
private const val ORDER_PANEL_W = 130
private const val FILTER_W = 260

private fun demandColumn(app: MarketApp) = Column<Row>(tr("kami_economy.col.demand"), 56, sort = compareBy { if (it.infinite) Int.MAX_VALUE else it.demand }) { _, c, row ->
    val y = c.y + (c.h - 8) / 2
    if (!row.infinite) {
        Draw.textRight(g, if (row.demand > 0) Format.number(row.demand) else "–", c.right, y, Palette.textSecondary)
        return@Column
    }
    val text = "∞ " + Format.compact(row.left.toLong())
    Draw.textRight(g, text, c.right, y, if (row.left > 0) Palette.success else Palette.textMuted)
    Draw.leadIcon(g, Icons.INFO, c.right - Draw.width(text) - 12, c.centerY, Palette.textMuted)
    tooltip("left:${row.item}", c, Tip.text(tr("kami_economy.tip.left", unitsText(row.left, app.stack(row.item).maxStackSize)), tr("kami_economy.nav.infinite")))
}

internal abstract class ListPage(val app: MarketApp, private val view: String) : Page() {
    protected val search = TextState()
    protected open val remoteSearch = true
    protected val ready get() = app.snap.view == view
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
        if (ui.searchField(r, search) && remoteSearch) sendAt = now + SEARCH_DELAY
        if (sendAt > 0 && now >= sendAt) { sendAt = 0; app.view(view, search.text) }
    }

    protected fun body(ui: Ui, area: Rect): Rect {
        if (!app.snap.truncated) return area
        Draw.text(ui.g, Draw.fit(tr("kami_economy.list.truncated"), area.w), area.x, area.bottom - 8, Palette.textMuted)
        return area.dropBottom(14)
    }

    protected fun helpFor(anchor: String, key: String) = listOf(Callout(anchor, tr(key), tr("$key.desc")))
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
        demandColumn(app),
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

    private fun keep(f: Filter, row: Row) = when (f) {
        Filter.ALL -> true
        Filter.STARTER -> row.starter
        Filter.SELLERS -> row.available > 0
        Filter.BUYERS -> row.demand > 0 || (row.infinite && row.left > 0)
        Filter.HELD -> row.item in held
    }

    override fun draw(ui: Ui, r: Rect) {
        held = app.carried.get().held
        val bar = r.top(SMALL_H)
        val chips = bar.right(FILTER_W)
        searchBar(ui, bar.dropRight(FILTER_W, 6))
        ui.anchor("market:filters", chips)
        val all = if (ready) app.snap.rows else emptyList()
        ui.segmented(chips, Filter.entries.map { f -> Option(f, "${tr("kami_economy.market.filter.${f.name.lowercase()}")} ${all.count { keep(f, it) }}") }, filter, key = "filter")?.let { filter = it }
        val area = body(ui, r.dropTop(SMALL_H, 6))
        ui.anchor("market:table", area)
        ui.table(area, columns, all.filter { keep(filter, it) }, table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_libs.common.nothing_found"), key = "market:$filter").opened?.let { app.openItem(it.item) }
    }
}

internal class InstantPage(val app: MarketApp) : Page() {
    private class Pending(val item: String, val qty: Int, val lot: Int)

    private val table = TableState<HeldRow>()
    private var pending: Pending? = null
    override val title get() = tr("kami_economy.nav.inventory")
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

    override fun draw(ui: Ui, r: Rect) {
        pending?.let { p ->
            app.snap.quote?.takeIf { it.mode == "sell_market" && it.item == p.item && it.qty == p.qty }?.let { q ->
                pending = null
                confirmQuote(app, q, p.lot)
            }
        }
        val extra = app.carried.get().auctionOnly.size
        val footer = if (extra > 0) 14 else 0
        val area = r.dropBottom(footer)
        ui.anchor("instant:table", area)
        ui.table(area, columns, if (app.snap.view == "instant") app.snap.held else emptyList(), table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_economy.instant.empty")).opened?.let { app.openItem(it.item, "sell", "instant") }
        if (extra > 0) {
            val text = tr("kami_economy.instant.auction_only", extra)
            Draw.text(ui.g, text, r.x, r.bottom - 8, Palette.textMuted)
            if (ui.link(r.x + Draw.width(text) + 6, r.bottom - 8, tr("kami_economy.instant.auction_link"))) app.navigate(Route("auction_create"))
        }
    }
}

internal class OrdersPage(app: MarketApp, private val bid: Boolean?) : ListPage(app, when (bid) { null -> "my_orders"; true -> "buy_orders"; false -> "sell_orders" }) {
    private val table = TableState<OrderRow>()
    override val remoteSearch get() = bid != null
    override val title get() = tr(if (bid == null) "kami_economy.nav.my_orders" else "kami_economy.nav.orders")
    override val help get() = helpFor("orders:table", "kami_economy.help.orders")

    private fun side(o: OrderRow) = if (o.bid == o.mine) "buy" else "sell"

    private fun open(o: OrderRow) = app.openItem(o.item, side(o), if (o.mine) "order" else "instant")

    private val columns = buildList {
        add(Column<OrderRow>(tr("kami_libs.common.item"), -1, sort = compareBy { app.stack(it.item).hoverName.string.lowercase() }) { _, c, o ->
            if (itemCell(c, app.stack(o.item), app.stack(o.item).hoverName.string, "order:${o.id}", if (o.mine) Palette.brass else Palette.text)) open(o)
        })
        if (bid == null) add(Column.text<OrderRow>(tr("kami_economy.col.side"), 44, color = { if (it.bid) Palette.success else Palette.danger }) { tr(if (it.bid) "kami_economy.side.buy" else "kami_economy.side.sell") })
        else {
            add(Column.text<OrderRow>(tr("kami_economy.col.owner"), 80, color = { if (it.mine) Palette.brass else Palette.textSecondary }) { it.ownerName })
            add(Column<OrderRow>(tr("kami_libs.common.country"), 90, sort = compareBy { it.country.lowercase() }) { _, c, o ->
                val y = c.y + (c.h - 8) / 2
                flag(Rect(c.x, y, 11, 8), o.color, o.flag)
                Draw.text(g, Draw.fit(o.country, c.w - 14), c.x + 14, y, if (o.relation == "embargo") Palette.danger else Palette.textSecondary)
                if (o.country.isNotEmpty()) tooltip("order-country:${o.id}", c, app.countryTip(o.country, o.relation))
            })
        }
        add(Column.number<OrderRow>(tr("kami_economy.col.price"), 64, tip = tr("kami_economy.col.per_lot"), format = { Format.money(it) }) { it.price.toLong() })
        add(unitsColumn<OrderRow>(tr("kami_libs.common.amount"), 90, { app.stack(it.item).maxStackSize }) { it.amount })
        add(Column.number<OrderRow>(tr("kami_economy.col.age"), 50, format = { Format.ago(it) }) { it.placedAt })
        add(Column.text<OrderRow>(tr("kami_libs.common.status"), 64, color = { if (it.best) Palette.success else Palette.warning }) {
            tr(if (it.best) "kami_economy.status.best" else if (it.mine) "kami_economy.status.undercut" else "kami_economy.status.none")
        })
        if (bid == true) add(Column<OrderRow>("", 40) { _, c, o ->
            if (!o.mine && button(Rect(c.x, c.y + 1, c.w, c.h - 2), tr("kami_economy.orders.fill"), key = "fill:${o.id}")) open(o)
        })
    }

    override fun draw(ui: Ui, r: Rect) {
        searchBar(ui, r.top(SMALL_H))
        val rows = if (bid == null) app.snap.mine.filter(::matches) else if (ready) app.snap.orders else emptyList()
        val area = body(ui, r.dropTop(SMALL_H, 6))
        val selected = rows.firstOrNull { it.mine && it.id in table.selected }
        val tableArea = if (selected != null) area.dropRight(ORDER_PANEL_W, 6) else area
        ui.anchor("orders:table", tableArea)
        ui.table(tableArea, columns, rows, table, { it.id }, rowHeight = ROW_H, severity = { if (it.mine && bid != null) Severity.INFO else null }, emptyText = tr("kami_economy.orders.empty")).opened?.let(::open)
        selected?.let { panel(ui, area.right(ORDER_PANEL_W), it) }
    }

    private fun matches(o: OrderRow) = search.text.isEmpty() || app.stack(o.item).hoverName.string.contains(search.text, ignoreCase = true)

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

internal class VendorsPage(app: MarketApp, private val sells: Boolean) : ListPage(app, if (sells) "buy_vendors" else "sell_vendors") {
    private val table = TableState<VendorLine>()
    override val title get() = tr("kami_economy.nav.vendors")
    override val help get() = helpFor("vendors:table", "kami_economy.help.vendors")

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
        ui.table(area, columns, if (ready) app.snap.vendors else emptyList(), table, { "${it.item}|${it.dim}|${it.x},${it.y},${it.z}" }, rowHeight = ROW_H,
            severity = { if (it.embargo) Severity.DANGER else null }, emptyText = tr("kami_economy.vendors.empty")).opened?.let { app.openItem(it.item) }
    }
}

internal class InfinitePage(app: MarketApp) : ListPage(app, "infinite") {
    private val table = TableState<Row>()
    override val title get() = tr("kami_economy.nav.infinite")
    override val help get() = helpFor("infinite:table", "kami_economy.help.infinite")

    private val columns = listOf(
        Column<Row>(tr("kami_libs.common.item"), -1, sort = compareBy { app.stack(it.item).hoverName.string.lowercase() }) { _, c, row ->
            if (itemCell(c, app.stack(row.item), app.stack(row.item).hoverName.string, "inf:${row.item}")) app.openItem(row.item, "sell", "instant")
        },
        Column.number<Row>(tr("kami_economy.col.sell"), 66, tip = tr("kami_economy.col.per_lot"), format = ::priceText) { it.sell.toLong() },
        unitsColumn<Row>(tr("kami_economy.col.left"), 90, { app.stack(it.item).maxStackSize }) { it.left },
        Column<Row>("", 54) { _, c, row ->
            if (button(Rect(c.x, c.y + 1, c.w, c.h - 2), tr("kami_economy.market.sell_now"), enabled = row.sell > 0, key = "inf-sell:${row.item}")) app.openItem(row.item, "sell", "instant")
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        searchBar(ui, r.top(SMALL_H))
        val area = body(ui, r.dropTop(SMALL_H, 6))
        ui.anchor("infinite:table", area)
        ui.table(area, columns, if (ready) app.snap.rows.filter { it.infinite } else emptyList(), table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_libs.common.nothing_found")).opened?.let { app.openItem(it.item, "sell", "instant") }
    }
}
