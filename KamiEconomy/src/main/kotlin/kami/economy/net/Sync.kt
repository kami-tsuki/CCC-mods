package kami.economy.net

import kami.economy.Config
import kami.economy.Market
import kami.economy.economy.Auctions
import kami.economy.economy.History
import kami.economy.economy.Matching
import kami.economy.economy.Resolution
import kami.libs.economy.Numismatics
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.roundToInt

@Serializable class Row(val item: String, val price: Int, val available: Int, val infinite: Boolean)
@Serializable class OrderLine(val item: String, val price: Int, val amount: Int)
@Serializable class QuoteLine(val item: String, val qty: Int, val filled: Int, val total: Int, val avg: Int)
@Serializable class Candle(val open: Int, val high: Int, val low: Int, val close: Int, val volume: Int, val at: Long)
@Serializable class Detail(
    val item: String, val price: Int, val available: Int, val infinite: Boolean, val instantSell: Boolean,
    val myOrderPrice: Int, val myOrderAmount: Int, val maxAffordable: Int, val history: List<Candle>
)
@Serializable class AuctionLine(val id: Long, val label: String, val stackData: String, val startPrice: Int, val buyNowPrice: Int, val currentBid: Int, val currentBidder: String, val expiresAt: Long, val mine: Boolean)
@Serializable class Snap(
    val funds: Long, val rows: List<Row>, val orders: List<OrderLine>, val query: String, val sort: String,
    val page: Int, val pages: Int, val taxPct: Int, val auctionFeePct: Int, val detail: Detail?, val quote: QuoteLine?,
    val auctions: List<AuctionLine>, val auctionPage: Int, val auctionPages: Int,
    val msg: String, val ok: Boolean, val open: Boolean
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

    fun quote(p: ServerPlayer, item: String, qty: Int) {
        val s = states.getOrPut(p.uuid) { State() }
        if (item.isEmpty() || qty <= 0) { s.quote = null; return }
        val plan = Matching.plan(item, qty)
        val avg = if (plan.filled > 0) (plan.totalSpurs.toDouble() / plan.filled).roundToInt() else 0
        s.quote = QuoteLine(item, qty, plan.filled, plan.totalSpurs, avg)
    }

    fun auctionPage(p: ServerPlayer, page: Int) {
        states.getOrPut(p.uuid) { State() }.auctionPage = page.coerceAtLeast(0)
    }

    fun forget(p: ServerPlayer) {
        states.remove(p.uuid)
    }

    private fun rows(s: State): Pair<List<Row>, Int> {
        val all = Market.data.books.values
            .filter { it.item.contains(s.text, ignoreCase = true) && it.sells.any { o -> o.amount > 0 } }
            .map { Row(it.item, Matching.bestPrice(it.item) ?: 0, Matching.available(it.item), Matching.isInfinite(it.item)) }
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
            val mine = b.sells.filter { it.owner == me && !it.synthetic }
            if (mine.isEmpty()) null else OrderLine(b.item, mine.first().price, mine.sumOf { it.amount })
        }

    private fun detail(s: State, me: String, funds: Long): Detail? {
        if (s.detailItem.isEmpty()) return null
        val history = History.of(s.detailItem, s.historyRes, 120)
            .map { Candle(it.open.roundToInt(), it.high.roundToInt(), it.low.roundToInt(), it.close.roundToInt(), it.volume.toInt(), it.at) }
        val instantSell = Config.s.endlessByItem.containsKey(s.detailItem) && Config.s.sellInfiniteEnabled
        val mine = Matching.bookFor(s.detailItem).sells.filter { it.owner == me && !it.synthetic }
        val myPrice = mine.firstOrNull()?.price ?: 0
        val myAmount = mine.sumOf { it.amount }
        val maxAffordable = Matching.maxAffordable(s.detailItem, funds)
        return Detail(s.detailItem, Matching.effectiveSellPrice(s.detailItem) ?: 0, Matching.available(s.detailItem), Matching.isInfinite(s.detailItem), instantSell, myPrice, myAmount, maxAffordable, history)
    }

    private fun auctions(s: State, me: String): Triple<List<AuctionLine>, Int, Int> {
        val all = Auctions.open().sortedBy { it.expiresAt }
            .map { AuctionLine(it.id, it.label, it.stackData, it.startPrice, it.buyNowPrice ?: -1, it.currentBid, it.currentBidder, it.expiresAt, it.seller == me) }
        val pageSize = Config.s.pageSize
        val pages = ((all.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        val page = s.auctionPage.coerceIn(0, pages - 1)
        return Triple(all.drop(page * pageSize).take(pageSize), page, pages)
    }

    fun encode(p: ServerPlayer, msg: String, ok: Boolean, open: Boolean): String {
        val s = states.getOrPut(p.uuid) { State() }
        s.quote?.let { quote(p, it.item, it.qty) }
        val (rowList, pages) = rows(s)
        val (auctionList, auctionPage, auctionPages) = auctions(s, p.stringUUID)
        val funds = Numismatics.balance(p.uuid)
        val snap = Snap(
            funds, rowList, myOrders(p.stringUUID), s.text, s.sort, s.page, pages,
            (Config.s.sellTaxPct * 100).roundToInt(), (Config.s.auctionFeePct * 100).roundToInt(),
            detail(s, p.stringUUID, funds), s.quote, auctionList, auctionPage, auctionPages, msg, ok, open
        )
        return json.encodeToString(snap)
    }
}
