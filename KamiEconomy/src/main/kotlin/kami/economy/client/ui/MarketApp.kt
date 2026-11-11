package kami.economy.client.ui

import kami.economy.client.ClientHooks
import kami.economy.client.MarketPrefs
import kami.economy.client.PriceCache
import kami.economy.client.admin.MarketSection
import kami.economy.economy.Blacklist
import kami.economy.economy.ClientVariants as Variants
import kami.economy.economy.Classification
import kami.economy.net.Detail
import kami.economy.net.FlagView
import kami.economy.net.OrderRow
import kami.economy.net.Slot
import kami.economy.net.Snap
import kami.libs.mc.ItemSpec
import kami.libs.text.SearchQuery
import kami.libs.text.Text
import kami.libs.jei.DropTarget
import kami.libs.ui.app.AppScreen
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Modules
import kami.libs.ui.app.NavBadge
import kami.libs.ui.app.NavGroup
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.OpenWait
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
import kami.libs.ui.style.Metrics
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.ItemSearch
import kami.libs.ui.text.tr
import kami.libs.ui.text.trJson
import kami.libs.ui.widget.*
import kami.libs.util.Time
import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

internal val ROW_H get() = Metrics.tableRowH
private const val MAX_IDS = 28000
private const val VIEW_CACHE = 24
private const val DETAIL_CACHE = 16
private const val VIEW_WAIT_MS = 10_000L
internal const val MAX_PRICE = 1_000_000L
internal const val SOON_MS = Time.HOUR_MS
internal const val AUCTION_SOON_MS = 300_000L

internal fun searchIds(q: SearchQuery, extra: Collection<String>, max: Int = MAX_IDS): String =
    if (q.isEmpty) "" else ItemSearch.ids(q, extra).map(Variants::core).distinct().joinToString(",").takeIf { it.length <= max } ?: "*"

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

private class Lru<V>(private val max: Int) : LinkedHashMap<String, V>(max, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
}

internal fun marketStack(id: String): ItemStack = ItemSpec.stack(id).let { if (it.isEmpty) ItemStack(Items.BARRIER) else it }

internal fun Ui.flag(r: Rect, color: Int, flag: FlagView?) =
    flag?.let { Flags.draw(g, r, color, it.pattern, it.emblem, it.secondary) } ?: Draw.fill(g, r, color or 0xFF000000.toInt())

internal fun priceText(v: Long) = if (v > 0) Format.money(v) else "–"

internal fun signedPercent(percent: Double, digits: Int = 0) =
    (if (percent > 0) "+" else if (percent < 0) "-" else "±") + tr("kami_libs.unit.percent", Format.decimal(abs(percent), digits))

internal fun countText(v: Int, zero: String = Format.number(0)) = if (v < 0) "∞" else if (v == 0) zero else Format.number(v)

internal fun infiniteText(v: Long) = countText(if (v == Long.MAX_VALUE) -1 else v.toInt())

internal fun stackHint(units: Int, stack: Int): String {
    if (stack <= 1 || units < stack) return ""
    val rest = units % stack
    return if (rest == 0) tr("kami_economy.stack.exact", units / stack) else tr("kami_economy.stack.hint", units / stack, rest)
}

internal fun unitsText(units: Int, stack: Int): String {
    if (units < 0) return countText(units)
    val hint = stackHint(units, stack)
    return Format.number(units) + if (hint.isEmpty()) "" else " ($hint)"
}

internal fun <T> unitsColumn(title: String, width: Int, stack: (T) -> Int, units: (T) -> Int) =
    Column<T>(title, width, Align.RIGHT, compareBy<T> { units(it) }) { _, r, row ->
        cellText(r, unitsText(units(row), stack(row)), align = Align.RIGHT, key = "uc:${r.x},${r.y}")
    }

class MarketApp private constructor() : KamiApp() {
    lateinit var snap: Snap
        private set
    var loaded = false
        private set
    override val module: String? get() = "market"
    override val home = Route("dashboard")
    private val views = Lru<Snap>(VIEW_CACHE)
    private val latest = HashMap<String, Snap>()
    private val queries = HashMap<String, String>()
    private val details = Lru<Detail>(DETAIL_CACHE)
    private var awaiting: String? = null
    private var last: List<String>? = null
    private var awaitAt = 0L

    init {
        bind(MarketPrefs.prefs, MarketPrefs::save)
    }

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

    fun request(name: String, vararg args: String) = ClientHooks.request(name, *args)

    fun view(name: String, query: String = "", ids: String = "", subject: String = "") {
        queries[name] = query
        awaiting = name
        last = listOf(name, query, ids, subject)
        awaitAt = System.currentTimeMillis()
        request("view", name, query, ids, subject)
    }

    fun search(name: String, query: String) = view(name, query, searchIds(SearchQuery.parse(query), PriceCache.items + MarketSection.cachedIds))

    fun cached(view: String): Snap? = views["$view|${queries[view].orEmpty()}"] ?: latest[view]

    fun loading(view: String) = awaiting == view && System.currentTimeMillis() - awaitAt < VIEW_WAIT_MS

    fun detail(item: String): Detail? = details[item]

    private var importAt = 0L

    private fun importWatch(next: Snap) {
        val legacy = MarketPrefs.prefs.watch ?: return
        val now = System.currentTimeMillis()
        if (legacy.isNotEmpty() && next.watch.isEmpty()) {
            if (now - importAt < IMPORT_RETRY_MS) return
            importAt = now
            request("watch_import", legacy.distinct().joinToString(","))
            return
        }
        MarketPrefs.prefs.watch = null
        MarketPrefs.save()
    }

    fun update(next: Snap, notify: Boolean) {
        importWatch(next)
        snap = next
        loaded = true
        views["${next.view}|${queries[next.view].orEmpty()}"] = next
        latest[next.view] = next
        if (awaiting == next.view) awaiting = null
        next.detail?.let { details[it.item] = it }
        (next.rows + next.watchRows).forEach { PriceCache.lot(it.item, it.lot) }
        next.detail?.let { PriceCache.lot(it.item, it.lot) }
        if (notify && next.msg.isNotEmpty()) toast(if (next.ok) Severity.SUCCESS else Severity.DANGER, trJson(next.msg))
    }

    fun reset() {
        loaded = false
        views.clear()
        latest.clear()
        queries.clear()
        details.clear()
        awaiting = null
        last = null
        skipUntil = 0
        opening.done()
    }

    private fun badge(bid: Boolean?): () -> NavBadge? = { snap.mine.count { bid == null || it.bid == bid }.takeIf { it > 0 }?.let { NavBadge(it, Severity.INFO) } }

    override fun buildNav() = listOf(
        NavGroup(id = "overview", label = tr("kami_libs.common.overview"), items = listOf(
            NavItem("dashboard", tr("kami_libs.common.dashboard"), Icons.DASHBOARD),
            NavItem("market", tr("kami_libs.common.market"), Icons.SEARCH),
            NavItem("my_orders", tr("kami_economy.nav.my_orders"), Icons.SCROLL, badge(null)),
            NavItem("quests", tr("kami_libs.flow.quest"), Icons.CROWN, { snap.questOpen.takeIf { it > 0 }?.let { NavBadge(it, Severity.WARNING) } })
        )),
        NavGroup(id = "sell", label = tr("kami_economy.nav.group.sell"), items = listOf(
            NavItem("instant", tr("kami_economy.nav.inventory"), Icons.CHEST),
            NavItem("infinite", tr("kami_economy.nav.infinite"), Icons.COIN),
            NavItem("sell_orders", tr("kami_economy.nav.sell_orders"), Icons.LEDGER, badge(false)),
            NavItem("sell_vendors", tr("kami_economy.nav.sell_vendors"), Icons.PEOPLE)
        )),
        NavGroup(id = "buy", label = tr("kami_economy.nav.group.buy"), items = listOf(
            NavItem("buy_orders", tr("kami_economy.nav.buy_orders"), Icons.LEDGER, badge(true)),
            NavItem("buy_vendors", tr("kami_economy.nav.buy_vendors"), Icons.PEOPLE)
        )),
        NavGroup(id = "auction", label = tr("kami_libs.common.auctions"), items = listOf(
            NavItem("auctions", tr("kami_libs.common.auctions"), Icons.SCALES),
            NavItem("auction_create", tr("kami_economy.nav.create"), Icons.ADD)
        )),
        NavGroup(id = "system", label = tr("kami_libs.common.system"), items = listOf(NavItem("settings", tr("kami_libs.common.settings"), Icons.SETTINGS)))
    )

    override fun create(id: String): Page = when (id) {
        "market" -> MarketPage(this)
        "my_orders" -> OrdersPage(this, bid = null)
        "quests" -> QuestsPage(this)
        "instant" -> InstantPage(this)
        "infinite" -> InfinitePage(this)
        "sell_orders" -> OrdersPage(this, bid = false)
        "buy_orders" -> OrdersPage(this, bid = true)
        "sell_vendors" -> VendorsPage(this, sells = false)
        "buy_vendors" -> VendorsPage(this, sells = true)
        "auctions" -> AuctionsPage(this)
        "auction_create" -> AuctionCreatePage(this)
        "item" -> ItemPage(this)
        "settings" -> SettingsPage(this)
        else -> DashboardPage(this)
    }

    override fun banner(ui: Ui, r: Rect): Int {
        if (snap.citizen) return 0
        ui.banner(r.top(22), Severity.WARNING, tr("kami_libs.lock.no_country"))
        return 22
    }

    override fun autoRefresh() {
        if (route.page == "item") (page("item") as? ItemPage)?.refresh() else last?.let { request("view", *it.toTypedArray()) }
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
            Callout("modules", tr("kami_libs.common.modules"), tr("kami_economy.tour.modules.desc"), dashboard).takeIf { Modules.visible.size > 1 },
            Callout("nav:dashboard", tr("kami_libs.common.dashboard"), tr("kami_economy.tour.dashboard.desc"), dashboard),
            Callout("nav:market", tr("kami_libs.common.market"), tr("kami_economy.tour.market.desc")),
            Callout("nav:my_orders", tr("kami_economy.nav.my_orders"), tr("kami_economy.tour.my_orders.desc")),
            Callout("nav:instant", tr("kami_economy.nav.inventory"), tr("kami_economy.tour.instant.desc")),
            Callout("nav:infinite", tr("kami_economy.nav.infinite"), tr("kami_economy.tour.infinite.desc")),
            Callout("nav:sell_orders", tr("kami_economy.nav.sell_orders"), tr("kami_economy.tour.sell_orders.desc")),
            Callout("nav:buy_orders", tr("kami_economy.nav.buy_orders"), tr("kami_economy.tour.buy_orders.desc")),
            Callout("nav:auctions", tr("kami_libs.common.auctions"), tr("kami_economy.tour.auctions.desc")),
            Callout("nav:settings", tr("kami_libs.common.settings"), tr("kami_economy.tour.settings.desc")),
            Callout("topbar", tr("kami_economy.tour.topbar"), tr("kami_economy.tour.topbar.desc")),
            Callout("page-help", tr("kami_economy.tour.help"), tr("kami_economy.tour.help.desc"))
        )) {}
    }

    override fun topBar(ui: Ui, r: Rect) {
        Draw.leadIcon(ui.g, Icons.COIN, r.x + 6, r.centerY, Palette.money)
        var x = Draw.text(ui.g, tr("kami_libs.common.market"), r.x + 24, r.centerY - 4, TextStyle.HEADING) + 12
        ui.moneyRight(r.right - 8, r.centerY - 4, snap.funds)
        val limit = r.right - 90
        val chips = listOf(tr("kami_economy.slots.orders") to snap.orderSlots, tr("kami_libs.common.auctions") to snap.auctionSlots).filter { (_, slot) -> slot.max > 0 || slot.hint.isNotEmpty() }
        val hidden = ArrayList<String>()
        chips.forEach { (name, slot) ->
            val next = chip(ui, x, r, name, slot, limit)
            if (next == x) hidden += name else x = next
        }
        if (snap.goal.isNotEmpty()) {
            val text = trJson(snap.goal) + if (snap.goalMax > 0) " ${snap.goalValue}/${snap.goalMax}" else ""
            if (x + Draw.width(text, TextStyle.CAPTION) <= limit) Draw.text(ui.g, text, x, r.centerY - 4, TextStyle.CAPTION) else hidden += text
        }
        if (hidden.isEmpty()) return
        Draw.textRight(ui.g, "+${hidden.size}", limit, r.centerY - 4, Palette.textMuted)
        ui.tooltip("topbar:more", Rect(limit - 24, r.y, 24, r.h), hidden.joinToString(", "))
    }

    fun slotLock(slot: Slot): Lock? =
        if (slot.used < slot.max || (slot.max <= 0 && slot.hint.isEmpty())) null else Lock(tr("kami_libs.format.used", slot.used, slot.max), slot.hint.takeIf { it.isNotEmpty() }?.let { trJson(it) })

    private fun chip(ui: Ui, x: Int, r: Rect, name: String, slot: Slot, limit: Int): Int {
        val lock = slotLock(slot)
        val text = "$name ${slot.used}/${slot.max}"
        val w = Draw.width(text) + 10 + if (lock != null) Draw.ICON - 1 else 0
        if (x + w > limit) return x
        if (lock != null) ui.chip(x, r.centerY - 6, text, Palette.warning, Icons.LOCK, lock.how, "slot:$name") else Draw.text(ui.g, text, x + 5, r.centerY - 4, Palette.textMuted)
        return x + w + 6
    }

    fun drops(): List<DropTarget> = if (route.page == "item") (page("item") as? ItemPage)?.drops().orEmpty() else emptyList()

    fun openItem(item: String, side: String? = null, kind: String = "instant") {
        val core = Variants.core(item)
        navigate(Route("item", buildMap {
            put("item", core)
            side?.let { put("side", it) }
            put("kind", kind)
            if (core != item) put("variant", item)
        }))
    }

    fun taxFor(relation: String) = if (relation == "allied" || relation == "family") snap.allyTaxPct else snap.taxPct

    fun countryTip(country: String, relation: String): String =
        if (relation == "embargo") tr("kami_libs.lock.embargo", country)
        else tr("kami_economy.market.country.tip", country, tr("kami_economy.market.rate.${relation.ifEmpty { "neutral" }}"), taxFor(relation))

    fun reprice(host: KamiApp, o: OrderRow) {
        val state = NumberState(o.price.toLong())
        host.open(Dialog(tr("kami_economy.reprice.action"), MarketSection.stack(o.item).hoverName.string, Icons.EDIT, DialogKind.CONFIRM) { s ->
            s.used = numberDialogBody(s, s.body.y, state, tr("kami_economy.market.price_lot", o.lot), 1, MAX_PRICE, help = tr("kami_economy.price.each", Format.unitMoney(state.value, o.lot)))
            dialogButtons(s, tr("kami_economy.reprice.confirm"), state.value != o.price.toLong()) {
                request(if (o.bid) "reprice_bid" else "reprice", o.item, state.value.toString())
                s.close()
            }
        })
    }

    fun cancel(host: KamiApp, o: OrderRow) = confirmDialog(
        host::open, tr("kami_economy.cancel.action"), MarketSection.stack(o.item).hoverName.string, Icons.CROSS,
        emptyList(),
        tr("kami_economy.cancel.confirm"), danger = true
    ) { request(if (o.bid) "cancel_bid" else "cancel", o.item) }

    override fun escape() = super.escape() || route.page == "item" && run { val before = route; back(); route !== before }

    override fun closed() {
        super.closed()
        request("close")
    }

    companion object {
        val instance by lazy { MarketApp() }
        val opening = OpenWait({ tr("kami_libs.common.loading") }, { tr("kami_economy.open.timeout") }, { tr("kami_libs.common.no_answer") })
        private const val SKIP_MS = 10_000L
        private const val IMPORT_RETRY_MS = 5_000L
        private var skipUntil = 0L

        private fun showing() = (Minecraft.getInstance().screen as? AppScreen)?.app === instance

        private fun show() {
            val app = instance
            Minecraft.getInstance().setScreen(AppScreen(app, Text.msg("kami_libs.common.market")))
            if (app.route.page.isNotEmpty()) app.page(app.route.page).opened(app.route)
        }

        fun open() {
            ClientHooks.request("open")
            if (!instance.loaded) return opening.start()
            skipUntil = System.currentTimeMillis() + SKIP_MS
            show()
        }

        fun receive(snap: Snap) {
            val showing = showing()
            instance.update(snap, showing)
            if (!snap.open) return
            opening.done()
            if (System.currentTimeMillis() < skipUntil) skipUntil = 0
            else if (!showing) show()
        }
    }
}
