package kami.economy.economy

import kami.economy.Book
import kami.economy.Market
import kotlin.math.max
import kotlin.math.min

object Pricing {
    fun observe(item: String, perLot: Double, lots: Int, server: Boolean, buy: Boolean) {
        if (lots <= 0) return
        val c = Stocks.course(item)
        Market.book(item).course = when {
            !server -> Stocks.afterPlayer(item, c, perLot, lots.toDouble())
            buy -> Stocks.afterBuy(item, c, lots)
            else -> Stocks.afterSell(item, c, lots)
        }
        Market.dirty = true
    }

    class Asks(val items: Set<String>, private val prices: Map<String, Int>) {
        fun of(item: String): Int? = prices[item]
    }

    private var asks: Asks? = null
    private var asksRevision = -1L
    private var asksStatus = -1L

    fun asks(): Asks {
        asks?.takeIf { asksRevision == Market.revision && asksStatus == TradeStatus.rev }?.let { return it }
        val items = Market.data.books.keys + Stocks.items()
        return Asks(items, items.mapNotNull { i -> Matching.anyAsk(i)?.let { i to it } }.toMap()).also {
            asks = it
            asksRevision = Market.revision
            asksStatus = TradeStatus.rev
        }
    }

    fun invalidate() {
        asks = null
    }

    fun tick() {
        if (Market.data.books.values.map { record(it) }.any { it }) Market.dirty = true
        Stocks.prune()
        Pegs.tick()
        invalidate()
    }

    private fun record(book: Book): Boolean {
        val fills = book.recentFills
        if (fills.isEmpty()) return false
        val open = fills.first().price.toDouble()
        val close = if (book.course > 0.0) book.course else fills.last().price.toDouble()
        val high = max(fills.maxOf { it.price }.toDouble(), close)
        val low = min(fills.minOf { it.price }.toDouble(), close)
        History.record(book.item, open, high, low, close, fills.sumOf { it.qty.toLong() })
        fills.clear()
        return true
    }
}
