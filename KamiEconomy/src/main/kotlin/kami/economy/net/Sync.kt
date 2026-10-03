package kami.economy.net

import kami.economy.Book
import kami.economy.Config
import kami.economy.Market
import kami.economy.Order
import kami.economy.Vendor
import kami.economy.economy.Auction
import kami.economy.economy.Auctions
import kami.economy.economy.Blacklist
import kami.economy.economy.Classification
import kami.economy.economy.History
import kami.economy.economy.Limits
import kami.economy.economy.Matching
import kami.economy.economy.Notify
import kami.economy.economy.Range
import kami.economy.economy.SellPlan
import kami.economy.economy.Stocks
import kami.economy.economy.Terms
import kami.economy.economy.Trade
import kami.libs.claims.ClaimsApi
import kami.libs.claims.CountryCapacity
import kami.libs.claims.FlagInfo
import kami.libs.economy.Numismatics
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.roundToInt

@Serializable class FlagView(val pattern: Int = 0, val emblem: Int = 0, val secondary: Int = 0xFFFFFF)
@Serializable class Row(
    val item: String, val lot: Int, val buy: Int, val sell: Int, val available: Int, val demand: Int,
    val last: Int, val change: Int, val volume: Int, val sold: Long, val bought: Long, val starter: Boolean,
    val vendorPrice: Int, val myAsk: Int, val myBid: Int, val infinite: Boolean, val left: Int
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
@Serializable class Level(
    val price: Int, val amount: Int, val market: Boolean = false,
    val country: String = "", val color: Int = 0, val flag: FlagView? = null, val relation: String = "", val mine: Boolean = false
)
@Serializable class StockLine(val room: Int, val capLeft: Int)
@Serializable class Candle(val open: Int, val high: Int, val low: Int, val close: Int, val volume: Int, val at: Long)
@Serializable class Chart(val start: Long, val open: Int, val candles: List<Candle>)
@Serializable class Leader(val name: String, val value: Long, val color: Int = 0, val flag: FlagView? = null)
@Serializable class Dash(
    val topSold: List<Row>, val topBought: List<Row>, val players: List<Leader>, val countries: List<Leader>,
    val items: Int, val asks: Int, val bids: Int, val auctions: Int, val traded: Long
)
@Serializable class Detail(
    val item: String, val lot: Int, val buy: Int, val sell: Int,
    val held: Int, val maxAffordable: Int, val last: Int, val change: Int, val sold: Long, val bought: Long, val chart: Chart,
    val stock: StockLine?, val asks: List<Level>, val bids: List<Level>, val mine: List<OrderRow>,
    val vendors: List<VendorLine>, val available: Int, val demand: Int, val infinite: Boolean, val stockBid: Int, val resetAt: Long, val maxAmount: Int
)
@Serializable class Quote(
    val mode: String, val item: String, val qty: Int, val price: Int,
    val filled: Int, val listed: Int, val returned: Int, val reason: String,
    val gross: Long, val tax: Long, val tariff: Long, val total: Long, val avg: Int, val worst: Int, val levels: Int,
    val listGross: Long, val listTax: Long, val step: Int, val taxPct: Int, val tariffPct: Int, val relation: String,
    val guaranteed: Int, val after: Int
)
@Serializable class AuctionLine(
    val id: Long, val label: String, val stackData: String, val startPrice: Int, val buyNowPrice: Int, val currentBid: Int,
    val currentBidder: String, val expiresAt: Long, val mine: Boolean, val sellerName: String
)
@Serializable class Slot(val used: Int = 0, val max: Int = 0, val hint: String = "")
@Serializable class Snap(
    val funds: Long, val view: String,
    val rows: List<Row> = emptyList(), val orders: List<OrderRow> = emptyList(), val vendors: List<VendorLine> = emptyList(),
    val held: List<HeldRow> = emptyList(), val auctions: List<AuctionLine> = emptyList(), val dash: Dash? = null,
    val mine: List<OrderRow>, val taxPct: Int, val allyTaxPct: Int, val auctionFeePct: Int,
    val detail: Detail?, val quote: Quote?, val msg: String, val ok: Boolean, val open: Boolean, val citizen: Boolean,
    val orderSlots: Slot, val auctionSlots: Slot, val goal: String, val goalValue: Long, val goalMax: Long, val truncated: Boolean,
    val muted: List<String> = emptyList()
)

private class QuoteReq(val mode: String, val item: String, val qty: Int, val price: Int)

private class State {
    var view = "dashboard"
    var query = ""
    var detailItem = ""
    var range = Range.WEEK
    var quote: QuoteReq? = null
}

object Sync {
    private const val DASH_TICKS = 200
    private const val AUCTION_ROWS = 60
    private val json = Json { encodeDefaults = true }
    private val states = HashMap<UUID, State>()
    private var dash: Dash? = null
    private var dashTick = 0

    fun view(p: ServerPlayer, view: String, query: String) {
        val s = states.getOrPut(p.uuid) { State() }
        s.view = view
        s.query = query
    }

    fun detail(p: ServerPlayer, item: String, range: String) {
        val s = states.getOrPut(p.uuid) { State() }
        s.detailItem = item
        s.range = Range.parse(range)
    }

    fun quote(p: ServerPlayer, mode: String, item: String, qty: Int, price: Int) {
        states.getOrPut(p.uuid) { State() }.quote = if (item.isEmpty() || qty <= 0) null else QuoteReq(mode, item, qty.coerceAtMost(Config.s.maxAmount), price)
    }

    fun forget(p: ServerPlayer) {
        states.remove(p.uuid)
    }

    private fun name(server: MinecraftServer, who: String): String =
        Trade.uuid(who)?.let { id -> server.playerList.getPlayer(id)?.name?.string ?: server.profileCache?.get(id)?.orElse(null)?.name } ?: "?"

    private fun flagView(f: FlagInfo) = FlagView(f.pattern, f.emblem, f.secondary)

    private fun matches(query: String, text: String) = query.isEmpty() || text.contains(query, ignoreCase = true)

    private fun row(item: String, me: String): Row {
        val lot = Config.s.lotOf(item)
        val book = Matching.bookFor(item)
        val stockBid = Matching.stockBid(me, item)
        val now = System.currentTimeMillis()
        val traded = Market.data.traded[item]
        val infinite = Stocks.infinite(item)
        return Row(
            item, lot, Matching.bestAsk(item, me) ?: 0, Matching.bestBidFor(item, me) ?: 0,
            Matching.available(item, me), Matching.openBids(item, me).sumOf { it.amount } + if (stockBid != null) Stocks.room(item, me) * lot else 0,
            book.lastFill, History.change(item, now), History.dayVolume(item, now).toInt(), traded?.sold ?: 0, traded?.bought ?: 0, Stocks.good(item) != null,
            Stocks.vendorPrice(item) ?: 0, book.sells.firstOrNull { it.owner == me }?.price ?: 0, book.buys.firstOrNull { it.owner == me }?.price ?: 0,
            infinite, if (infinite) Stocks.capLeft(me, item) * lot else 0
        )
    }

    private fun marketRows(query: String, me: String): List<Row> =
        (Market.data.books.keys + Stocks.items()).filter { matches(query, it) }.map { row(it, me) }.filter { it.buy > 0 || it.sell > 0 }
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

    private fun held(p: ServerPlayer, me: String, query: String): List<HeldRow> = inventory(p).filterKeys { matches(query, it) }.map { (item, count) ->
        val lot = Config.s.lotOf(item)
        val plan = Matching.sellPlan(me, item, (count - count % lot).coerceAtMost(Config.s.maxAmount)).takeIf { it.filled > 0 && it.net > 0 }
        val source = when {
            plan == null -> "none"
            plan.fills.any { it.market } -> "stock"
            else -> "bids"
        }
        HeldRow(item, count, lot, Matching.bestBidFor(item, me) ?: 0, plan?.net ?: 0, source, count.coerceAtMost(Config.s.maxAmount))
    }

    private fun levels(orders: List<Order>, me: String, relation: (String) -> Terms): List<Level> =
        orders.groupBy { it.price to Trade.country(it.owner) }.map { (key, list) ->
            val c = key.second?.let { ClaimsApi.country(it) }
            Level(key.first, list.sumOf { it.amount }, country = c?.name ?: "", color = c?.color ?: 0, flag = c?.flag?.let(::flagView),
                relation = relation(list.first().owner).relation.name.lowercase(), mine = list.any { it.owner == me })
        }

    private fun detail(p: ServerPlayer, s: State, me: String, funds: Long): Detail? {
        val item = s.detailItem
        if (item.isEmpty()) return null
        val r = row(item, me)
        val book = Matching.bookFor(item)
        val series = History.series(item, s.range, System.currentTimeMillis(), book.midPrice)
        val chart = Chart(series.start, series.open.roundToInt(), series.buckets.map { Candle(it.open.roundToInt(), it.high.roundToInt(), it.low.roundToInt(), it.close.roundToInt(), it.volume.toInt(), it.at) })
        val stockBid = Matching.stockBid(me, item)
        val stock = Stocks.good(item)?.let { StockLine(Stocks.room(item, me), Stocks.capLeft(me, item)) }
        val stockAsk = Stocks.ask(item)?.let { Level(it, (Stocks.stock(item)?.lots ?: 0) * r.lot, market = true) }
        val stockBidLevel = stockBid?.let { Level(it, Stocks.room(item, me) * r.lot, market = true) }
        val asks = (levels(book.sells.filter { it.amount > 0 }, me) { Trade.terms(me, it) } + listOfNotNull(stockAsk)).sortedBy { it.price }.take(5)
        val bids = (levels(Matching.bids(item), me) { Trade.terms(it, me) } + listOfNotNull(stockBidLevel)).sortedByDescending { it.price }.take(5)
        val vendors = Market.data.vendors.filter { it.item == item && it.price > 0 }.map { vendorLine(it, p.server, me) }.sortedBy { it.price }.take(50)
        return Detail(
            item, r.lot, r.buy, r.sell, inventory(p)[item] ?: 0, Matching.maxAffordable(item, funds, me), r.last, r.change, r.sold, r.bought,
            chart, stock, asks, bids, mine(p.server, me, listOf(book)), vendors,
            r.available, r.demand, r.infinite, stockBid ?: 0, Stocks.nextReset(), Config.s.maxAmount
        )
    }

    private fun auctions(me: String, query: String): List<Auction> =
        Auctions.open().filter { matches(query, it.label) }.sortedWith(compareByDescending<Auction> { it.seller == me }.thenBy { it.expiresAt })

    private fun auctionLine(server: MinecraftServer, me: String, a: Auction) =
        AuctionLine(a.id, a.label, a.stackData, a.startPrice, a.buyNowPrice ?: -1, a.currentBid, a.currentBidder, a.expiresAt, a.seller == me, name(server, a.seller))

    private fun dash(server: MinecraftServer): Dash {
        val cached = dash
        if (cached != null && server.tickCount - dashTick in 0 until DASH_TICKS) return cached
        val traded = Market.data.traded
        val books = Market.data.books.values
        val built = Dash(
            traded.entries.filter { it.value.sold > 0 }.sortedByDescending { it.value.sold }.take(5).map { row(it.key, "") },
            traded.entries.filter { it.value.bought > 0 }.sortedByDescending { it.value.bought }.take(5).map { row(it.key, "") },
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

    private fun avg(gross: Long, filled: Int, lot: Int): Int = if (filled > 0) (gross * lot / filled).toInt() else 0

    private fun relation(terms: List<Terms>): String = terms.map { it.relation.name.lowercase() }.distinct().let { if (it.size > 1) "mixed" else it.firstOrNull() ?: "" }

    private fun clamp(v: Long): Int = v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    private fun quote(me: String, funds: Long, r: QuoteReq): Quote? {
        val lot = Config.s.lotOf(r.item)
        val whole = r.qty - r.qty % lot
        fun lotReason(filled: Int) = when {
            filled < whole -> "liquidity"
            whole < r.qty -> "lot"
            else -> ""
        }
        return when (r.mode) {
            "buy" -> {
                val plan = Matching.plan(r.item, whole, me)
                val stock = plan.fills.filter { it.market }
                val terms = plan.fills.filterNot { it.market }.map { Trade.terms(me, it.owner) }
                val gross = plan.fills.sumOf { it.gross }
                Quote(
                    r.mode, r.item, r.qty, 0, plan.filled, 0, r.qty - plan.filled, lotReason(plan.filled),
                    gross, stock.sumOf { it.tax }, plan.fills.sumOf { it.tariff }, plan.totalSpurs, avg(gross, plan.filled, lot), plan.fills.maxOfOrNull { it.unitPrice } ?: 0, plan.fills.size,
                    0, 0, lot, if (stock.isEmpty()) 0 else Config.s.taxPct, terms.maxOfOrNull { it.tariffPct } ?: 0, relation(terms), 0, clamp(funds - plan.totalSpurs)
                )
            }
            "sell_market" -> {
                val plan = Matching.sellPlan(me, r.item, whole).takeIf { it.filled > 0 && it.net > 0 }
                sellQuote(me, funds, r, plan, 0, 0, lot, lotReason(plan?.filled ?: 0), 0, 0)
            }
            "sell" -> {
                val price = Matching.listPrice(me, r.item, r.price)
                val q = Matching.sellQuote(me, r.item, r.qty, price)
                val rest = whole - q.filled
                val reason = when {
                    rest > 0 && q.limited -> "slots"
                    rest > q.listed -> "step"
                    whole < r.qty -> "lot"
                    else -> ""
                }
                val listGross = q.listed.toLong() / lot * price
                sellQuote(me, funds, r, q.plan, price, q.listed, q.step, reason, listGross, Matching.tax(listGross))
            }
            "bid" -> {
                val step = Matching.step(r.price.coerceAtLeast(1)) * lot
                val amount = r.qty - r.qty % step
                val total = amount.toLong() / lot * r.price
                val refusal = when {
                    amount <= 0 -> "step"
                    Matching.bookFor(r.item).buys.any { it.owner == me } -> "exists"
                    Limits.marketFull(me) -> "slots"
                    Matching.effectiveSellPrice(r.item)?.let { it <= r.price } == true -> "crosses"
                    else -> null
                }
                val listed = if (refusal == null) amount else 0
                val reason = refusal ?: if (amount < r.qty) "step" else ""
                Quote(
                    r.mode, r.item, r.qty, r.price, 0, listed, r.qty - listed, reason, total, 0, 0, total, 0, 0, 0, 0, 0, step,
                    Config.s.taxPct, 0, "", 0, clamp(funds - total)
                )
            }
            else -> null
        }
    }

    private fun sellQuote(me: String, funds: Long, r: QuoteReq, plan: SellPlan?, price: Int, listed: Int, step: Int, reason: String, listGross: Long, listTax: Long): Quote {
        val lot = Config.s.lotOf(r.item)
        val fills = plan?.fills.orEmpty()
        val terms = fills.filterNot { it.market }.map { Trade.terms(it.owner, me) }
        val filled = plan?.filled ?: 0
        val total = plan?.net ?: 0L
        val taxPct = (terms.map { it.taxPct } + if (fills.any { it.market }) listOf(Config.s.taxPct) else emptyList()).maxOrNull() ?: Config.s.taxPct
        return Quote(
            r.mode, r.item, r.qty, price, filled, listed, r.qty - filled - listed, reason,
            plan?.gross ?: 0, plan?.tax ?: 0, plan?.tariff ?: 0, total, avg(plan?.gross ?: 0, filled, lot), fills.minOfOrNull { it.unitPrice } ?: 0, fills.size,
            listGross, listTax, step, taxPct, terms.maxOfOrNull { it.tariffPct } ?: 0, relation(terms), plan?.capLots ?: 0, clamp(funds + total)
        )
    }

    fun encode(p: ServerPlayer, msg: String, ok: Boolean, open: Boolean): String {
        val s = states.getOrPut(p.uuid) { State() }
        val me = p.stringUUID
        val funds = Numismatics.balance(p.uuid)
        val limit = Config.s.listLimit
        var truncated = false
        fun <T> List<T>.cap(max: Int = limit): List<T> {
            if (size > max) truncated = true
            return take(max)
        }
        val books = Market.data.books.values
        val goal = ClaimsApi.goals(p.uuid).firstOrNull()
        val snap = Snap(
            funds, s.view,
            rows =when (s.view) {
                "market" -> marketRows(s.query, me)
                "infinite" -> Stocks.items().filter { Stocks.infinite(it) && matches(s.query, it) }.map { row(it, me) }.sortedBy { it.item }
                else -> emptyList()
            }.cap(),
            orders = when (s.view) {
                "sell_orders" -> orderRows(p.server, me, books.filter { matches(s.query, it.item) }, bid = false)
                "buy_orders" -> orderRows(p.server, me, books.filter { matches(s.query, it.item) }, bid = true)
                else -> emptyList()
            }.sortedByDescending { it.placedAt }.cap(),
            vendors = if (s.view == "sell_vendors" || s.view == "buy_vendors") Market.data.vendors.filter { it.price > 0 && it.sell == (s.view == "buy_vendors") && matches(s.query, it.item) }.sortedWith(compareBy({ it.item }, { it.price })).cap().map { vendorLine(it, p.server, me) } else emptyList(),
            held = if (s.view == "instant") held(p, me, s.query).sortedBy { it.item }.cap() else emptyList(),
            auctions = if (s.view == "auctions") auctions(me, s.query).cap(minOf(limit, AUCTION_ROWS)).map { auctionLine(p.server, me, it) } else emptyList(),
            dash = if (s.view == "dashboard") dash(p.server) else null,
            mine = mine(p.server, me, books),
            taxPct = Config.s.taxPct, allyTaxPct = Config.s.allyTaxPct, auctionFeePct = (Config.s.auctionFeePct * 100).roundToInt(),
            detail = detail(p, s, me, funds), quote = s.quote?.let { quote(me, funds, it) },
            msg = msg, ok = ok, open = open, citizen = ClaimsApi.isCitizen(p.uuid),
            orderSlots = slot(me, CountryCapacity.MARKET_SLOTS, Limits.marketUsed(me), Limits.marketSlots(me)),
            auctionSlots = slot(me, CountryCapacity.AUCTION_SLOTS, Limits.auctionUsed(me), Limits.auctionSlots(me)),
            goal = goal?.text?.json() ?: "", goalValue = goal?.value ?: 0, goalMax = goal?.max ?: 0,
            truncated = truncated, muted = Notify.muted(me).toList()
        )
        return json.encodeToString(snap)
    }
}
