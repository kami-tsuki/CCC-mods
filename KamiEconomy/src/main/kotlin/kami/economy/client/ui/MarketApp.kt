package kami.economy.client.ui

import kami.economy.client.ClientHooks
import kami.economy.client.PriceCache
import kami.economy.economy.Blacklist
import kami.economy.economy.Classification
import kami.economy.net.Row
import kami.economy.net.Slot
import kami.economy.net.Snap
import kami.libs.text.Text
import kami.libs.ui.app.AppScreen
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavBadge
import kami.libs.ui.app.NavGroup
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.text.trJson
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData

fun marketStack(id: String): ItemStack {
    val baseId = id.substringBefore('#')
    val item = ResourceLocation.tryParse(baseId)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: Items.BARRIER
    val stack = ItemStack(item)
    if ('#' in id && baseId == "tacz:ammo") stack.set(DataComponents.CUSTOM_DATA, CustomData.of(CompoundTag().apply { putString("AmmoId", id.substringAfter('#')) }))
    return stack
}

class SellEntry(val item: String, val stack: ItemStack, val count: Int, val slot: Int, val cls: Classification)

class MarketApp(snap: Snap) : KamiApp() {
    var snap = snap
        private set
    override val home = Route("browse")
    private val stacks = HashMap<String, ItemStack>()

    fun stack(id: String): ItemStack = stacks.getOrPut(id) { marketStack(id) }

    fun request(name: String, vararg args: String) = ClientHooks.request(name, *args)

    fun update(next: Snap, notify: Boolean) {
        snap = next
        if (notify && next.msg.isNotEmpty()) toast(if (next.ok) Severity.SUCCESS else Severity.DANGER, trJson(next.msg))
    }

    override fun buildNav() = listOf(NavGroup(tr("kami_libs.common.market"), listOf(
        NavItem("browse", tr("kami_economy.market.tab.browse"), Icons.SEARCH),
        NavItem("sell", tr("kami_economy.market.mode.sell"), Icons.CHEST),
        NavItem("orders", tr("kami_economy.market.tab.orders"), Icons.LEDGER, { snap.orders.size.takeIf { it > 0 }?.let { NavBadge(it, Severity.INFO) } }),
        NavItem("auctions", tr("kami_libs.common.auctions"), Icons.SCALES)
    )))

    override fun create(id: String): Page = when (id) {
        "sell" -> SellPage(this)
        "orders" -> OrdersPage(this)
        "auctions" -> AuctionsPage(this)
        "item" -> ItemPage(this)
        "list" -> ListAuctionPage(this)
        else -> BrowsePage(this)
    }

    override fun banner(ui: Ui, r: Rect): Int {
        if (snap.citizen) return 0
        ui.banner(r.top(22), Severity.WARNING, tr("kami_libs.lock.no_country"))
        return 22
    }

    override fun topBar(ui: Ui, r: Rect) {
        Draw.leadIcon(ui.g, Icons.COIN, r.x + 6, r.centerY, Palette.money)
        var x = Draw.text(ui.g, tr("kami_libs.common.market"), r.x + 24, r.centerY - 4, TextStyle.HEADING) + 12
        ui.moneyRight(r.right - 8, r.centerY - 4, snap.funds)
        val limit = r.right - 90
        x = chip(ui, x, r, tr("kami_economy.slots.orders"), snap.orderSlots, limit)
        x = chip(ui, x, r, tr("kami_libs.common.auctions"), snap.auctionSlots, limit)
        if (snap.goal.isNotEmpty()) {
            val text = trJson(snap.goal) + if (snap.goalMax > 0) " ${snap.goalValue}/${snap.goalMax}" else ""
            if (x + Draw.width(text, TextStyle.CAPTION) <= limit) Draw.text(ui.g, text, x, r.centerY - 4, TextStyle.CAPTION)
        }
    }

    fun slotLock(slot: Slot): Lock? =
        if (slot.used < slot.max || (slot.max <= 0 && slot.hint.isEmpty())) null else Lock(tr("kami_libs.format.used", slot.used, slot.max), slot.hint.takeIf { it.isNotEmpty() }?.let { trJson(it) })

    private fun chip(ui: Ui, x: Int, r: Rect, name: String, slot: Slot, limit: Int): Int {
        if (slot.max <= 0 && slot.hint.isEmpty()) return x
        val lock = slotLock(slot)
        val text = "$name ${slot.used}/${slot.max}"
        val w = Draw.width(text) + 10 + if (lock != null) Draw.ICON - 1 else 0
        if (x + w > limit) return x
        if (lock != null) ui.chip(x, r.centerY - 6, text, Palette.warning, Icons.LOCK, lock.how, "slot:$name") else Draw.text(ui.g, text, x + 5, r.centerY - 4, Palette.textMuted)
        return x + w + 6
    }

    fun openItem(item: String, mode: String, qty: Int = 1) = navigate(Route("item", mapOf("item" to item, "mode" to mode, "qty" to qty.toString())))

    fun openSell(e: SellEntry) =
        if (e.cls == Classification.AUCTION_ONLY) navigate(Route("list", mapOf("item" to e.item, "qty" to e.count.toString()))) else openItem(e.item, "sell")

    fun closeDetail() { if (route.page in DETAILS) back() }

    override fun escape() = super.escape() || route.page in DETAILS && run { closeDetail(); true }

    override fun closed() {
        super.closed()
        request("close")
        if (current === this) current = null
    }

    companion object {
        private var current: MarketApp? = null
        private val DETAILS = setOf("item", "list")

        fun receive(snap: Snap) {
            val mc = Minecraft.getInstance()
            val app = current
            if (app != null && (mc.screen as? AppScreen)?.app === app) app.update(snap, true)
            else if (snap.open) {
                val next = MarketApp(snap)
                current = next
                mc.setScreen(AppScreen(next, Text.msg("kami_libs.common.market")))
            }
        }
    }
}

private class BrowsePage(val app: MarketApp) : Page() {
    private val search = TextState()
    override val title get() = tr("kami_economy.market.tab.browse")

    override fun opened(route: Route) { search.set(app.snap.query) }

    override fun draw(ui: Ui, r: Rect) {
        val snap = app.snap
        val bar = r.top(16)
        if (ui.searchField(bar.dropRight(SORT_W, 6), search)) app.request("search", search.text, snap.sort, "0")
        ui.segmented(bar.right(SORT_W), SORTS.map { Option(it, tr("kami_economy.market.sort.$it")) }, snap.sort, key = "sort")
            ?.let { app.request("search", search.text, it, "0") }

        val grid = r.dropTop(16, 6).dropBottom(16, 4)
        if (snap.rows.isEmpty()) ui.emptyState(grid, tr("kami_libs.common.nothing_found"), tr("kami_economy.market.browse.empty"))
        val cols = ((grid.w + GAP) / (CELL_W + GAP)).coerceAtLeast(1)
        val left = grid.x + (grid.w - (cols * (CELL_W + GAP) - GAP)) / 2
        snap.rows.forEachIndexed { i, row ->
            val cell = Rect(left + i % cols * (CELL_W + GAP), grid.y + i / cols * (CELL_H + GAP), CELL_W, CELL_H)
            if (cell.bottom > grid.bottom) return@forEachIndexed
            if (ui.itemSlot(Rect(cell.centerX - 11, cell.y, 22, 22), app.stack(row.item), stock(row), key = "item:${row.item}")) app.openItem(row.item, "buy", if (ui.input.shift) app.stack(row.item).maxStackSize else 1)
            ui.money(cell.centerX - moneyWidth(row.price.toLong()) / 2, cell.y + 25, row.price.toLong())
        }
        if (snap.pages > 1) ui.pager(r.bottom(16).centered(96, 16), snap.page, snap.pages)?.let { app.request("search", search.text, snap.sort, it.toString()) }
    }

    private fun stock(row: Row) = when {
        row.available < 1000 -> row.available.toString()
        else -> "${row.available / 1000}k"
    }

    companion object {
        const val CELL_W = 50
        const val CELL_H = 36
        const val GAP = 4
        const val SORT_W = 150
        val SORTS = listOf("name", "price", "stock")
    }
}

private class SellPage(val app: MarketApp) : Page() {
    private val table = TableState<SellEntry>()
    override val title get() = tr("kami_economy.market.mode.sell")

    private val columns = listOf(
        Column<SellEntry>(tr("kami_libs.common.item"), -1, sort = compareBy { it.stack.hoverName.string.lowercase() }) { _, c, e ->
            if (itemSlot(Rect(c.x, c.y, c.h, c.h), e.stack.copyWithCount(e.count), key = "sell:${e.slot}")) app.openSell(e)
            Draw.text(g, Draw.fit(e.stack.hoverName.string, c.w - c.h - 4), c.x + c.h + 4, c.y + (c.h - 8) / 2)
        },
        Column.number(tr("kami_economy.market.sell.col.count"), 50) { it.count.toLong() },
        Column.number(tr("kami_economy.market.sort.price"), 70, tip = tr("kami_economy.market.sell.col.price.tooltip")) { (PriceCache.of(it.item)?.price ?: 0).toLong() },
        Column.text(tr("kami_economy.market.sell.col.channel"), 90, color = { if (it.cls == Classification.AUCTION_ONLY) Palette.warning else Palette.textSecondary }) {
            tr(if (it.cls == Classification.AUCTION_ONLY) "kami_economy.market.sell.channel.auction" else "kami_libs.common.market")
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        val events = ui.table(r, columns, entries(), table, { it.slot }, rowHeight = 18, emptyText = tr("kami_economy.market.sell.empty"))
        events.opened?.let(app::openSell)
    }

    override fun actionsWidth() = buttonWidth(tr("kami_economy.market.sell.open"))

    override fun actions(ui: Ui, r: Rect) {
        val selected = entries().firstOrNull { it.slot in table.selected }
        val label = tr("kami_economy.market.sell.open")
        if (ui.edgeButton(r, label, style = ButtonStyle.PRIMARY, enabled = app.snap.citizen && selected != null, disabledReason = if (!app.snap.citizen) tr("kami_libs.lock.no_country") else tr("kami_economy.market.sell.open.none")))
            selected?.let(app::openSell)
    }

    private fun entries(): List<SellEntry> {
        val inv = Minecraft.getInstance().player?.inventory ?: return emptyList()
        val entries = mutableListOf<SellEntry>()
        val grouped = HashMap<String, Int>()
        inv.items.forEachIndexed { i, stack ->
            if (stack.isEmpty) return@forEachIndexed
            val cls = Blacklist.classify(stack)
            if (cls == Classification.BLOCKED) return@forEachIndexed
            val id = Blacklist.itemId(stack)
            val idx = grouped[id].takeIf { cls == Classification.ALLOWED }
            if (idx != null) {
                val e = entries[idx]
                entries[idx] = SellEntry(e.item, e.stack, e.count + stack.count, e.slot, e.cls)
            } else {
                if (cls == Classification.ALLOWED) grouped[id] = entries.size
                entries += SellEntry(id, stack, stack.count, i, cls)
            }
        }
        return entries
    }
}
