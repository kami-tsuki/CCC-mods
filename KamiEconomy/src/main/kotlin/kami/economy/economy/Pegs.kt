package kami.economy.economy

import kami.economy.Config
import kami.economy.Market
import kami.economy.Order
import kami.libs.chat.Tone
import kami.libs.economy.CleanStep
import kotlin.math.ceil
import kotlin.math.floor

object Pegs {
    private const val CAP = 200
    private const val SCAN = 1000
    private var cursor = 0
    private val warned = HashSet<Long>()

    fun tick() {
        var left = CAP
        var scan = SCAN
        val live = HashSet<Long>()
        val books = Market.data.books.values.toList()
        if (books.isEmpty()) return
        val start = cursor % books.size
        var visited = 0
        while (visited < books.size) {
            val book = books[(start + visited) % books.size]
            val pegged = book.buys.count { it.peg != null } + book.sells.count { it.peg != null }
            if (visited > 0 && (left <= 0 || scan < pegged)) break
            if (pegged > 0 && Stocks.good(book.item) == null) {
                (book.buys + book.sells).filter { it.peg != null }.forEach { it.peg = null; it.pegLimit = null }
                Market.dirty = true
                visited++
                continue
            }
            for (o in book.buys.toList()) if (o.peg != null) { live += o.id; scan--; if (left > 0 && bid(book.item, o)) left-- }
            for (o in book.sells.toList()) if (o.peg != null) { live += o.id; scan--; if (left > 0 && sell(book.item, o)) left-- }
            visited++
        }
        cursor = (start + visited) % books.size
        if (visited == books.size) warned.retainAll(live)
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun snap(x: Double, g: Long, bid: Boolean): Int? {
        if (x < g) return null
        val steps = if (bid) floor(x / g + 1e-9) else ceil(x / g - 1e-9)
        return (steps * g).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    private fun price(item: String, o: Order, bid: Boolean): Int? {
        val course = Stocks.course(item)
        if (course <= 0.0) return null
        val raw = course * (1 + (o.peg ?: 0) / 1000.0)
        val q = CleanStep.q(listOf(Config.s.taxPct))
        val g = q / gcd(q, (o.amount / o.lot.coerceAtLeast(1)).toLong().coerceAtLeast(1))
        val capped = o.pegLimit?.let { if (bid) minOf(raw, it.toDouble()) else maxOf(raw, it.toDouble()) } ?: raw
        var price = snap(capped, g, bid) ?: return null
        Matching.bestCross(item, o.owner, price, bid)?.let { price = snap((if (bid) it - 1 else it + 1).toDouble(), g, bid) ?: return null }
        return price.takeIf { it != o.price && Config.s.validPrice(it) }
    }

    private fun bid(item: String, o: Order): Boolean {
        val price = price(item, o, true) ?: return false
        return when (Ledger.repriceBid(o.owner, item, price)) {
            is OrderResult.Ok -> { warned -= o.id; true }
            OrderResult.InsufficientFunds -> { if (warned.add(o.id)) Notify.tell(o.owner, Tone.WARN, "peg_funds") { arrayOf(Notify.stack(item, o.amount)) }; false }
            else -> false
        }
    }

    private fun sell(item: String, o: Order): Boolean {
        val price = price(item, o, false) ?: return false
        if (Matching.crosses(item, price, o.owner, false) || Matching.ownCross(o.owner, item, price, false)) return false
        Ledger.reprice(o.owner, item, o.id, price)
        return true
    }
}
