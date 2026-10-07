package kami.economy.client.ui

import kami.economy.client.admin.MarketSection
import kami.economy.net.HeldRow
import kami.economy.net.OrderRow
import kami.economy.net.Row
import kami.economy.net.VendorLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.app.SELECT_W
import kami.libs.ui.app.segmentWidth
import kami.libs.ui.core.Dim
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.text.SearchQuery
import kami.libs.ui.text.ItemSearch
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

private const val SEARCH_DELAY = 250L
private const val ORDER_PANEL_W = 130
private const val SEARCH_MAX = 200
private const val SEARCH_MIN = 110
private const val BAR_GAP = 6

private fun demandColumn(app: MarketApp) = Column<Row>(tr("kami_economy.col.demand"), 56, sort = compareBy { if (it.infinite) Int.MAX_VALUE else it.demand }) { _, c, row ->
    val y = c.y + (c.h - 8) / 2
    if (!row.infinite) {
        Draw.textRight(g, if (row.demand > 0) Format.number(row.demand) else "–", c.right, y, Palette.textSecondary)
        return@Column
    }
    val text = "∞ " + Format.number(row.left.toLong())
    Draw.textRight(g, text, c.right, y, if (row.left > 0) Palette.success else Palette.textMuted)
    Draw.leadIcon(g, Icons.INFO, c.right - Draw.width(text) - 12, c.centerY, Palette.textMuted)
    tooltip("left:${row.item}", c, Tip.text(tr("kami_economy.tip.left", unitsText(row.left, MarketSection.stack(row.item).maxStackSize)), tr("kami_economy.nav.infinite")))
}

internal class SearchBox(private val stack: (String) -> ItemStack) {
    val text = TextState()
    private var raw = ""
    private var parsed = SearchQuery.EMPTY
    private var idsFor: String? = null
    private var ids = ""

    val query: SearchQuery get() {
        if (text.text != raw) { raw = text.text; parsed = SearchQuery.parse(raw) }
        return parsed
    }

    fun hit(item: String, vararg extra: String) = ItemSearch.matches(query, stack(item), *extra)

    fun ids(extra: () -> Collection<String>, max: Int): String {
        val q = query
        if (idsFor != raw) { idsFor = raw; ids = searchIds(q, extra(), max) }
        return ids
    }

    fun <T> filter(ui: Ui, key: String, rows: List<T>, test: (T) -> Boolean): List<T> =
        if (query.isEmpty) rows else ui.filtered(key, listOf(text.text, rows)) { rows.filter(test) }
}

private fun orderOpen(app: MarketApp, o: OrderRow) = app.openItem(o.item, if (o.bid == o.mine) "buy" else "sell", if (o.mine) "order" else "instant")

internal fun orderColumns(app: MarketApp, side: Boolean, owner: Boolean, fill: Boolean) = buildList {
    add(itemColumn<OrderRow>({ it.item }, { "order:${it.id}" }, { if (it.mine) Palette.brass else Palette.text }) { orderOpen(app, it) })
    if (side) add(Column.text<OrderRow>(tr("kami_economy.col.side"), 44, color = { if (it.bid) Palette.success else Palette.danger }) { tr(if (it.bid) "kami_economy.side.buy" else "kami_economy.side.sell") })
    if (owner) {
        add(Column.text<OrderRow>(tr("kami_economy.col.owner"), 80, color = { if (it.mine) Palette.brass else Palette.textSecondary }) { it.ownerName })
        add(Column<OrderRow>(tr("kami_libs.common.country"), 90, sort = textOrder { it.country }) { _, c, o ->
            val y = c.y + (c.h - 8) / 2
            flag(Rect(c.x, y, 11, 8), o.color, o.flag)
            Draw.text(g, Draw.fit(o.country, c.w - 14), c.x + 14, y, if (o.relation == "embargo") Palette.danger else Palette.textSecondary)
            if (o.country.isNotEmpty()) tooltip("order-country:${o.id}", c, app.countryTip(o.country, o.relation))
        })
    }
    add(Column.number<OrderRow>(tr("kami_economy.col.price"), 64, tip = tr("kami_economy.col.per_lot"), format = { Format.money(it) }) { it.price.toLong() })
    add(unitsColumn<OrderRow>(tr("kami_libs.common.amount"), 90, { MarketSection.stack(it.item).maxStackSize }) { it.amount })
    add(Column.number<OrderRow>(tr("kami_economy.col.age"), 50, format = { Format.ago(it) }) { it.placedAt })
    add(Column.text<OrderRow>(tr("kami_libs.common.status"), 64, color = { if (it.best) Palette.success else Palette.warning }) {
        tr(if (it.best) "kami_economy.status.best" else if (it.mine) "kami_economy.status.undercut" else "kami_economy.status.none")
    })
    if (fill) add(Column<OrderRow>("", 40) { _, c, o ->
        if (!o.mine && button(Rect(c.x, c.y + 1, c.w, c.h - 2), tr("kami_economy.orders.fill"), key = "fill:${o.id}")) orderOpen(app, o)
    })
}

internal abstract class ListPage(val app: MarketApp, private val view: String) : Page() {
    private val box = SearchBox(MarketSection::stack)
    protected val search = box.text
    protected val query get() = box.query
    protected open val remoteSearch = true
    protected val data get() = app.cached(view)
    protected val loading get() = data == null || app.loading(view)
    protected val stale get() = data != null && app.loading(view)
    private var sendAt = 0L
    private var focusSearch = false

    override fun opened(route: Route) {
        search.set("")
        focusSearch = route.param("search") != null
        app.view(view)
    }

    protected fun searchBar(ui: Ui, bounds: Rect) {
        val r = bounds.left(SEARCH_MAX)
        if (focusSearch) { ui.focus = ui.id("search"); focusSearch = false }
        val now = System.currentTimeMillis()
        if (ui.searchField(r, search) && remoteSearch) sendAt = now + SEARCH_DELAY
        if (sendAt > 0 && now >= sendAt) { sendAt = 0; app.search(view, search.text) }
        if (remoteSearch && (sendAt > 0 || app.loading(view)) && search.text.isNotEmpty()) ui.spinner(r.right - r.h - 10, r.centerY - 4, Palette.textMuted)
    }

    protected fun hit(item: String, vararg extra: String) = box.hit(item, *extra)

    protected fun <T> filtered(ui: Ui, key: String, rows: List<T>, test: (T) -> Boolean) = box.filter(ui, key, rows, test)

    protected fun body(ui: Ui, area: Rect): Rect {
        if (data?.truncated != true) return area
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
    private val orderTable = TableState<OrderRow>()
    private val orderCols = orderColumns(app, side = true, owner = true, fill = false)
    override val title get() = tr("kami_libs.common.market")
    override val help get() = listOf(
        Callout("market:filters", tr("kami_economy.help.market.filters"), tr("kami_economy.help.market.filters.desc")),
        Callout("market:table", tr("kami_economy.help.market.table"), tr("kami_economy.help.market.table.desc"))
    )

    private val columns = listOf(
        itemColumn<Row>({ it.item }, { "market:${it.item}" },
            area = { c, row -> c.dropRight(tradeLock(c, row.status, row.statusReason, "market-lock:${row.item}")) },
            extra = { cell, row -> tooltip("market-tip:${row.item}", cell) { rowTip(row) } }
        ) { app.openItem(it.item) },
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

    private fun rowTip(row: Row) = Tip(MarketSection.stack(row.item).hoverName.string, listOfNotNull(
        row.status.takeIf { it.isNotEmpty() }?.let { statusTip(it, row.statusReason) to Palette.warning },
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
        val snap = data
        val all = snap?.let { filtered(ui, "market:rows", it.rows) { row -> hit(row.item) } }.orEmpty()
        val orders = snap?.let { filtered(ui, "market:orders", it.orders) { o -> hit(o.item, o.ownerName, o.country) } }.orEmpty()
        val options = Filter.entries.map { f -> Option(f, tr("kami_economy.market.filter.${f.name.lowercase()}") + if (snap == null) "" else " ${all.count { keep(f, it) }}") }
        val compact = bar.w - segmentWidth(options) - BAR_GAP < SEARCH_MIN
        val chips = bar.right(if (compact) SELECT_W else segmentWidth(options))
        searchBar(ui, bar.dropRight(chips.w, BAR_GAP))
        ui.anchor("market:filters", chips)
        (if (compact) ui.select(chips, options, filter, key = "filter") else ui.segmented(chips, options, filter, key = "filter"))?.let { filter = it }
        val area = body(ui, r.dropTop(SMALL_H, 6))
        val split = !query.isEmpty && orders.isNotEmpty()
        val rowsArea = if (split) area.top(area.h * 3 / 5) else area
        ui.anchor("market:table", rowsArea)
        val shown = all.filter { keep(filter, it) }
        ui.table(rowsArea, columns, shown, table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_libs.common.nothing_found"), key = "market:$filter", loading = loading).opened?.let { app.openItem(it.item) }
        ui.staleRows(rowsArea, stale && shown.isNotEmpty())
        if (split) {
            val rest = area.dropTop(rowsArea.h, 6)
            Draw.text(ui.g, "${tr("kami_economy.nav.orders")} ${orders.size}", rest.x, rest.y + 2, Palette.textSecondary)
            val ordersArea = rest.dropTop(12, 2)
            ui.table(ordersArea, orderCols, orders, orderTable, { it.id }, rowHeight = ROW_H, emptyText = tr("kami_economy.orders.empty"), key = "market:orders", loading = loading).opened?.let { orderOpen(app, it) }
            ui.staleRows(ordersArea, stale)
        }
    }
}

internal class InstantPage(val app: MarketApp) : Page() {
    private class Pending(val item: String, val qty: Int, val lot: Int)

    private val box = SearchBox(MarketSection::stack)
    private val table = TableState<HeldRow>()
    private var pending: Pending? = null
    override val title get() = tr("kami_economy.nav.inventory")
    override val help get() = listOf(Callout("instant:table", tr("kami_economy.help.instant"), tr("kami_economy.help.instant.desc")))

    private val columns = listOf(
        itemColumn<HeldRow>({ it.item }, { "instant:${it.item}" }) { app.openItem(it.item, "sell", "instant") },
        unitsColumn<HeldRow>(tr("kami_economy.col.held"), 90, { MarketSection.stack(it.item).maxStackSize }) { it.held },
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
        box.text.set("")
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
        ui.searchField(r.top(SMALL_H), box.text)
        val area = r.dropBottom(footer).dropTop(SMALL_H, 6)
        ui.anchor("instant:table", area)
        val snap = app.cached("instant")
        val held = snap?.let { box.filter(ui, "instant:rows", it.held) { h -> box.hit(h.item) } }.orEmpty()
        ui.table(area, columns, held, table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_economy.instant.empty"), loading = snap == null || app.loading("instant")).opened?.let { app.openItem(it.item, "sell", "instant") }
        ui.staleRows(area, snap != null && app.loading("instant") && held.isNotEmpty())
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

    private val columns = orderColumns(app, side = bid == null, owner = bid != null, fill = bid == true)

    override fun draw(ui: Ui, r: Rect) {
        searchBar(ui, r.top(SMALL_H))
        val rows = filtered(ui, "orders:rows", if (bid == null) app.snap.mine else data?.orders.orEmpty()) { hit(it.item, it.ownerName, it.country) }
        val area = body(ui, r.dropTop(SMALL_H, 6))
        val selected = rows.firstOrNull { it.mine && it.id in table.selected }
        val tableArea = if (selected != null) area.dropRight(ORDER_PANEL_W, 6) else area
        ui.anchor("orders:table", tableArea)
        val busy = bid != null && loading
        ui.table(tableArea, columns, rows, table, { it.id }, rowHeight = ROW_H, severity = { if (it.mine && bid != null) Severity.INFO else null }, emptyText = tr("kami_economy.orders.empty"), loading = busy).opened?.let { orderOpen(app, it) }
        ui.staleRows(tableArea, bid != null && stale && rows.isNotEmpty())
        selected?.let { panel(ui, area.right(ORDER_PANEL_W), it) }
    }

    private fun panel(ui: Ui, r: Rect, o: OrderRow) {
        val flow = ui.sidePanel(r)
        val head = flow.take(22)
        val stack = MarketSection.stack(o.item)
        ui.itemSlot(head.left(22), stack, key = "orders-panel-item")
        Draw.text(ui.g, Draw.fit(stack.hoverName.string, head.w - 28), head.x + 26, head.y + 7, TextStyle.HEADING)
        ui.property(flow.take(12), tr("kami_economy.col.price"), Format.money(o.price.toLong()))
        ui.property(flow.take(12), tr("kami_libs.common.amount"), unitsText(o.amount, stack.maxStackSize))
        if (ui.button(flow.take(SMALL_H), tr("kami_economy.reprice.action"), Icons.EDIT, key = "panel-reprice")) app.reprice(app, o)
        if (ui.button(flow.take(SMALL_H), tr("kami_economy.cancel.action"), Icons.CROSS, style = ButtonStyle.DANGER, key = "panel-cancel")) app.cancel(app, o)
    }
}

internal class VendorsPage(app: MarketApp, private val sells: Boolean) : ListPage(app, if (sells) "buy_vendors" else "sell_vendors") {
    private val table = TableState<VendorLine>()
    override val title get() = tr("kami_economy.nav.vendors")
    override val help get() = helpFor("vendors:table", "kami_economy.help.vendors")

    private fun where(v: VendorLine): String {
        val player = Minecraft.getInstance().player ?: return v.dim
        val near = if (Dim.current() == v.dim)
            tr("kami_libs.common.distance", Format.number(player.position().distanceTo(Vec3(v.x + 0.5, v.y + 0.5, v.z + 0.5)).toLong()))
        else tr("kami_economy.market.vendors.far")
        return "${v.dim} · $near"
    }

    private val columns = listOf(
        itemColumn<VendorLine>({ it.item }, { "vendor:${it.item}:${it.x},${it.y},${it.z}" }) { app.openItem(it.item) },
        Column.text<VendorLine>(tr("kami_economy.col.owner"), 80, color = { Palette.textSecondary }) { it.owner },
        Column<VendorLine>(tr("kami_libs.common.country"), 90, sort = textOrder { it.country }) { _, c, v ->
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
        val rows = data?.let { filtered(ui, "vendors:rows", it.vendors) { v -> hit(v.item, v.owner, v.country) } }.orEmpty()
        ui.table(area, columns, rows, table, { "${it.item}|${it.dim}|${it.x},${it.y},${it.z}" }, rowHeight = ROW_H,
            severity = { if (it.embargo) Severity.DANGER else null }, emptyText = tr("kami_economy.vendors.empty"), loading = loading).opened?.let { app.openItem(it.item) }
        ui.staleRows(area, stale && rows.isNotEmpty())
    }
}

internal class InfinitePage(app: MarketApp) : ListPage(app, "infinite") {
    private val table = TableState<Row>()
    override val title get() = tr("kami_economy.nav.infinite")
    override val help get() = helpFor("infinite:table", "kami_economy.help.infinite")

    private val columns = listOf(
        itemColumn<Row>({ it.item }, { "inf:${it.item}" }, area = { c, row -> c.dropRight(tradeLock(c, row.status, row.statusReason, "inf-lock:${row.item}")) }) { app.openItem(it.item, "sell", "instant") },
        Column.number<Row>(tr("kami_economy.col.sell"), 66, tip = tr("kami_economy.col.per_lot"), format = ::priceText) { it.sell.toLong() },
        unitsColumn<Row>(tr("kami_economy.col.left"), 90, { MarketSection.stack(it.item).maxStackSize }) { it.left },
        Column<Row>("", 54) { _, c, row ->
            if (button(Rect(c.x, c.y + 1, c.w, c.h - 2), tr("kami_economy.market.sell_now"), enabled = row.sell > 0, key = "inf-sell:${row.item}")) app.openItem(row.item, "sell", "instant")
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        searchBar(ui, r.top(SMALL_H))
        val area = body(ui, r.dropTop(SMALL_H, 6))
        ui.anchor("infinite:table", area)
        val rows = data?.let { filtered(ui, "infinite:rows", it.rows.filter { row -> row.infinite }) { row -> hit(row.item) } }.orEmpty()
        ui.table(area, columns, rows, table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_libs.common.nothing_found"), loading = loading).opened?.let { app.openItem(it.item, "sell", "instant") }
        ui.staleRows(area, stale && rows.isNotEmpty())
    }
}
