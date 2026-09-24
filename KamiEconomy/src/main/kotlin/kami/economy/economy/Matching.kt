package kami.economy.economy

import kami.economy.Book
import kami.economy.Config
import kami.economy.Fill
import kami.economy.Market
import kami.economy.Order
import kami.economy.now
import kotlin.math.min

class FillLinePlan(val orderId: Long, val owner: String, val qty: Int, val unitPrice: Int)

class BuyPlan(val fills: List<FillLinePlan>, val filled: Int, val totalSpurs: Int)

object Matching {
    fun plan(item: String, qty: Int): BuyPlan {
        val book = Market.book(item)
        var remaining = qty
        var total = 0
        val fills = mutableListOf<FillLinePlan>()
        for (order in book.sells.sortedBy { it.price }) {
            if (remaining <= 0) break
            val take = min(remaining, order.amount)
            if (take <= 0) continue
            fills += FillLinePlan(order.id, order.owner, take, order.price)
            total += take * order.price
            remaining -= take
        }
        return BuyPlan(fills, qty - remaining, total)
    }

    fun applyFills(item: String, fills: List<FillLinePlan>) {
        val book = Market.book(item)
        fills.forEach { f ->
            val order = book.sells.firstOrNull { it.id == f.orderId } ?: return@forEach
            order.amount -= f.qty
            book.lastFill = f.unitPrice
            book.recentFills += Fill(f.unitPrice, f.qty, now())
        }
        book.sells.removeAll { !it.synthetic && it.amount <= 0 }
        Market.dirty = true
    }

    fun insertSell(item: String, order: Order) {
        val book = Market.book(item)
        if (book.sells.any { it.id == order.id }) return
        book.sells += order
        Market.dirty = true
    }

    fun cancelOrder(item: String, orderId: Long, owner: String): Order? {
        val book = Market.book(item)
        val order = book.sells.firstOrNull { it.id == orderId && it.owner == owner && !it.synthetic } ?: return null
        book.sells.remove(order)
        Market.dirty = true
        return order
    }

    fun bestPrice(item: String): Int? = Market.book(item).sells.filter { it.amount > 0 }.minOfOrNull { it.price }

    fun effectiveSellPrice(item: String): Int? {
        bestPrice(item)?.let { return it }
        val endless = Config.s.endlessByItem[item]?.takeIf { Config.s.sellInfiniteEnabled } ?: return null
        return endless.floorPrice
    }

    fun available(item: String): Int = Market.book(item).sells.filter { !it.synthetic }.sumOf { it.amount.coerceAtMost(1_000_000) }

    fun maxAffordable(item: String, funds: Long): Int {
        val stock = Market.book(item).sells.filter { it.amount > 0 }.sumOf { it.amount.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (stock <= 0 || funds <= 0) return 0
        var lo = 0
        var hi = stock
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            val cost = plan(item, mid).totalSpurs.toLong()
            if (cost <= funds) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun isInfinite(item: String): Boolean {
        val sells = Market.book(item).sells
        return sells.any { it.synthetic } && sells.none { !it.synthetic && it.amount > 0 }
    }

    fun bookFor(item: String): Book = Market.book(item)
}
