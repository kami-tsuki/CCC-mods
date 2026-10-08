package kami.economy.net

import kami.economy.Book
import kami.economy.Config
import kami.economy.Market
import kami.economy.Order
import kami.economy.Vendor
import kami.economy.economy.Auction
import kami.economy.economy.Auctions
import kami.economy.economy.Blacklist
import kami.economy.economy.BuyPlan
import kami.economy.economy.Classification
import kami.economy.economy.FillLinePlan
import kami.economy.economy.History
import kami.economy.economy.Limits
import kami.economy.economy.Matching
import kami.economy.economy.Notify
import kami.economy.economy.Range
import kami.economy.economy.SellPlan
import kami.economy.economy.StackCodec
import kami.economy.economy.Stocks
import kami.economy.economy.Terms
import kami.economy.economy.Trade
import kami.economy.economy.Variants
import kami.economy.economy.TradeFlag
import kami.economy.economy.Watchlist
import kami.economy.economy.TradeStatus
import kami.libs.admin.Subject
import kami.libs.claims.ClaimsApi
import kami.libs.claims.CountryCapacity
import kami.libs.claims.FlagInfo
import kami.libs.economy.Numismatics
import kami.libs.mc.ItemSpec
import kami.libs.text.SearchQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.roundToInt

@Serializable class FlagView(val pattern: Int = 0, val emblem: Int = 0, val secondary: Int = 0xFFFFFF)
@Serializable class VariantRow(val item: String, val mul: Int, val div: Int, val lot: Int, val buy: Int, val sell: Int)
@Serializable data class Row(
    val item: String, val lot: Int, val buy: Int, val sell: Int, val available: Int, val demand: Int,
    val last: Int, val change: Int, val volume: Int, val sold: Long, val bought: Long, val starter: Boolean,
    val vendorPrice: Int, val myAsk: Int, val myBid: Int, val infinite: Boolean, val left: Int,
    val status: String = "", val statusReason: String = "", val variants: List<VariantRow> = emptyList()
)
@Serializable class OrderRow(
    val id: Long, val item: String, val owner: String, val ownerName: String, val country: String,
    val color: Int, val flag: FlagView?, val relation: String, val price: Int, val amount: Int, val lot: Int,
    val placedAt: Long, val bid: Boolean, val mine: Boolean, val best: Boolean
)
@Serializable class VendorLine(
    val item: String, val owner: String, val country: String, val color: Int, val flag: FlagView?,
    val dim: String, val x: Int, val y: Int, val z: Int, val price: Int, val sell: Boolean, val stock: Int, val embargo: Boolean
)
@Serializable class HeldRow(val item: String, val held: Int, val lot: Int, val sell: Int, val net: Long, val source: String, val cap: Int)
@Serializable data class Level(
    val price: Int, val amount: Int, val market: Boolean = false,
    val country: String = "", val color: Int = 0, val flag: FlagView? = null, val relation: String = "", val mine: Boolean = false, val tariff: Int = 0
)
@Serializable class TariffLine(val country: String, val pct: Int, val amount: Long)
@Serializable class Candle(val open: Int, val high: Int, val low: Int, val close: Int, val volume: Int, val at: Long)
@Serializable class Chart(val start: Long, val open: Int, val candles: List<Candle>)
@Serializable class Leader(val name: String, val value: Long, val color: Int = 0, val flag: FlagView? = null)
@Serializable class Dash(
    val topSold: List<Row>, val topBought: List<Row>, val players: List<Leader>, val countries: List<Leader>,
    val items: Int, val asks: Int, val bids: Int, val auctions: Int, val traded: Long
)
@Serializable class Detail(
    val item: String, val lot: Int, val buy: Int, val sell: Int,
    val held: Int, val maxAffordable: Int, val last: Int, val sold: Long, val bought: Long, val chart: Chart,
    val asks: List<Level>, val bids: List<Level>, val mine: List<OrderRow>,
    val vendors: List<VendorLine>, val available: Int, val demand: Int, val infinite: Boolean, val maxAmount: Int,
    val course: Double, val spread: Double, val capLeft: Int, val buyServer: Boolean, val sellServer: Boolean, val followable: Boolean,
    val status: String = "", val statusReason: String = "", val variants: List<VariantRow> = emptyList()
)
@Serializable data class Quote(
    val mode: String, val item: String, val qty: Int, val price: Int,
    val filled: Int, val listed: Int, val returned: Int, val reason: String,
    val gross: Long, val tax: Long, val tariff: Long, val total: Long, val avg: Int, val worst: Int, val levels: Int,
    val listGross: Long, val listTax: Long, val step: Int, val taxPct: Int, val tariffPct: Int, val relation: String,
    val after: Int, val otherTax: Long = 0, val otherTaxPct: Int = 0, val tariffs: List<TariffLine> = emptyList(), val leftover: Int = 0
)
@Serializable class BidLine(val name: String, val amount: Int, val at: Long)
@Serializable class AuctionLine(
    val id: Long, val label: String, val stackData: String, val startPrice: Int, val buyNowPrice: Int, val currentBid: Int,
    val currentBidder: String, val expiresAt: Long, val mine: Boolean, val sellerName: String,
    val minBid: Int, val bids: List<BidLine>, val status: String = "", val statusReason: String = ""
)
@Serializable class Slot(val used: Int = 0, val max: Int = 0, val hint: String = "")
@Serializable class Snap(
    val funds: Long, val view: String,
    val rows: List<Row> = emptyList(), val orders: List<OrderRow> = emptyList(), val vendors: List<VendorLine> = emptyList(),
    val held: List<HeldRow> = emptyList(), val auctions: List<AuctionLine> = emptyList(), val dash: Dash? = null,
    val mine: List<OrderRow>, val taxPct: Int, val allyTaxPct: Int, val auctionFeePct: Int,
    val detail: Detail?, val quote: Quote?, val msg: String, val ok: Boolean, val open: Boolean, val citizen: Boolean,
    val orderSlots: Slot, val auctionSlots: Slot, val goal: String, val goalValue: Long, val goalMax: Long, val truncated: Boolean,
    val muted: List<String> = emptyList(), val subject: String = "", val subjectOrders: List<OrderRow> = emptyList(),
    val watch: List<String> = emptyList(), val watchRows: List<Row> = emptyList()
)


private class State {
    var view = "dashboard"
    var query = ""
    var search = SearchQuery.EMPTY
    var ids: Set<String>? = null
    var detailItem = ""
    var range = Range.WEEK
    var quote: QuoteReq? = null
    var subject: UUID? = null
}

object Sync {
    private const val DASH_TICKS = 200
    private const val AUCTION_ROWS = 60
    private val json = Json { encodeDefaults = true }
    private val states = HashMap<UUID, State>()
    private var dash: Dash? = null
    private var dashTick = 0

    fun view(p: ServerPlayer, view: String, query: String, ids: String = "", subject: String = "") {
        val s = states.getOrPut(p.uuid) { State() }
        s.subject = subject.takeIf { it.isNotBlank() }?.let { Subject.resolve(p, it) }
        s.view = view
        s.query = query
        s.search = SearchQuery.parse(query)
        s.ids = if (query.isBlank() || ids == "*" || s.search.isEmpty) null else ids.split(',').filter(String::isNotBlank).toSet()
    }

    fun detail(p: ServerPlayer, item: String, range: String) {
        val s = states.getOrPut(p.uuid) { State() }
        s.detailItem = item
        s.range = Range.parse(range)
    }

    fun quote(p: ServerPlayer, mode: String, item: String, qty: Int, price: Int) {
        states.getOrPut(p.uuid) { State() }.quote = if (item.isEmpty() || qty <= 0) null else QuoteReq(mode, item, qty.coerceAtMost(Config.s.maxAmount), price)
    }

    fun subject(p: ServerPlayer): UUID? = states[p.uuid]?.subject

    fun forget(p: ServerPlayer) {
        states.remove(p.uuid)
    }

    private fun name(server: MinecraftServer, who: String): String =
        Trade.uuid(who)?.let { id -> server.playerList.getPlayer(id)?.name?.string ?: server.profileCache?.get(id)?.orElse(null)?.name } ?: "?"

    private fun flagView(f: FlagInfo) = FlagView(f.pattern, f.emblem, f.secondary)

    private fun State.hit(item: String): Boolean {
        val set = ids ?: return true
        return item in set || Variants.core(item) in set || Variants.family(item).any { it.first in set }
    }

    private fun unlimited(v: Int) = if (v == Int.MAX_VALUE) -1 else v

    private var vendorPrices = emptyMap<String, Int>()

    private fun row(item: String, me: String): Row {
        val lot = Config.s.lotOf(item)
        val book = Matching.bookFor(item)
        val now = System.currentTimeMillis()
        val traded = Market.data.traded[item]
        val status = TradeStatus.of(item)
        val buy = Matching.bestAsk(item, me) ?: 0
        val sell = Matching.bestBidFor(item, me) ?: 0
        return Row(
            item, lot, buy, sell,
            Matching.available(item, me), Matching.demand(item, me),
            book.lastFill, History.change(item, now), History.dayVolume(item, now).toInt(), traded?.sold ?: 0, traded?.bought ?: 0, Stocks.good(item) != null,
            vendorPrices[item] ?: 0, book.sells.firstOrNull { it.owner == me }?.price ?: 0, book.buys.firstOrNull { it.owner == me }?.price ?: 0,
            Stocks.infiniteDemand(item), unlimited(Stocks.capLeft(me, item)), status?.let { TradeFlag.csv(it.flags) }.orEmpty(), status?.reason.orEmpty(), variantRows(item, lot, buy, sell)
        )
    }

    private fun variantRows(core: String, lot: Int, buy: Int, sell: Int): List<VariantRow> = Variants.family(core).map { (id, v) ->
        VariantRow(id, v.mul, v.div, Variants.lot(id, lot), scaled(id, buy, lot), scaled(id, sell, lot))
    }

    private fun scaled(id: String, price: Int, lot: Int): Int = if (price <= 0) 0 else clamp(Variants.scale(id, price.toLong(), lot))

    private fun marketRows(s: State, me: String): List<Row> =
        (Market.data.books.keys + Market.data.traded.keys + Stocks.items()).filter { Variants.of(it) == null && s.hit(it) }.map { row(it, me) }
            .filter { it.buy > 0 || it.sell > 0 || it.sold + it.bought > 0 || it.last > 0 || it.status.isNotEmpty() }
            .sortedWith(compareByDescending<Row> { it.sold + it.bought }.thenBy { it.item })

    private fun orderRows(server: MinecraftServer, me: String, books: Collection<Book>, bid: Boolean, mineOnly: Boolean = false): List<OrderRow> = books.flatMap { b ->
        val side = (if (bid) b.buys else b.sells).filter { it.amount > 0 }
        val best = if (bid) side.maxOfOrNull { it.price } else side.minOfOrNull { it.price }
        side.filter { !mineOnly || it.owner == me }.groupBy { it.owner }.map { (owner, list) ->
            val c = Trade.country(owner)?.let { ClaimsApi.country(it) }
            val first = list.first()
            val terms = if (bid) Trade.terms(owner, me) else Trade.terms(me, owner)
            OrderRow(
                first.id, b.item, owner, name(server, owner), c?.name ?: "", c?.color ?: 0, c?.flag?.let(::flagView), terms.relation.name.lowercase(),
                first.price, list.sumOf { it.amount }, first.lot, list.minOf { it.placedAt }, bid, owner == me, first.price == best
            )
        }
    }

    private fun mine(server: MinecraftServer, me: String, books: Collection<Book>): List<OrderRow> =
        orderRows(server, me, books, bid = false, mineOnly = true) + orderRows(server, me, books, bid = true, mineOnly = true)

    private fun vendorLine(v: Vendor, server: MinecraftServer, me: String): VendorLine {
        val c = ClaimsApi.country(v.country)
        val embargo = Trade.country(me)?.let { !ClaimsApi.canTrade(it, v.country) } ?: false
        val price = (v.price.toLong() * Config.s.lotOf(v.item) / v.count.coerceAtLeast(1).toDouble()).roundToInt()
        return VendorLine(
            v.item, if (v.owner.isEmpty()) "" else name(server, v.owner), c?.name ?: v.country, c?.color ?: 0, c?.flag?.let(::flagView),
            v.dim, v.x, v.y, v.z, price, v.sell, v.stock, embargo
        )
    }

    private fun inventory(p: ServerPlayer): Map<String, Int> =
        p.inventory.items.filter { !it.isEmpty && Blacklist.classify(it) == Classification.ALLOWED }
            .groupBy { Blacklist.itemId(it) }.mapValues { (_, stacks) -> stacks.sumOf { it.count } }

    private fun held(p: ServerPlayer, me: String, s: State): List<HeldRow> = inventory(p).filterKeys { s.hit(it) }.mapNotNull { (item, count) ->
        val core = Variants.core(item)
        val coreLot = Config.s.lotOf(core)
        val lot = if (core == item) coreLot else Variants.lot(item, coreLot)
        val capped = count.coerceAtMost(Config.s.maxAmount)
        val whole = capped - capped % lot
        val coreWhole = if (core == item) whole else Variants.coreQty(item, whole) ?: return@mapNotNull null
        val bid = Matching.bestBidFor(core, me) ?: 0
        val plan = Matching.sellPlan(core, coreWhole, me).takeIf { it.filled > 0 && it.net > 0 }
        val source = when {
            plan == null -> "none"
            plan.fills.any { it.market } -> "stock"
            else -> "bids"
        }
        HeldRow(item, count, lot, bid.let { if (core == item) it else scaled(item, it, coreLot) }, plan?.net ?: 0, source, count.coerceAtMost(Config.s.maxAmount))
    }

    private fun levels(orders: List<Order>, me: String, relation: (String) -> Terms): List<Level> =
        orders.groupBy { it.price to Trade.country(it.owner) }.map { (key, list) ->
            val c = key.second?.let { ClaimsApi.country(it) }
            val terms = relation(list.first().owner)
            Level(key.first, list.sumOf { it.amount }, country = c?.name ?: "", color = c?.color ?: 0, flag = c?.flag?.let(::flagView),
                relation = terms.relation.name.lowercase(), mine = list.any { it.owner == me }, tariff = terms.tariffPct)
        }

    private fun detail(p: ServerPlayer, s: State, me: String, funds: Long): Detail? {
        val item = s.detailItem
        if (item.isEmpty()) return null
        val r = row(item, me)
        val book = Matching.bookFor(item)
        val series = History.series(item, s.range, System.currentTimeMillis(), book.course)
        val chart = Chart(series.start, series.open.roundToInt(), series.buckets.map { Candle(it.open.roundToInt(), it.high.roundToInt(), it.low.roundToInt(), it.close.roundToInt(), it.volume.toInt(), it.at) })
        val supply = unlimited(Stocks.supplyLeft(item))
        val demandLeft = unlimited(Stocks.demandLeft(item, me))
        val stockAsk = Matching.stockAsk(item)?.let { Level(it, if (supply < 0) -1 else clamp(supply.toLong() * r.lot), market = true) }
        val stockBid = Stocks.bid(item, me)?.let { Level(it, if (demandLeft < 0) -1 else clamp(demandLeft.toLong() * r.lot), market = true) }
        val playerAsks = levels(book.sells.filter { it.amount > 0 }, me) { Trade.terms(me, it) }
        val playerBids = levels(Matching.bids(item), me) { Trade.terms(it, me) }
        val asks = (playerAsks + listOfNotNull(stockAsk)).sortedBy { it.price }.take(5)
        val bids = (playerBids + listOfNotNull(stockBid)).sortedByDescending { it.price }.take(5)
        val serverTax = Config.s.taxPct / 100.0
        val bestPlayerAsk = book.sells.filter { it.amount > 0 && it.owner != me }.map { it.price * (1 + Trade.terms(me, it.owner).tariffPct / 100.0) to Trade.terms(me, it.owner) }
            .filter { !it.second.blocked }.minOfOrNull { it.first }
        val bestPlayerBid = Matching.openBids(item, me).maxOfOrNull { val t = Trade.terms(it.owner, me); it.price * (1 - (t.taxPct + t.tariffPct) / 100.0) }
        val buyServer = stockAsk != null && (bestPlayerAsk == null || stockAsk.price * (1 + serverTax) < bestPlayerAsk)
        val sellServer = stockBid != null && (bestPlayerBid == null || stockBid.price * (1 - serverTax) > bestPlayerBid)
        val vendors = Market.data.vendors.filter { it.item == item && it.price > 0 }.map { vendorLine(it, p.server, me) }.sortedBy { it.price }.take(50)
        return Detail(
            item, r.lot, r.buy, r.sell, inventory(p)[item] ?: 0, Matching.maxAffordable(item, funds, me), r.last, r.sold, r.bought,
            chart, asks, bids, mine(p.server, me, listOf(book)), vendors,
            r.available, r.demand, r.infinite, Config.s.maxAmount,
            Stocks.course(item), Stocks.spread(item), r.left, buyServer, sellServer, Stocks.good(item) != null, r.status, r.statusReason, r.variants
        )
    }

    private val auctionSpecs = HashMap<Long, String>()

    private fun auctionSpec(server: MinecraftServer, a: Auction) = auctionSpecs.getOrPut(a.id) {
        runCatching { ItemSpec.spec(StackCodec.decode(a.stackData, server.registryAccess())) }.getOrDefault("")
    }

    private fun auctions(server: MinecraftServer, me: String, s: State): List<Auction> =
        Auctions.open().also { live -> auctionSpecs.keys.retainAll(live.map(Auction::id).toSet()) }.filter { s.search.isEmpty || s.hit(auctionSpec(server, it)) || s.search.hasWords && s.search.matches(it.label) }.sortedWith(compareByDescending<Auction> { it.seller == me }.thenBy { it.expiresAt })

    private fun auctionLine(server: MinecraftServer, me: String, a: Auction): AuctionLine {
        val status = TradeStatus.of(auctionSpec(server, a))
        return AuctionLine(
            a.id, a.label, a.stackData, a.startPrice, a.buyNowPrice ?: -1, a.currentBid, a.currentBidder, a.expiresAt, a.seller == me, name(server, a.seller),
            Auctions.minBid(a), a.bids.takeLast(Auctions.BID_HISTORY).map { BidLine(name(server, it.bidder), it.amount, it.at) },
            status?.let { TradeFlag.csv(it.flags) }.orEmpty(), status?.reason.orEmpty()
        )
    }

    private fun dash(server: MinecraftServer): Dash {
        val cached = dash
        if (cached != null && server.tickCount - dashTick in 0 until DASH_TICKS) return cached
        val traded = Market.data.traded
        val books = Market.data.books.values
        val built = Dash(
            traded.entries.filter { Variants.of(it.key) == null && it.value.sold > 0 }.sortedByDescending { it.value.sold }.take(5).map { row(it.key, "") },
            traded.entries.filter { Variants.of(it.key) == null && it.value.bought > 0 }.sortedByDescending { it.value.bought }.take(5).map { row(it.key, "") },
            Numismatics.richest(3).map { (id, balance) -> Leader(name(server, id.toString()), balance) },
            ClaimsApi.countries().filter { it.treasury > 0 }.sortedByDescending { it.treasury }.take(3).map { Leader(it.name, it.treasury, it.color, flagView(it.flag)) },
            traded.values.count { it.sold + it.bought > 0 }, books.sumOf { b -> b.sells.count { it.amount > 0 } }, books.sumOf { b -> b.buys.count { it.amount > 0 } },
            Auctions.open().size, traded.values.sumOf { it.sold + it.bought }
        )
        dash = built
        dashTick = server.tickCount
        return built
    }

    private fun slot(me: String, key: String, used: Int, max: Int) = Slot(used, max, if (used >= max) Limits.hint(me, key, used) else "")

    fun encode(p: ServerPlayer, msg: String, ok: Boolean, open: Boolean): String {
        val s = states.getOrPut(p.uuid) { State() }
        val me = p.stringUUID
        vendorPrices = Stocks.vendorPrices()
        val funds = Numismatics.balance(p.uuid)
        val limit = Config.s.listLimit
        var truncated = false
        fun <T> List<T>.cap(max: Int = limit): List<T> {
            if (size > max) truncated = true
            return take(max)
        }
        val books = Market.data.books.values
        val goal = ClaimsApi.goals(p.uuid).firstOrNull()
        val watch = Watchlist.of(me)
        val snap = Snap(
            funds, s.view,
            rows =when (s.view) {
                "market" -> marketRows(s, me)
                "infinite" -> Stocks.items().filter { Variants.of(it) == null && Stocks.infiniteDemand(it) && s.hit(it) }.map { row(it, me) }.sortedBy { it.item }
                else -> emptyList()
            }.cap(),
            orders = when (s.view) {
                "market" -> if (s.search.isEmpty) emptyList() else (orderRows(p.server, me, books, bid = false) + orderRows(p.server, me, books, bid = true)).filter {
                    s.hit(it.item) || s.search.hasWords && s.search.matches(it.ownerName, it.country)
                }
                "sell_orders" -> orderRows(p.server, me, books.filter { s.hit(it.item) }, bid = false)
                "buy_orders" -> orderRows(p.server, me, books.filter { s.hit(it.item) }, bid = true)
                else -> emptyList()
            }.sortedByDescending { it.placedAt }.cap(),
            vendors = if (s.view == "sell_vendors" || s.view == "buy_vendors") Market.data.vendors.filter { it.price > 0 && it.sell == (s.view == "buy_vendors") && s.hit(it.item) }.sortedWith(compareBy({ it.item }, { it.price })).cap().map { vendorLine(it, p.server, me) } else emptyList(),
            held = if (s.view == "instant") held(p, me, s).sortedBy { it.item }.cap() else emptyList(),
            auctions = if (s.view == "auctions") auctions(p.server, me, s).cap(minOf(limit, AUCTION_ROWS)).map { auctionLine(p.server, me, it) } else emptyList(),
            dash = if (s.view == "dashboard") dash(p.server) else null,
            mine = mine(p.server, me, books),
            taxPct = Config.s.taxPct, allyTaxPct = Config.s.allyTaxPct, auctionFeePct = (Config.s.auctionFeePct * 100).roundToInt(),
            detail = detail(p, s, me, funds), quote = s.quote?.let { Quotes.of(me, funds, it) },
            msg = msg, ok = ok, open = open, citizen = ClaimsApi.isCitizen(p.uuid),
            orderSlots = slot(me, CountryCapacity.MARKET_SLOTS, Limits.marketUsed(me), Limits.marketSlots(me)),
            auctionSlots = slot(me, CountryCapacity.AUCTION_SLOTS, Limits.auctionUsed(me), Limits.auctionSlots(me)),
            goal = goal?.text?.json() ?: "", goalValue = goal?.value ?: 0, goalMax = goal?.max ?: 0,
            truncated = truncated, muted = Notify.muted(me).toList(),
            subject = s.subject?.toString().orEmpty(), subjectOrders = s.subject?.let { mine(p.server, it.toString(), books) }.orEmpty(),
            watch = watch.toList(),
            watchRows = if (s.view == "watch" || s.view == "dashboard") watch.filter { Variants.of(it) == null && (Stocks.good(it) != null || Blacklist.sellable(it)) }.map { row(it, me) } else emptyList()
        )
        return json.encodeToString(snap)
    }
}
