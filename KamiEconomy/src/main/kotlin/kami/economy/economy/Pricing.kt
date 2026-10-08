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
            !server -> Stocks.afterPlayer(item, c, perLot, lots)
            buy -> Stocks.afterBuy(item, c, lots)
            else -> Stocks.afterSell(item, c, lots)
        }
        Market.dirty = true
    }

    fun tick() {
        if (Market.data.books.values.map { record(it) }.any { it }) Market.dirty = true
        Stocks.prune()
        Pegs.tick()
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
