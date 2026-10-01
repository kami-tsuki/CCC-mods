package kami.economy.net

import kami.economy.Config
import kami.economy.Market
import kami.economy.Order
import kami.economy.economy.Auctions
import kami.economy.economy.History
import kami.economy.economy.Limits
import kami.economy.economy.Matching
import kami.economy.economy.Resolution
import kami.economy.economy.Stocks
import kami.economy.economy.Terms
import kami.economy.economy.Trade
import kami.libs.economy.Numismatics
import kami.libs.claims.ClaimsApi
import kami.libs.claims.CountryCapacity
import kami.libs.claims.FlagInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.roundToInt

@Serializable class Row(val item: String, val price: Int, val available: Int)
@Serializable class OrderLine(val item: String, val price: Int, val amount: Int, val bid: Boolean = false)
@Serializable class FlagView(val pattern: Int = 0, val emblem: Int = 0, val secondary: Int = 0xFFFFFF)
@Serializable class Level(
    val price: Int, val amount: Int, val market: Boolean = false,
    val country: String = "", val color: Int = 0, val flag: FlagView? = null, val relation: String = ""
)
@Serializable class QuoteLine(
    val item: String, val qty: Int, val filled: Int, val total: Long, val avg: Int, val tax: Long = 0,
    val buyerTax: Long = 0, val stock: Int = 0, val market: Boolean = false, val guaranteed: Int = 0, val after: Int = 0,
    val taxPct: Int = 0, val relation: String = "", val tariff: Long = 0, val tariffPct: Int = 0
)
@Serializable class StockLine(val price: Int, val lots: Int, val capLeft: Int, val cap: Int, val step: Int, val room: Int = 0)
@Serializable class Candle(val open: Int, val high: Int, val low: Int, val close: Int, val volume: Int, val at: Long)
@Serializable class VendorLine(
    val country: String, val color: Int, val dim: String, val x: Int, val y: Int, val z: Int, val price: Int, val sell: Boolean, val stock: Int,
    val flag: FlagView? = null, val embargo: Boolean = false
)
@Serializable class Detail(
    val item: String, val price: Int, val available: Int,
    val myOrderPrice: Int, val myOrderAmount: Int, val maxAffordable: Int, val history: List<Candle>, val lot: Int = 1,
    val buyStep: Int = 1, val stock: StockLine? = null,
    val asks: List<Level> = emptyList(), val bids: List<Level> = emptyList(), val myBidPrice: Int = 0, val myBidAmount: Int = 0,
    val vendors: List<VendorLine> = emptyList(), val vendorPrice: Int = 0
)
@Serializable class AuctionLine(val id: Long, val label: String, val stackData: String, val startPrice: Int, val buyNowPrice: Int, val currentBid: Int, val currentBidder: String, val expiresAt: Long, val mine: Boolean)
@Serializable class Slot(val used: Int = 0, val max: Int = 0, val hint: String = "")
@Serializable class Snap(
    val funds: Long, val rows: List<Row>, val orders: List<OrderLine>, val query: String, val sort: String,
    val page: Int, val pages: Int, val taxPct: Int, val auctionFeePct: Int, val detail: Detail?, val quote: QuoteLine?,
    val auctions: List<AuctionLine>, val auctionPage: Int, val auctionPages: Int,
    val msg: String, val ok: Boolean, val open: Boolean, val citizen: Boolean = true,
    val orderSlots: Slot = Slot(), val auctionSlots: Slot = Slot(), val goal: String = "", val goalValue: Long = 0, val goalMax: Long = 0
)

private class State {
    var text = ""
    var sort = "name"
    var page = 0
    var detailItem = ""
    var historyRes = Resolution.RAW
    var quote: QuoteLine? = null
    var auctionPage = 0
}

object Sync {
    private val json = Json { encodeDefaults = true }
    private val states = HashMap<UUID, State>()

    fun search(p: ServerPlayer, text: String, sort: String, page: Int) {
        val s = states.getOrPut(p.uuid) { State() }
        s.text = text
        s.sort = sort
        s.page = page.coerceAtLeast(0)
    }

    fun detail(p: ServerPlayer, item: String, resolution: String) {
        val s = states.getOrPut(p.uuid) { State() }
        s.detailItem = item
        s.historyRes = Resolution.parse(resolution)
    }

    fun quote(p: ServerPlayer, item: String, qty: Int, market: Boolean = false) {
        val s = states.getOrPut(p.uuid) { State() }
        if (item.isEmpty() || qty <= 0) { s.quote = null; return }
        if (market) {
            val lot = Config.s.lotOf(item)
            val plan = Matching.sellPlan(p.stringUUID, item, qty.coerceAtMost(Config.s.maxAmount) / lot * lot)
            val lots = plan.filled / lot
            s.quote = QuoteLine(item, qty, plan.filled, plan.net, if (lots > 0) (plan.gross / lots).toInt() else 0, plan.tax, market = true, guaranteed = plan.capLots, after = plan.after)
            return
        }
        val plan = Matching.plan(item, qty.coerceAtMost(Config.s.maxAmount), p.stringUUID)
        val avg = if (plan.filled > 0) (plan.totalSpurs.toDouble() * Config.s.lotOf(item) / plan.filled).roundToInt() else 0
        val stock = plan.fills.filter { it.market }
        val terms = plan.fills.filterNot { it.market }.map { Trade.terms(p.stringUUID, it.owner) }
        val taxPct = terms.map { it.taxPct }.distinct().singleOrNull() ?: 0
        val relation = terms.map { it.relation.name.lowercase() }.distinct().let { if (it.size > 1) "mixed" else it.firstOrNull() ?: "" }
        s.quote = QuoteLine(item, qty, plan.filled, plan.totalSpurs, avg, plan.fills.sumOf { it.tax }, stock.sumOf { it.tax }, stock.sumOf { it.qty },
            taxPct = taxPct, relation = relation, tariff = plan.fills.sumOf { it.tariff }, tariffPct = terms.maxOfOrNull { it.tariffPct } ?: 0)
    }

    fun auctionPage(p: ServerPlayer, page: Int) {
        states.getOrPut(p.uuid) { State() }.auctionPage = page.coerceAtLeast(0)
    }

    fun forget(p: ServerPlayer) {
        states.remove(p.uuid)
    }

    private fun rows(s: State): Pair<List<Row>, Int> {
        val all = (Market.data.books.keys + Stocks.items())
            .filter { it.contains(s.text, ignoreCase = true) }
            .map { Row(it, Matching.effectiveSellPrice(it) ?: 0, Matching.available(it)) }
            .filter { it.available > 0 || Matching.bestBid(it.item) != null }
        val sorted = when (s.sort) {
            "price" -> all.sortedBy { it.price }
            "stock" -> all.sortedByDescending { it.available }
            else -> all.sortedBy { it.item }
        }
        val pageSize = Config.s.pageSize
        val pages = ((sorted.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = s.page.coerceIn(0, pages - 1)
        return sorted.drop(page * pageSize).take(pageSize) to pages
    }

    private fun myOrders(me: String): List<OrderLine> =
        Market.data.books.values.mapNotNull { b ->
            val mine = b.sells.filter { it.owner == me }
            if (mine.isEmpty()) null else OrderLine(b.item, mine.first().price, mine.sumOf { it.amount })
        } + Market.data.books.values.flatMap { b -> b.buys.filter { it.owner == me }.map { OrderLine(b.item, it.price, it.amount, bid = true) } }

    private fun levels(orders: List<Order>, relation: (String) -> Terms): List<Level> =
        orders.groupBy { it.price to Trade.country(it.owner) }.map { (key, list) ->
            val c = key.second?.let { ClaimsApi.country(it) }
            Level(key.first, list.sumOf { it.amount }, country = c?.name ?: "", color = c?.color ?: 0, flag = c?.flag?.let(::flagView),
                relation = relation(list.first().owner).relation.name.lowercase())
        }

    private fun flagView(f: FlagInfo) = FlagView(f.pattern, f.emblem, f.secondary)

    private fun detail(s: State, me: String, funds: Long): Detail? {
        if (s.detailItem.isEmpty()) return null
        val history = History.of(s.detailItem, s.historyRes, 120)
            .map { Candle(it.open.roundToInt(), it.high.roundToInt(), it.low.roundToInt(), it.close.roundToInt(), it.volume.toInt(), it.at) }
        val mine = Matching.bookFor(s.detailItem).sells.filter { it.owner == me }
        val myPrice = mine.firstOrNull()?.price ?: 0
        val myAmount = mine.sumOf { it.amount }
        val maxAffordable = Matching.maxAffordable(s.detailItem, funds, me)
        val item = s.detailItem
        val lot = Config.s.lotOf(item)
        val price = Matching.effectiveSellPrice(item) ?: 0
        val ask = Stocks.ask(item)
        val stock = Stocks.stock(item)?.let { StockLine(Stocks.price(item).roundToInt(), it.lots, Stocks.capLeft(me, item), Stocks.cap(item), Stocks.step(item) * lot, Stocks.room(item)) }
        val buyStep = if (stock != null && ask != null && ask <= price) stock.step else Matching.step(price.coerceAtLeast(1)) * lot
        val book = Matching.bookFor(item)
        val stockAsk = ask?.let { Level(it, (stock?.lots ?: 0) * lot, market = true) }
        val stockBid = Stocks.bid(me, item)?.let { Level(it, Stocks.room(item) * lot, market = true) }
        val asks = (levels(book.sells.filter { it.amount > 0 }) { Trade.terms(me, it) } + listOfNotNull(stockAsk)).sortedBy { it.price }.take(5)
        val bids = (levels(Matching.bids(item)) { Trade.terms(it, me) } + listOfNotNull(stockBid)).sortedByDescending { it.price }.take(5)
        val myBid = book.buys.firstOrNull { it.owner == me }
        return Detail(item, price, Matching.available(item), myPrice, myAmount, maxAffordable, history, lot, buyStep, stock, asks, bids, myBid?.price ?: 0, myBid?.amount ?: 0,
            vendors(item, lot, me), Stocks.vendorPrice(item) ?: 0)
    }

    private fun vendors(item: String, lot: Int, me: String): List<VendorLine> = Market.data.vendors.filter { it.item == item && it.price > 0 }.map { v ->
        val c = ClaimsApi.country(v.country)
        val embargo = Trade.country(me)?.let { !ClaimsApi.canTrade(it, v.country) } ?: false
        VendorLine(c?.name ?: v.country, c?.color ?: 0, v.dim, v.x, v.y, v.z, (v.price.toLong() * lot / v.count.coerceAtLeast(1).toDouble()).roundToInt(), v.sell, v.stock,
            c?.flag?.let(::flagView), embargo)
    }.sortedBy { it.price }.take(50)

    private fun auctions(s: State, me: String): Triple<List<AuctionLine>, Int, Int> {
        val all = Auctions.open().sortedBy { it.expiresAt }
            .map { AuctionLine(it.id, it.label, it.stackData, it.startPrice, it.buyNowPrice ?: -1, it.currentBid, it.currentBidder, it.expiresAt, it.seller == me) }
        val pageSize = Config.s.pageSize
        val pages = ((all.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = s.auctionPage.coerceIn(0, pages - 1)
        return Triple(all.drop(page * pageSize).take(pageSize), page, pages)
    }

    private fun slot(me: String, key: String, used: Int, max: Int) = Slot(used, max, if (used >= max) Limits.hint(me, key, max) else "")

    fun encode(p: ServerPlayer, msg: String, ok: Boolean, open: Boolean): String {
        val s = states.getOrPut(p.uuid) { State() }
        s.quote?.let { quote(p, it.item, it.qty, it.market) }
        val (rowList, pages) = rows(s)
        val (auctionList, auctionPage, auctionPages) = auctions(s, p.stringUUID)
        val funds = Numismatics.balance(p.uuid)
        val me = p.stringUUID
        val goal = ClaimsApi.goals(p.uuid).firstOrNull()
        val snap = Snap(
            funds, rowList, myOrders(p.stringUUID), s.text, s.sort, s.page, pages,
            Config.s.taxPct, (Config.s.auctionFeePct * 100).roundToInt(),
            detail(s, p.stringUUID, funds), s.quote, auctionList, auctionPage, auctionPages, msg, ok, open, ClaimsApi.isCitizen(p.uuid),
            slot(me, CountryCapacity.MARKET_SLOTS, Limits.marketUsed(me), Limits.marketSlots(me)),
            slot(me, CountryCapacity.AUCTION_SLOTS, Limits.auctionUsed(me), Limits.auctionSlots(me)),
            goal?.text?.json() ?: "", goal?.value ?: 0, goal?.max ?: 0
        )
        return json.encodeToString(snap)
    }
}
