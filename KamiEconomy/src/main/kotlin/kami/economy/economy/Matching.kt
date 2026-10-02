package kami.economy.economy

import kami.economy.Book
import kami.economy.Config
import kami.economy.Fill
import kami.economy.Market
import kami.economy.Order
import kami.economy.now
import kami.libs.economy.CleanStep

class FillLinePlan(
    val orderId: Long, val owner: String, val qty: Int, val unitPrice: Int, val lot: Int = 1,
    val gross: Long = qty.toLong() / lot.coerceAtLeast(1) * unitPrice, val tax: Long = Matching.tax(gross), val tariff: Long = 0
) {
    val market: Boolean get() = orderId == Stocks.ORDER_ID
    val paid: Long get() = if (market) gross + tax else gross + tariff
}

class BuyPlan(val fills: List<FillLinePlan>, val filled: Int, val totalSpurs: Long)

class SellPlan(val fills: List<FillLinePlan>, val filled: Int, val capLots: Int, val after: Int) {
    val gross: Long get() = fills.sumOf { it.gross }
    val tax: Long get() = fills.sumOf { it.tax }
    val tariff: Long get() = fills.sumOf { it.tariff }
    val net: Long get() = gross - tax - tariff
}

object Matching {
    private class Taker(var remaining: Int, val self: String, val terms: (String) -> Terms) {
        val fills = mutableListOf<FillLinePlan>()
        fun take(order: Order) {
            if (remaining <= 0 || order.owner == self) return
            val t = terms(order.owner)
            if (t.blocked) return
            val lot = order.lot.coerceAtLeast(1)
            val orderLots = order.amount / lot
            val cap = minOf(remaining / lot, orderLots)
            val lots = if (cap == orderLots) orderLots else cap - cap % CleanStep.step(order.price.toLong(), t.rates)
            if (lots <= 0) return
            val gross = lots.toLong() * order.price
            fills += FillLinePlan(order.id, order.owner, lots * lot, order.price, lot, gross, CleanStep.charge(gross, t.taxPct), CleanStep.charge(gross, t.tariffPct))
            remaining -= lots * lot
        }
    }

    fun plan(item: String, qty: Int, buyer: String = ""): BuyPlan {
        val ask = Stocks.ask(item)
        val (cheap, dear) = bookFor(item).sells.filter { it.amount > 0 }.sortedBy { it.price }.partition { ask == null || it.price <= ask }
        val t = Taker(qty, buyer) { Trade.terms(buyer, it) }
        cheap.forEach(t::take)
        if (t.remaining > 0) Stocks.plan(item, t.remaining)?.let { t.fills += it; t.remaining -= it.qty }
        dear.forEach(t::take)
        return BuyPlan(t.fills, qty - t.remaining, t.fills.sumOf { it.paid })
    }

    fun bids(item: String): List<Order> =
        bookFor(item).buys.filter { it.amount > 0 }.sortedWith(compareByDescending<Order> { it.price }.thenBy { it.placedAt })

    fun sellPlan(seller: String, item: String, qty: Int, minPrice: Int? = null): SellPlan {
        val stockBid = if (minPrice == null) Stocks.bid(seller, item) else null
        val (good, poor) = bids(item).filter { minPrice == null || it.price >= minPrice }.partition { stockBid == null || it.price >= stockBid }
        val t = Taker(qty, seller) { Trade.terms(it, seller) }
        good.forEach(t::take)
        var capLots = 0
        var after = 0
        val lot = Stocks.good(item)?.lot ?: 1
        val lots = if (stockBid == null) 0 else minOf(t.remaining / lot, Stocks.room(item))
        if (lots > 0) Stocks.sale(seller, item, lots)?.takeIf { it.net > 0 }?.let {
            t.fills += FillLinePlan(Stocks.ORDER_ID, "", lots * lot, (it.gross / lots).toInt(), lot, it.gross, it.tax)
            t.remaining -= lots * lot
            capLots = it.guaranteed
            after = it.after
        }
        poor.forEach(t::take)
        return SellPlan(t.fills, qty - t.remaining, capLots, after)
    }

    fun applySale(item: String, seller: String, fills: List<FillLinePlan>, capLots: Int) {
        val book = Market.book(item)
        val lot = Config.s.lotOf(item)
        fills.forEach { f ->
            if (f.market) return@forEach Stocks.sold(seller, item, f.qty / f.lot.coerceAtLeast(1), capLots, f.unitPrice)
            val bid = book.buys.firstOrNull { it.id == f.orderId } ?: return@forEach
            bid.amount -= f.qty
            Market.queueDelivery(bid.owner, item, f.qty)
            val perLot = (f.gross * lot / f.qty.coerceAtLeast(1)).toInt()
            if (!Trade.sameCountry(seller, bid.owner)) Stocks.observe(item, perLot)
            book.lastFill = perLot
            book.recentFills += Fill(perLot, f.qty, now())
        }
        book.buys.removeAll { it.amount <= 0 }
        Market.dirty = true
    }

    fun insertBid(item: String, order: Order) {
        val book = Market.book(item)
        if (book.buys.any { it.id == order.id }) return
        book.buys += order
        Market.dirty = true
    }

    fun removeBid(item: String, orderId: Long): Order? {
        val book = Market.book(item)
        val order = book.buys.firstOrNull { it.id == orderId } ?: return null
        book.buys.remove(order)
        Market.dirty = true
        return order
    }

    fun escrow(order: Order): Long = order.amount.toLong() / order.lot.coerceAtLeast(1) * order.price

    fun bestBid(item: String): Int? = bookFor(item).buys.filter { it.amount > 0 }.maxOfOrNull { it.price }

    fun step(lotPrice: Int): Int = CleanStep.step(lotPrice.toLong(), listOf(Config.s.taxPct))

    fun isClean(lots: Int, lotPrice: Int): Boolean = CleanStep.isClean(lots.toLong() * lotPrice, listOf(Config.s.taxPct))

    fun tax(gross: Long): Long = CleanStep.charge(gross, Config.s.taxPct)

    fun applyFills(item: String, fills: List<FillLinePlan>, buyer: String = "") {
        val book = Market.book(item)
        val lot = Config.s.lotOf(item)
        fills.forEach { f ->
            val perLot = (f.gross * lot / f.qty.coerceAtLeast(1)).toInt()
            if (f.market) Stocks.take(item, f.qty / f.lot.coerceAtLeast(1))
            else {
                val order = book.sells.firstOrNull { it.id == f.orderId } ?: return@forEach
                order.amount -= f.qty
                if (!Trade.sameCountry(buyer, order.owner)) Stocks.observe(item, perLot)
            }
            book.lastFill = perLot
            book.recentFills += Fill(perLot, f.qty, now())
        }
        book.sells.removeAll { it.amount <= 0 }
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
        val order = book.sells.firstOrNull { it.id == orderId && it.owner == owner } ?: return null
        book.sells.remove(order)
        Market.dirty = true
        return order
    }

    fun bestPrice(item: String): Int? = bookFor(item).sells.filter { it.amount > 0 }.minOfOrNull { it.price }

    fun effectiveSellPrice(item: String): Int? = listOfNotNull(bestPrice(item), Stocks.ask(item)).minOrNull()

    fun available(item: String, viewer: String = ""): Int {
        val stock = Stocks.stock(item)?.let { it.lots.toLong() * Config.s.lotOf(item) } ?: 0L
        return (bookFor(item).sells.sumOf { if (it.owner == viewer) 0L else it.amount.toLong() } + stock).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun maxAffordable(item: String, funds: Long, buyer: String = ""): Int {
        val stock = available(item, buyer)
        if (stock <= 0 || funds <= 0) return 0
        var lo = 0
        var hi = stock
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            val cost = plan(item, mid, buyer).totalSpurs
            if (cost <= funds) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun bookFor(item: String): Book = Market.peek(item) ?: Book(item)
}
