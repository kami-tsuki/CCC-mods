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
import kami.libs.ui.core.Memo
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
import kotlin.reflect.KProperty

internal class Localized<T>(private val build: () -> T) {
    private val memo = Memo()

    operator fun getValue(owner: Any?, property: KProperty<*>): T = memo.of(Format.locale) { build() }
}

internal fun <T> localized(build: () -> T) = Localized(build)

private const val SEARCH_DELAY = 250L
private const val ORDER_PANEL_W = 130
internal const val SEARCH_MAX = 200
internal const val SEARCH_MIN = 110
internal const val BAR_GAP = 6
private const val QUEST_DOT_W = 10

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
        ui.statusPill(area.x, area.bottom - 13, tr("kami_economy.list.truncated"), Severity.INFO, tr("kami_economy.list.truncated.tip"), "list-truncated", area.w)
        return area.dropBottom(17)
    }

    protected fun helpFor(anchor: String, key: String) = listOf(Callout(anchor, tr(key), tr("$key.desc")))
}

internal class MarketPage(app: MarketApp) : ListPage(app, "market") {
    private enum class Filter { ALL, STARTER, SELLERS, BUYERS, HELD }

    private var filter = Filter.ALL
    private val filterOptions = Memo()
    private var held: Map<String, Int> = emptyMap()
    private val table = TableState<Row>()
    private val orderTable = TableState<OrderRow>()
    private val orderCols by localized { orderColumns(app, side = true, owner = true, fill = false) }
    override val title get() = tr("kami_libs.common.market")
    override val help get() = listOf(
        Callout("market:filters", tr("kami_economy.help.market.filters"), tr("kami_economy.help.market.filters.desc")),
        Callout("market:table", tr("kami_economy.help.market.table"), tr("kami_economy.help.market.table.desc"))
    )

    private val columns by localized { listOf(
        itemColumn<Row>({ it.item }, { "market:${it.item}" },
            area = { c, row -> c.dropRight(tradeLock(c, row.status, row.statusReason, "market-lock:${row.item}")).let { if (row.quest != null) it.dropRight(QUEST_DOT_W) else it } },
            extra = { cell, row ->
                tooltip("market-tip:${row.item}", cell) { rowTip(row) }
                row.quest?.let { questDot(cell, it, row.item) }
            }
        ) { app.openItem(it.item) },
        rateColumn<Row>(tr("kami_libs.common.price"), 58, rate = { it.perItem() }),
        Column.number<Row>(tr("kami_libs.range.24h"), 52, color = { if (it.change > 0) Palette.success else if (it.change < 0) Palette.danger else Palette.textMuted }, format = { signedPercent(it / 10.0, 1) }) { it.change.toLong() },
        ownAware(lotPriceColumn<Row>(tr("kami_economy.col.buy_now"), 70, lot = { it.lot }) { it.buy.toLong() }, "ask") { it.myAsk },
        ownAware(lotPriceColumn<Row>(tr("kami_economy.market.sell_now"), 70, empty = -1, lot = { it.lot }) { it.sell.toLong() }, "bid") { it.myBid },
        supplyColumn(),
        Column<Row>("", ROW_H) { _, c, row ->
            if (row.item in held && iconButton(Rect(c.x, c.y, c.h, c.h), Icons.COIN, tr("kami_economy.market.sell_now"), key = "sell:${row.item}")) app.openItem(row.item, "sell", "instant")
        }
    ) }

    private val tableColumns by localized { childColumns(app, columns, 1, 3, 4) }

    private fun rowTip(row: Row) = Tip(MarketSection.stack(row.item).hoverName.string, listOfNotNull(
        row.status.takeIf { it.isNotEmpty() }?.let { statusTip(it, row.statusReason) to Palette.warning },
        row.last.takeIf { it > 0 }?.let { tr("kami_economy.tip.last", Format.unitMoney(it.toLong(), row.lot)) to Palette.textSecondary },
        tr("kami_economy.tip.volume", Format.number(row.volume)) to Palette.textSecondary,
        tr("kami_economy.tip.totals", Format.number(row.sold), Format.number(row.bought)) to Palette.textSecondary,
        tr("kami_economy.tip.lot", row.lot) to Palette.textSecondary,
        row.vendorPrice.takeIf { it > 0 }?.let { tr("kami_economy.market.vendors.price", Format.money(it.toLong())) to Palette.textSecondary },
        row.myAsk.takeIf { it > 0 }?.let { tr("kami_economy.tip.my_ask", Format.unitMoney(it.toLong(), row.lot)) to Palette.brass },
        row.myBid.takeIf { it > 0 }?.let { tr("kami_economy.tip.my_bid", Format.unitMoney(it.toLong(), row.lot)) to Palette.brass }
    ))

    private fun keep(f: Filter, row: Row) = when (f) {
        Filter.ALL -> true
        Filter.STARTER -> row.starter
        Filter.SELLERS -> row.available != 0
        Filter.BUYERS -> row.demand != 0 && !(row.left == 0 && row.demand < 0)
        Filter.HELD -> row.item in held || row.variants.any { it.item in held }
    }

    override fun draw(ui: Ui, r: Rect) {
        held = app.carried.get().held
        val bar = r.top(SMALL_H)
        val snap = data
        val all = snap?.let { filtered(ui, "market:rows", it.rows) { row -> hit(row.item) || row.variants.any { hit(it.item) } } }.orEmpty()
        val orders = snap?.let { filtered(ui, "market:orders", it.orders) { o -> hit(o.item, o.ownerName, o.country) } }.orEmpty()
        val counts = ui.filtered("market:counts", listOf(snap?.rows, all, held)) { Filter.entries.map { f -> all.count { keep(f, it) } } }
        val options = filterOptions.of(counts, snap == null, Format.locale) { Filter.entries.mapIndexed { i, f -> Option(f, tr("kami_economy.market.filter.${f.name.lowercase()}") + if (snap == null) "" else " ${counts[i]}") } }
        val compact = bar.w - segmentWidth(options) - BAR_GAP < SEARCH_MIN
        val chips = bar.right(if (compact) SELECT_W else segmentWidth(options))
        searchBar(ui, bar.dropRight(chips.w, BAR_GAP))
        ui.anchor("market:filters", chips)
        (if (compact) ui.select(chips, options, filter, key = "filter") else ui.segmented(chips, options, filter, key = "filter"))?.let { filter = it }
        val area = body(ui, r.dropTop(SMALL_H, 6))
        val split = !query.isEmpty && orders.isNotEmpty()
        val rowsArea = if (split) area.top(area.h * 3 / 5) else area
        ui.anchor("market:table", rowsArea)
        val shown = ui.filtered("market:shown", listOf(filter, search.text, held, snap?.rows, all)) { all.filter { keep(filter, it) } }
        ui.table(rowsArea, tableColumns, shown, table, { it.item }, rowHeight = ROW_H, rowHeightOf = ::childHeight, regroup = ::groupRows, emptyText = tr("kami_libs.common.nothing_found"), key = "market:$filter", loading = loading).opened?.let { app.openItem(it.item) }
        ui.staleRows(rowsArea, stale && shown.isNotEmpty())
        if (split) {
            val rest = area.dropTop(rowsArea.h, 6)
            Draw.text(ui.g, "${tr("kami_economy.nav.orders")} ${orders.size}", rest.x, rest.y + 2, Palette.textSecondary)
            val ordersArea = rest.dropTop(12, 2)
            ui.table(ordersArea, orderCols, orders, orderTable, { it.id }, rowHeight = ROW_H, emptyText = tr("kami_libs.table.empty"), key = "market:orders", loading = loading).opened?.let { orderOpen(app, it) }
            ui.staleRows(ordersArea, stale)
        }
    }
}

internal class InstantPage(val app: MarketApp) : Page() {
    private class Pending(val item: String, val qty: Int, val lot: Int)

    private val box = SearchBox(MarketSection::stack)
    private val table = TableState<HeldRow>()
    private var pending: Pending? = null
    private var sellable = false
    private val sellableOptions by localized { listOf(Option(false, tr("kami_libs.common.all")), Option(true, tr("kami_economy.instant.filter.sellable"))) }
    private val sellAll by localized { tr("kami_economy.instant.sell_all") }
    override val title get() = tr("kami_economy.nav.inventory")
    override val help get() = listOf(Callout("instant:table", tr("kami_economy.help.instant"), tr("kami_economy.help.instant.desc")))

    private val columns by localized { listOf(
        itemColumn<HeldRow>({ it.item }, { "instant:${it.item}" },
            area = { c, h -> c.dropRight(factorText(h.item).let { if (it.isEmpty()) 0 else Draw.width(it) + 4 }) },
            extra = { cell, h -> factorText(h.item).takeIf { it.isNotEmpty() }?.let { Draw.text(g, it, cell.right + 2, cell.y + (cell.h - 8) / 2, Palette.textMuted) } }
        ) { app.openItem(it.item, "sell", "instant") },
        unitsColumn<HeldRow>(tr("kami_economy.col.held"), 90, { MarketSection.stack(it.item).maxStackSize }) { it.held },
        lotPriceColumn<HeldRow>(tr("kami_economy.market.sell_now"), 70, empty = -1, lot = { it.lot }) { it.sell.toLong() },
        Column.number<HeldRow>(tr("kami_economy.col.receive"), 70, tip = tr("kami_economy.col.receive.tip"), color = { if (it.net > 0) Palette.success else Palette.textMuted }, format = ::priceText) { it.net },
        Column.text<HeldRow>(tr("kami_libs.common.source"), 80, color = { if (it.source == "none") Palette.danger else Palette.textSecondary }) { tr("kami_economy.source.${it.source}") },
        Column<HeldRow>("", buttonWidth(sellAll)) { _, c, h ->
            val reason = when {
                !app.snap.citizen -> tr("kami_libs.lock.no_country")
                h.held < h.lot -> tr("kami_economy.action.below_lot", Format.number(h.lot))
                h.net <= 0 -> tr("kami_economy.action.no_buyers")
                else -> null
            }
            if (button(Rect(c.x, c.y + 1, c.w, c.h - 2), sellAll, enabled = reason == null, disabledReason = reason, key = "all:${h.item}")) {
                pending = Pending(h.item, h.cap, h.lot)
                app.request("quote", "sell_market", h.item, h.cap.toString(), "0")
            }
        }
    ) }

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
        val bar = r.top(SMALL_H)
        val options = sellableOptions
        val seg = bar.right(segmentWidth(options))
        ui.searchField(bar.dropRight(seg.w, BAR_GAP).left(SEARCH_MAX), box.text)
        ui.segmented(seg, options, sellable, key = "sellable")?.let { sellable = it }
        val area = r.dropBottom(footer).dropTop(SMALL_H, 6)
        ui.anchor("instant:table", area)
        val snap = app.cached("instant")
        val found = snap?.let { box.filter(ui, "instant:rows", it.held) { h -> box.hit(h.item) } }.orEmpty()
        val held = if (sellable) ui.filtered("instant:sellable", listOf(box.text.text, found)) { found.filter { it.net > 0 } } else found
        ui.table(area, columns, held, table, { it.item }, rowHeight = ROW_H, regroup = ::groupHeld, emptyText = tr("kami_economy.instant.empty"), loading = snap == null || app.loading("instant")).opened?.let { app.openItem(it.item, "sell", "instant") }
        ui.staleRows(area, snap != null && app.loading("instant") && held.isNotEmpty())
        if (extra > 0) {
            val text = tr("kami_economy.instant.auction_only", extra)
            Draw.text(ui.g, text, r.x, r.bottom - 8, Palette.textMuted)
            if (ui.link(r.x + Draw.width(text) + 6, r.bottom - 8, tr("kami_economy.instant.auction_link"))) app.navigate(Route("auction_create"))
        }
    }
}

internal enum class Side(val label: String) {
    ALL("kami_libs.common.all"), BUY("kami_economy.side.buy"), SELL("kami_economy.side.sell")
}

internal class OrdersPage(app: MarketApp, private val bid: Boolean?) : ListPage(app, when (bid) { null -> "my_orders"; true -> "buy_orders"; false -> "sell_orders" }) {
    private val table = TableState<OrderRow>()
    private var side = Side.ALL
    private val sideOptions by localized { Side.entries.map { Option(it, tr(it.label)) } }
    override val remoteSearch get() = bid != null
    override val title get() = tr(when (bid) { null -> "kami_economy.nav.my_orders"; true -> "kami_economy.nav.buy_orders"; false -> "kami_economy.nav.sell_orders" })
    override val help get() = helpFor("orders:table", "kami_economy.help.orders")

    private val columns by localized { orderColumns(app, side = bid == null, owner = bid != null, fill = bid == true) }

    override fun draw(ui: Ui, r: Rect) {
        val bar = r.top(SMALL_H)
        if (bid == null) {
            val options = sideOptions
            val seg = bar.right(segmentWidth(options))
            searchBar(ui, bar.dropRight(seg.w, BAR_GAP))
            ui.segmented(seg, options, side, key = "side")?.let { side = it }
        } else searchBar(ui, bar)
        val found = filtered(ui, "orders:rows", if (bid == null) app.snap.mine else data?.orders.orEmpty()) { hit(it.item, it.ownerName, it.country) }
        val rows = if (side == Side.ALL) found else ui.filtered("orders:side", listOf(side, found)) { found.filter { (it.bid) == (side == Side.BUY) } }
        val area = body(ui, r.dropTop(SMALL_H, 6))
        val selected = rows.firstOrNull { it.mine && it.id in table.selected }
        val split = ui.masterDetail(area, ORDER_PANEL_W, selected != null) { table.selected.clear() }
        split.list?.let { tableArea ->
            ui.anchor("orders:table", tableArea)
            val busy = bid != null && loading
            ui.table(tableArea, columns, rows, table, { it.id }, rowHeight = ROW_H, severity = { if (it.mine && bid != null) Severity.INFO else null }, emptyText = tr("kami_libs.table.empty"), loading = busy).opened?.let { orderOpen(app, it) }
            ui.staleRows(tableArea, bid != null && stale && rows.isNotEmpty())
        }
        selected?.let { o -> split.detail?.let { panel(ui, it, o) } }
    }

    private fun panel(ui: Ui, r: Rect, o: OrderRow) {
        val flow = ui.sidePanel(r)
        val head = flow.take(22)
        val stack = MarketSection.stack(o.item)
        ui.itemSlot(head.left(22), stack, key = "orders-panel-item")
        ui.fitText(head.x + 26, head.y + 7, head.w - 28, stack.hoverName.string, style = TextStyle.HEADING)
        ui.property(flow.take(12), tr("kami_libs.common.price"), Format.unitMoney(o.price.toLong(), o.lot))
        ui.property(flow.take(12), tr("kami_libs.common.amount"), unitsText(o.amount, stack.maxStackSize))
        if (ui.button(flow.take(SMALL_H), tr("kami_economy.reprice.action"), Icons.EDIT, key = "panel-reprice")) app.reprice(app, o)
        if (ui.button(flow.take(SMALL_H), tr("kami_economy.cancel.action"), Icons.CROSS, style = ButtonStyle.DANGER, key = "panel-cancel")) app.cancel(app, o)
    }
}

internal class VendorsPage(app: MarketApp, private val sells: Boolean) : ListPage(app, if (sells) "buy_vendors" else "sell_vendors") {
    private val table = TableState<VendorLine>()
    override val title get() = tr(if (sells) "kami_economy.nav.buy_vendors" else "kami_economy.nav.sell_vendors")
    override val help get() = helpFor("vendors:table", "kami_economy.help.vendors")

    private fun where(v: VendorLine): String {
        val player = Minecraft.getInstance().player ?: return v.dim
        val near = if (Dim.current() == v.dim)
            tr("kami_libs.common.distance", Format.number(player.position().distanceTo(Vec3(v.x + 0.5, v.y + 0.5, v.z + 0.5)).toLong()))
        else tr("kami_economy.market.vendors.far")
        return "${v.dim} · $near"
    }

    private val columns by localized { listOf(
        itemColumn<VendorLine>({ it.item }, { "vendor:${it.item}:${it.x},${it.y},${it.z}" }) { app.openItem(it.item) },
        Column.text<VendorLine>(tr("kami_libs.common.owner"), 80, color = { Palette.textSecondary }) { it.owner },
        Column<VendorLine>(tr("kami_libs.common.country"), 90, sort = textOrder { it.country }) { _, c, v ->
            val y = c.y + (c.h - 8) / 2
            flag(Rect(c.x, y, 11, 8), v.color, v.flag)
            fitText(c.x + 14, y, c.w - 14, v.country, if (v.embargo) Palette.danger else Palette.textSecondary, key = "vc:${v.x},${v.y},${v.z}")
            if (v.embargo) tooltip("vendor-embargo:${v.x},${v.y},${v.z}", c, tr("kami_libs.lock.embargo", v.country))
        },
        Column<VendorLine>(tr("kami_libs.common.price"), 64, Align.RIGHT, compareBy<VendorLine> { it.price.toDouble() / it.lot.coerceAtLeast(1) }, tr("kami_economy.col.per_item")) { _, c, v ->
            Draw.textRight(g, Format.unitMoney(v.price.toLong(), v.lot), c.right, c.y + (c.h - 8) / 2, Palette.text)
            if (v.count > 1) tooltip("vendor-bundle:${v.x},${v.y},${v.z}:${v.item}", c) { Tip.text(tr("kami_economy.vendor.bundle", Format.money((v.price.toLong() * v.count / v.lot.coerceAtLeast(1))), Format.number(v.count.toLong()))) }
        },
        Column.number<VendorLine>(tr("kami_economy.col.stock"), 48, format = { if (it >= 0) Format.number(it) else "–" }) { it.stock.toLong() },
        Column<VendorLine>(tr("kami_economy.col.location"), 100) { _, c, v ->
            cellText(c, "${v.x}, ${v.y}, ${v.z}", Palette.textSecondary, key = "vw:${v.x},${v.y},${v.z}")
            tooltip("vendor-where:${v.x},${v.y},${v.z}", c) { Tip.text(where(v)) }
        }
    ) }

    override fun draw(ui: Ui, r: Rect) {
        searchBar(ui, r.top(SMALL_H))
        val area = body(ui, r.dropTop(SMALL_H, 6))
        ui.anchor("vendors:table", area)
        val rows = data?.let { filtered(ui, "vendors:rows", it.vendors) { v -> hit(v.item, v.owner, v.country) } }.orEmpty()
        ui.table(area, columns, rows, table, { "${it.item}|${it.dim}|${it.x},${it.y},${it.z}" }, rowHeight = ROW_H,
            severity = { if (it.embargo) Severity.DANGER else null }, emptyText = tr("kami_libs.common.nothing_found"), loading = loading).opened?.let { app.openItem(it.item) }
        ui.staleRows(area, stale && rows.isNotEmpty())
    }
}

internal class InfinitePage(app: MarketApp) : ListPage(app, "infinite") {
    private val table = TableState<Row>()
    override val title get() = tr("kami_economy.nav.infinite")
    override val help get() = helpFor("infinite:table", "kami_economy.help.infinite")

    private val columns by localized { listOf(
        itemColumn<Row>({ it.item }, { "inf:${it.item}" }, area = { c, row -> c.dropRight(tradeLock(c, row.status, row.statusReason, "inf-lock:${row.item}")) }) { app.openItem(it.item, "sell", "instant") },
        lotPriceColumn<Row>(tr("kami_economy.market.sell_now"), 70, empty = -1, lot = { it.lot }) { it.sell.toLong() },
        unitsColumn<Row>(tr("kami_economy.col.left"), 90, { MarketSection.stack(it.item).maxStackSize }) { if (it.left < 0) -1 else (it.left.toLong() * it.lot).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() },
        Column<Row>("", buttonWidth(tr("kami_economy.market.sell_now"))) { _, c, row ->
            if (button(Rect(c.x, c.y + 1, c.w, c.h - 2), tr("kami_economy.market.sell_now"), enabled = row.sell > 0, key = "inf-sell:${row.item}")) app.openItem(row.item, "sell", "instant")
        }
    ) }

    private val tableColumns by localized { childColumns(app, columns, -1, -1, 1) }

    override fun draw(ui: Ui, r: Rect) {
        searchBar(ui, r.top(SMALL_H))
        val area = body(ui, r.dropTop(SMALL_H, 6))
        ui.anchor("infinite:table", area)
        val rows = data?.let { snap -> ui.filtered("infinite:shown", listOf(search.text, snap.rows)) { filtered(ui, "infinite:rows", snap.rows.filter { row -> row.infinite }) { row -> hit(row.item) || row.variants.any { hit(it.item) } } } }.orEmpty()
        ui.table(area, tableColumns, rows, table, { it.item }, rowHeight = ROW_H, rowHeightOf = ::childHeight, regroup = ::groupRows, emptyText = tr("kami_libs.common.nothing_found"), loading = loading).opened?.let { app.openItem(it.item, "sell", "instant") }
        ui.staleRows(area, stale && rows.isNotEmpty())
    }
}
