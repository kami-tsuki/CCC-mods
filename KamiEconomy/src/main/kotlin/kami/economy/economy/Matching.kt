package kami.economy.economy

import kami.economy.Book
import kami.economy.Config
import kami.economy.Fill
import kami.economy.LOG
import kami.economy.Market
import kami.economy.Order
import kami.economy.Traded
import kami.economy.now
import kami.economy.quest.Quests
import kami.libs.economy.CleanStep

class FillLinePlan(
    val orderId: Long, val owner: String, val qty: Int, val unitPrice: Int, val lot: Int = 1,
    val gross: Long = qty.toLong() / lot.coerceAtLeast(1) * unitPrice, val tax: Long = Matching.tax(gross), val tariff: Long = 0
) {
    val market: Boolean get() = orderId == Stocks.ORDER_ID
    val paid: Long get() = if (market) gross + tax else gross + tariff
}

class SellQuote(val plan: SellPlan?, val listed: Int, val step: Int, val limited: Boolean) {
    val filled get() = plan?.filled ?: 0
}

class BuyPlan(val fills: List<FillLinePlan>, val filled: Int, val totalSpurs: Long)

class SellPlan(val fills: List<FillLinePlan>, val filled: Int) {
    val gross: Long get() = fills.sumOf { it.gross }
    val tax: Long get() = fills.sumOf { it.tax }
    val tariff: Long get() = fills.sumOf { it.tariff }
    val net: Long get() = gross - tax - tariff
}

object Matching {
    private const val MAX_OWN_SELLS = 8
    private const val WASH_MS = 600_000L
    private val washed = HashMap<String, Long>()

    private fun washKeys(item: String, a: String, b: String) =
        listOf(if (a < b) "$item|$a|$b" else "$item|$b|$a", "$item|$a", "$item|$b", "$item|c:${Trade.country(a) ?: a}", "$item|c:${Trade.country(b) ?: b}")

    fun moves(item: String, a: String, b: String, seen: Set<String> = emptySet(), t: Long = now()): Boolean =
        washKeys(item, a, b).all { it !in seen && (washed[it] ?: Long.MIN_VALUE / 2) + WASH_MS <= t }

    private fun washMark(item: String, a: String, b: String, t: Long) {
        if (washed.size > 256) washed.values.removeIf { it + WASH_MS <= t }
        washKeys(item, a, b).forEach { washed[it] = t }
    }

    fun serverTax(gross: Long): Long = (gross * Config.s.taxPct + 99) / 100

    private class Entry(val order: Order, val terms: Terms)

    private fun chunk(e: Entry, remaining: Int): Int {
        val lot = e.order.lot.coerceAtLeast(1)
        val orderLots = e.order.amount / lot
        val cap = minOf(remaining / lot, orderLots)
        return if (cap == orderLots) orderLots else cap - cap % CleanStep.step(e.order.price.toLong(), e.terms.rates)
    }

    private fun walk(item: String, qty: Int, who: String, buy: Boolean, limit: Int?, only: Long? = null): List<FillLinePlan> {
        val good = Stocks.good(item)
        val lot = Config.s.lotOf(item)
        val queue = (if (buy) liveSells(item) else liveBids(item, who))
            .filter { it.owner != who && (only == null || it.id == only) && (limit == null || (if (buy) it.price <= limit else it.price >= limit)) }
            .map { Entry(it, if (buy) Trade.terms(who, it.owner) else Trade.terms(it.owner, who)) }
            .filter { !it.terms.blocked }
            .sortedWith(compareBy<Entry> { if (buy) it.order.price * (1 + it.terms.tariffPct / 100.0) else -it.order.price * (1 - (it.terms.taxPct + it.terms.tariffPct) / 100.0) }.thenBy { it.order.placedAt })
        val serverOn = good != null && only == null && (buy || TradeStatus.instantSell(item))
        val taxFactor = Config.s.taxPct / 100.0
        var c = Stocks.course(item)
        var left = if (!serverOn) 0 else if (buy) Stocks.supplyLeft(item) else Stocks.demandLeft(item, who)
        var remaining = qty
        var i = 0
        var runLots = 0
        var runGross = 0L
        val out = ArrayList<FillLinePlan>()
        val moved = HashSet<String>()
        val t = now()
        fun close() {
            if (runLots > 0) out += FillLinePlan(Stocks.ORDER_ID, "", runLots * lot, (runGross / runLots).toInt(), lot, runGross, serverTax(runGross))
            runLots = 0
            runGross = 0
        }
        while (remaining > 0) {
            var lots = 0
            while (i < queue.size && lots == 0) {
                lots = chunk(queue[i], remaining)
                if (lots == 0) i++
            }
            val player = queue.getOrNull(i)?.takeIf { lots > 0 }
            val server = left > 0 && remaining >= lot
            if (player == null && !server) break
            val serverGross = if (!server) 0 else if (buy) Stocks.askAt(item, c) else Stocks.bidAt(item, c)
            val serverScore = if (!server) Double.NEGATIVE_INFINITY else if (buy) -serverGross * (1 + taxFactor) else serverGross * (1 - taxFactor)
            val playerScore = player?.let {
                if (buy) -it.order.price * (1 + it.terms.tariffPct / 100.0) else it.order.price * (1 - (it.terms.taxPct + it.terms.tariffPct) / 100.0)
            } ?: Double.NEGATIVE_INFINITY
            val pick = player?.takeIf { playerScore >= serverScore }
            val gross = pick?.order?.price ?: serverGross
            if (pick == null && limit != null && !(if (buy) gross <= limit else gross >= limit)) {
                left = 0
                continue
            }
            if (pick != null) {
                val o = pick.order
                val total = lots.toLong() * o.price
                val fill = FillLinePlan(o.id, o.owner, lots * o.lot, o.price, o.lot, total, CleanStep.charge(total, pick.terms.taxPct), CleanStep.charge(total, pick.terms.tariffPct))
                if (!buy && fill.gross - fill.tax - fill.tariff <= 0) {
                    i++
                    continue
                }
                close()
                out += fill
                remaining -= lots * o.lot
                i++
                if (good != null && !Quests.owns(o.owner) && !Trade.sameCountry(who, o.owner) && moves(item, who, o.owner, moved, t)) {
                    moved += washKeys(item, who, o.owner)
                    if (o.peg == null) c = Stocks.afterPlayer(item, c, o.price.toDouble(), lots.toDouble())
                }
            } else {
                val next = if (buy) Stocks.afterBuy(item, c, 1) else Stocks.afterSell(item, c, 1)
                val n = if (next == c) minOf(left, remaining / lot) else 1
                runLots += n
                runGross += n.toLong() * serverGross
                remaining -= n * lot
                left -= n
                c = next
            }
        }
        close()
        return out
    }

    fun plan(item: String, qty: Int, buyer: String = "", limit: Int? = null): BuyPlan {
        if (!TradeStatus.instantBuy(item)) return BuyPlan(emptyList(), 0, 0)
        val fills = walk(item, qty, buyer, true, limit)
        return BuyPlan(fills, fills.sumOf { it.qty }, fills.sumOf { it.paid })
    }

    fun bids(item: String, viewer: String = ""): List<Order> =
        (bookFor(item).buys.filter { it.amount > 0 } + Quests.bids(item, viewer)).sortedWith(compareByDescending<Order> { it.price }.thenBy { it.placedAt })

    fun stockAsk(item: String): Int? = if (TradeStatus.instantBuy(item)) Stocks.ask(item) else null

    fun stockBid(seller: String, item: String): Int? = if (TradeStatus.instantSell(item)) Stocks.bid(item, seller) else null

    private fun liveBids(item: String, viewer: String = ""): List<Order> = if (TradeStatus.bids(item)) bids(item, viewer) else emptyList()

    private fun liveSells(item: String): List<Order> = if (TradeStatus.asks(item)) bookFor(item).sells.filter { it.amount > 0 } else emptyList()

    fun listPrice(seller: String, item: String, price: Int): Int = bookFor(item).sells.firstOrNull { it.owner == seller }?.price ?: price

    fun sellQuote(seller: String, item: String, qty: Int, price: Int): SellQuote {
        val lot = Config.s.lotOf(item)
        val whole = qty - qty % lot
        val plan = if (TradeStatus.instantSell(item)) sellPlan(item, whole, seller, limit = price).takeIf { it.filled > 0 && it.net > 0 } else null
        val rest = whole - (plan?.filled ?: 0)
        val step = step(price) * lot
        val own = bookFor(item).sells.count { it.owner == seller }
        val limited = own >= MAX_OWN_SELLS || own == 0 && Limits.marketFull(seller)
        return SellQuote(plan, if (limited) 0 else rest - rest % step, step, limited)
    }

    fun sellPlan(item: String, qty: Int, seller: String, limit: Int? = null): SellPlan {
        val fills = walk(item, qty, seller, false, limit)
        return SellPlan(fills, fills.sumOf { it.qty })
    }

    fun questPlan(seller: String, questId: Long, qty: Int): Pair<String, SellPlan>? {
        val orderId = Quests.orderId(questId)
        val item = Quests.itemOf(orderId, seller) ?: return null
        val lot = Quests.get(questId)?.lot ?: return null
        val fills = walk(item, qty - qty % lot, seller, false, null, orderId)
        return item to SellPlan(fills, fills.sumOf { it.qty })
    }

    private fun traded(item: String): Traded = Market.data.traded.getOrPut(item) { Traded() }

    private fun perLot(f: FillLinePlan): Double = f.gross.toDouble() * f.lot.coerceAtLeast(1) / f.qty.coerceAtLeast(1)

    fun applySale(item: String, seller: String, f: FillLinePlan, key: String, t: Long = now()): Boolean {
        if (Quests.isOrder(f.orderId)) {
            if (!Quests.delivered(key)) Quests.fill(item, seller, f, key, t).takeIf { it > 0 }?.let { n -> traded(item).apply { sold += n; last = t } }
            return true
        }
        val book = Market.book(item)
        val lots = f.qty / f.lot.coerceAtLeast(1)
        var moved = true
        if (f.market) {
            traded(item).apply { sold += f.qty; last = t }
            Stocks.sold(seller, item, lots)
            Pricing.observe(item, perLot(f), lots, server = true, buy = false)
        } else {
            val bid = book.buys.firstOrNull { it.id == f.orderId } ?: run { LOG.warn("Sale of {} x{} by {} paid but bid {} is gone", item, f.qty, seller, f.orderId); return true }
            traded(item).apply { sold += f.qty; last = t }
            bid.amount -= minOf(f.qty, bid.amount)
            Market.queueDelivery(bid.owner, item, f.qty, key)
            if (!Trade.sameCountry(seller, bid.owner)) {
                if (moves(item, seller, bid.owner, t = t)) {
                    washMark(item, seller, bid.owner, t)
                    if (bid.peg == null) Pricing.observe(item, perLot(f), lots, server = false, buy = false)
                } else moved = false
            }
            book.buys.removeAll { it.amount <= 0 }
        }
        book.lastFill = perLot(f).toInt()
        book.recentFills += Fill(book.lastFill, f.qty, now())
        Market.dirty = true
        return moved
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

    fun bestBid(item: String): Int? = bids(item).maxOfOrNull { it.price }

    fun step(lotPrice: Int): Int = CleanStep.step(lotPrice.toLong(), listOf(Config.s.taxPct))

    fun isClean(lots: Int, lotPrice: Int): Boolean = CleanStep.isClean(lots.toLong() * lotPrice, listOf(Config.s.taxPct))

    fun tax(gross: Long): Long = CleanStep.charge(gross, Config.s.taxPct)

    fun applyFills(item: String, fills: List<FillLinePlan>, buyer: String = ""): Set<Int> {
        val book = Market.book(item)
        val washedOut = HashSet<Int>()
        val t = now()
        fills.forEachIndexed { idx, f ->
            if (f.qty <= 0) return@forEachIndexed
            val lots = f.qty / f.lot.coerceAtLeast(1)
            if (f.market) {
                Stocks.take(item, lots)
                Pricing.observe(item, perLot(f), lots, server = true, buy = true)
            } else {
                val order = book.sells.firstOrNull { it.id == f.orderId } ?: return@forEachIndexed
                if (order.amount <= 0) return@forEachIndexed
                order.amount -= minOf(f.qty, order.amount)
                if (!Trade.sameCountry(buyer, order.owner)) {
                    if (moves(item, buyer, order.owner, t = t)) {
                        washMark(item, buyer, order.owner, t)
                        if (order.peg == null) Pricing.observe(item, perLot(f), lots, server = false, buy = true)
                    } else washedOut += idx
                }
            }
            traded(item).apply { bought += f.qty; last = t }
            book.lastFill = perLot(f).toInt()
            book.recentFills += Fill(book.lastFill, f.qty, now())
        }
        book.sells.removeAll { it.amount <= 0 }
        Market.dirty = true
        return washedOut
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

    fun bestCross(item: String, viewer: String, price: Int, bid: Boolean): Int? =
        if (bid) liveSells(item).filter { it.owner != viewer && !Trade.terms(viewer, it.owner).blocked && it.price <= price }.minOfOrNull { it.price }
        else openBids(item, viewer).filter { it.price >= price }.maxOfOrNull { it.price }

    fun crosses(item: String, price: Int, viewer: String, bid: Boolean): Boolean = bestCross(item, viewer, price, bid) != null

    fun ownCross(owner: String, item: String, price: Int, bid: Boolean): Boolean =
        if (bid) bookFor(item).sells.any { it.owner == owner && it.amount > 0 && it.price <= price }
        else bookFor(item).buys.any { it.owner == owner && it.amount > 0 && it.price >= price }

    fun anyAsk(item: String): Int? = listOfNotNull(liveSells(item).minOfOrNull { it.price }, stockAsk(item)).minOrNull()

    fun bestAsk(item: String, viewer: String): Int? =
        listOfNotNull(liveSells(item).filter { it.owner != viewer && !Trade.terms(viewer, it.owner).blocked }.minOfOrNull { it.price }, stockAsk(item)).minOrNull()

    fun openBids(item: String, viewer: String): List<Order> =
        liveBids(item, viewer).filter { it.owner != viewer && !Trade.terms(it.owner, viewer).blocked }

    fun bestBidFor(item: String, viewer: String): Int? =
        listOfNotNull(openBids(item, viewer).firstOrNull()?.price, stockBid(viewer, item)).maxOrNull()

    private fun units(lots: Int, item: String, base: Long): Int = (base + lots.toLong() * Config.s.lotOf(item)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun available(item: String, viewer: String = ""): Int {
        val players = liveSells(item).filter { it.owner != viewer && !Trade.terms(viewer, it.owner).blocked }.sumOf { it.amount.toLong() }
        val lots = if (TradeStatus.instantBuy(item)) Stocks.supplyLeft(item) else 0
        return if (lots == Int.MAX_VALUE) -1 else units(lots, item, players)
    }

    fun demand(item: String, viewer: String = ""): Int {
        val players = openBids(item, viewer).sumOf { it.amount.toLong() }
        val lots = if (TradeStatus.instantSell(item)) Stocks.demandLeft(item, viewer) else 0
        return if (lots == Int.MAX_VALUE) -1 else units(lots, item, players)
    }

    fun maxAffordable(item: String, funds: Long, buyer: String = ""): Int {
        val stock = available(item, buyer).let { if (it < 0) Config.s.maxAmount else it }
        if (stock <= 0 || funds <= 0) return 0
        val ask = bestAsk(item, buyer) ?: return 0
        val lot = Config.s.lotOf(item)
        var lo = 0
        var hi = minOf(stock.toLong(), funds / ask.coerceAtLeast(1) * lot).toInt()
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            val cost = plan(item, mid, buyer).totalSpurs
            if (cost <= funds) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun bookFor(item: String): Book = Market.peek(item) ?: Book(item)
}
