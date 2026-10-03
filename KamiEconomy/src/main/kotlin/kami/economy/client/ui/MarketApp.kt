package kami.economy.client.ui

import kami.economy.client.ClientHooks
import kami.economy.client.MarketPrefs
import kami.economy.economy.Blacklist
import kami.economy.economy.Classification
import kami.economy.net.FlagView
import kami.economy.net.OrderRow
import kami.economy.net.Slot
import kami.economy.net.Snap
import kami.libs.mc.ItemSpec
import kami.libs.text.Text
import kami.libs.ui.app.AppScreen
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Modules
import kami.libs.ui.app.NavBadge
import kami.libs.ui.app.NavGroup
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.app.Tour
import kami.libs.ui.app.confirmDialog
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.numberDialogBody
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
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal const val ROW_H = 18
internal const val MAX_PRICE = 1_000_000L

internal class Throttled<T>(private val load: () -> T) {
    private var at = 0L
    private var value = load()

    fun get(): T {
        val now = System.currentTimeMillis()
        if (now - at >= 500) { at = now; value = load() }
        return value
    }
}

internal class Stored(val slot: Int, val stack: ItemStack)

internal class Carried(val held: Map<String, Int>, val auctionOnly: List<Stored>)

private fun marketStack(id: String): ItemStack = ItemSpec.stack(id).let { if (it.isEmpty) ItemStack(Items.BARRIER) else it }

internal fun Ui.itemCell(c: Rect, stack: ItemStack, name: String, key: String, color: Int = Palette.text): Boolean {
    val clicked = itemSlot(Rect(c.x, c.y, c.h, c.h), stack, key = key)
    Draw.text(g, Draw.fit(name, c.w - c.h - 4), c.x + c.h + 4, c.y + (c.h - 8) / 2, color)
    return clicked
}

internal fun Ui.flag(r: Rect, color: Int, flag: FlagView?) =
    flag?.let { Flags.draw(g, r, color, it.pattern, it.emblem, it.secondary) } ?: Draw.fill(g, r, color or 0xFF000000.toInt())

internal fun priceText(v: Long) = if (v > 0) Format.money(v) else "–"

internal fun permille(v: Long) = if (v == 0L) "–" else (if (v > 0) "+" else "") + tr("kami_libs.unit.percent", Format.decimal(v / 10.0, 1))

internal fun stackHint(units: Int, stack: Int): String {
    if (stack <= 1 || units < stack) return ""
    val rest = units % stack
    return if (rest == 0) tr("kami_economy.stack.exact", units / stack) else tr("kami_economy.stack.hint", units / stack, rest)
}

internal fun unitsText(units: Int, stack: Int): String {
    val hint = stackHint(units, stack)
    return Format.number(units) + if (hint.isEmpty()) "" else " ($hint)"
}

internal fun <T> unitsColumn(title: String, width: Int, stack: (T) -> Int, units: (T) -> Int) =
    Column<T>(title, width, Align.RIGHT, compareBy<T> { units(it) }) { _, r, row ->
        val s = Draw.fit(unitsText(units(row), stack(row)), r.w)
        Draw.text(g, s, r.right - Draw.width(s), r.y + (r.h - 8) / 2)
    }

class MarketApp private constructor() : KamiApp() {
    lateinit var snap: Snap
        private set
    override val module: String? get() = "market"
    override val home = Route("dashboard")
    private val stacks = HashMap<String, ItemStack>()

    internal val carried = Throttled {
        val held = HashMap<String, Int>()
        val auctionOnly = ArrayList<Stored>()
        Minecraft.getInstance().player?.inventory?.items?.forEachIndexed { slot, stack ->
            if (stack.isEmpty) return@forEachIndexed
            when (Blacklist.classify(stack)) {
                Classification.ALLOWED -> held.merge(Blacklist.itemId(stack), stack.count, Int::plus)
                Classification.AUCTION_ONLY -> auctionOnly += Stored(slot, stack)
                else -> {}
            }
        }
        Carried(held, auctionOnly)
    }

    fun stack(id: String): ItemStack = stacks.getOrPut(id) { marketStack(id) }

    fun request(name: String, vararg args: String) = ClientHooks.request(name, *args)

    fun view(name: String, query: String = "") = request("view", name, query)

    fun update(next: Snap, notify: Boolean) {
        snap = next
        if (notify && next.msg.isNotEmpty()) toast(if (next.ok) Severity.SUCCESS else Severity.DANGER, trJson(next.msg))
    }

    override val collapsedGroups get() = MarketPrefs.prefs.collapsedGroups

    override fun collapseChanged() { MarketPrefs.save() }

    private fun group(id: String, items: List<NavItem>) = NavGroup(tr("kami_economy.nav.group.$id"), items, id, collapsible = true)

    private fun badge(bid: Boolean?): () -> NavBadge? = { snap.mine.count { bid == null || it.bid == bid }.takeIf { it > 0 }?.let { NavBadge(it, Severity.INFO) } }

    override fun buildNav() = listOf(
        group("overview", listOf(
            NavItem("dashboard", tr("kami_economy.nav.dashboard"), Icons.DASHBOARD),
            NavItem("market", tr("kami_libs.common.market"), Icons.SEARCH),
            NavItem("my_orders", tr("kami_economy.nav.my_orders"), Icons.SCROLL, badge(null))
        )),
        group("sell", listOf(
            NavItem("instant", tr("kami_economy.nav.inventory"), Icons.CHEST),
            NavItem("infinite", tr("kami_economy.nav.infinite"), Icons.COIN),
            NavItem("sell_orders", tr("kami_economy.nav.orders"), Icons.LEDGER, badge(false)),
            NavItem("sell_vendors", tr("kami_economy.nav.vendors"), Icons.PEOPLE)
        )),
        group("buy", listOf(
            NavItem("buy_orders", tr("kami_economy.nav.orders"), Icons.LEDGER, badge(true)),
            NavItem("buy_vendors", tr("kami_economy.nav.vendors"), Icons.PEOPLE)
        )),
        group("auction", listOf(
            NavItem("auctions", tr("kami_libs.common.auctions"), Icons.SCALES),
            NavItem("auction_create", tr("kami_economy.nav.create"), Icons.ADD)
        ))
    )

    override fun create(id: String): Page = when (id) {
        "market" -> MarketPage(this)
        "my_orders" -> OrdersPage(this, bid = null)
        "instant" -> InstantPage(this)
        "infinite" -> InfinitePage(this)
        "sell_orders" -> OrdersPage(this, bid = false)
        "buy_orders" -> OrdersPage(this, bid = true)
        "sell_vendors" -> VendorsPage(this, sells = false)
        "buy_vendors" -> VendorsPage(this, sells = true)
        "auctions" -> AuctionsPage(this)
        "auction_create" -> AuctionCreatePage(this)
        "item" -> ItemPage(this)
        else -> DashboardPage(this)
    }

    override fun banner(ui: Ui, r: Rect): Int {
        if (snap.citizen) return 0
        ui.banner(r.top(22), Severity.WARNING, tr("kami_libs.lock.no_country"))
        return 22
    }

    override fun beforeFrame() {
        if (tour == null && !MarketPrefs.prefs.tourDone) {
            MarketPrefs.finishTour()
            startTour()
        }
    }

    fun startTour() {
        val dashboard = Route("dashboard")
        tour = Tour(listOfNotNull(
            Callout("modules", tr("kami_economy.tour.modules"), tr("kami_economy.tour.modules.desc"), dashboard).takeIf { Modules.all.size > 1 },
            Callout("nav:dashboard", tr("kami_economy.nav.dashboard"), tr("kami_economy.tour.dashboard.desc"), dashboard),
            Callout("nav:market", tr("kami_libs.common.market"), tr("kami_economy.tour.market.desc")),
            Callout("nav:my_orders", tr("kami_economy.nav.my_orders"), tr("kami_economy.tour.my_orders.desc")),
            Callout("nav:instant", tr("kami_economy.nav.inventory"), tr("kami_economy.tour.instant.desc")),
            Callout("nav:infinite", tr("kami_economy.nav.infinite"), tr("kami_economy.tour.infinite.desc")),
            Callout("nav:sell_orders", tr("kami_economy.nav.orders"), tr("kami_economy.tour.sell_orders.desc")),
            Callout("nav:buy_orders", tr("kami_economy.nav.orders"), tr("kami_economy.tour.buy_orders.desc")),
            Callout("nav:auctions", tr("kami_libs.common.auctions"), tr("kami_economy.tour.auctions.desc")),
            Callout("topbar", tr("kami_economy.tour.topbar"), tr("kami_economy.tour.topbar.desc")),
            Callout("page-help", tr("kami_economy.tour.help"), tr("kami_economy.tour.help.desc"))
        )) {}
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

    fun openItem(item: String, side: String = "buy", kind: String = "instant") =
        navigate(Route("item", mapOf("item" to item, "side" to side, "kind" to kind)))

    fun taxFor(relation: String) = if (relation == "allied" || relation == "family") snap.allyTaxPct else snap.taxPct

    fun countryTip(country: String, relation: String): String =
        if (relation == "embargo") tr("kami_economy.market.embargo.tip", country)
        else tr("kami_economy.market.country.tip", country, tr("kami_economy.market.rate.${relation.ifEmpty { "neutral" }}"), taxFor(relation))

    fun reprice(o: OrderRow) {
        val state = NumberState(o.price.toLong())
        open(Dialog(tr("kami_economy.reprice.title"), stack(o.item).hoverName.string, Icons.EDIT, DialogKind.CONFIRM) { s ->
            s.used = numberDialogBody(s, s.body.y, state, tr("kami_economy.market.price_lot", o.lot), 1, MAX_PRICE)
            dialogButtons(s, tr("kami_economy.reprice.confirm"), state.value != o.price.toLong()) {
                request(if (o.bid) "reprice_bid" else "reprice", o.item, state.value.toString())
                s.close()
            }
        })
    }

    fun cancel(o: OrderRow) = confirmDialog(
        ::open, tr("kami_economy.cancel.title"), stack(o.item).hoverName.string, Icons.CROSS,
        listOf(Consequence(tr(if (o.bid) "kami_economy.market.bid.cancel.tooltip" else "kami_economy.market.orders.cancel.tooltip"))),
        tr("kami_economy.cancel.confirm"), danger = true
    ) { request(if (o.bid) "cancel_bid" else "cancel", o.item) }

    override fun escape() = super.escape() || route.page == "item" && run { back(); true }

    override fun closed() {
        super.closed()
        request("close")
    }

    companion object {
        val instance by lazy { MarketApp() }

        fun receive(snap: Snap) {
            val mc = Minecraft.getInstance()
            val app = instance
            if ((mc.screen as? AppScreen)?.app === app) app.update(snap, true)
            else if (snap.open) {
                app.update(snap, false)
                mc.setScreen(AppScreen(app, Text.msg("kami_libs.common.market")))
                if (app.route.page.isNotEmpty()) app.page(app.route.page).opened(app.route)
            }
        }
    }
}
