package kami.economy.economy

import kami.economy.Config
import kami.economy.Fill
import kami.economy.Market
import kami.economy.StarterGood
import kami.economy.Stock
import kami.economy.Vendor
import kami.economy.now
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign

class StockSale(val lots: Int, val guaranteed: Int, val gross: Long, val tax: Long, val after: Int, val full: Boolean = false) {
    val net: Long get() = gross - tax
}

object Stocks {
    const val ORDER_ID = -1L
    private const val ALL = "*"
    private const val BASE_SPREAD = 3.0
    private var goods: Map<String, StarterGood> = emptyMap()

    fun index(resolve: (String) -> List<String>) {
        goods = Config.s.starterGoods.flatMap { g -> (if (g.item.startsWith("#")) resolve(g.item.drop(1)) else listOf(g.item)).map { it to g } }.toMap()
    }

    fun good(item: String): StarterGood? = goods[item]

    fun items(): Set<String> = goods.keys

    fun stock(item: String): Stock? {
        val g = goods[item] ?: return null
        return Market.data.stocks.getOrPut(item) { Market.dirty = true; Stock(g.target, g.base.toDouble()) }
    }

    private fun curve(u: Double): Double {
        val lo = Config.s.bandLow
        val hi = Config.s.bandHigh
        return when {
            u > ln(hi) -> hi - 1 + hi * (u - ln(hi))
            u < ln(lo) -> lo - 1 + lo * (u - ln(lo))
            else -> exp(u) - 1
        }
    }

    fun value(item: String, from: Int, to: Int): Double {
        val g = goods[item] ?: return 0.0
        val s = stock(item) ?: return 0.0
        val d = g.depth.toDouble()
        return s.base * d * (curve((g.target - from) / d) - curve((g.target - to) / d))
    }

    fun price(item: String, lots: Int? = null): Double {
        val g = goods[item] ?: return 0.0
        val s = stock(item) ?: return 0.0
        return s.base * exp((g.target - (lots ?: s.lots)).toDouble() / g.depth).coerceIn(Config.s.bandLow, Config.s.bandHigh)
    }

    private fun clampBase(g: StarterGood, base: Double): Double = base.coerceIn(g.base / BASE_SPREAD, g.base * BASE_SPREAD)

    fun basePrice(item: String): Int {
        val g = goods[item] ?: return 1
        if (g.infinite) return g.base.coerceAtLeast(1)
        return clampBase(g, stock(item)?.base ?: g.base.toDouble()).roundToInt().coerceAtLeast(1)
    }

    fun limit(item: String): Int {
        val g = goods[item] ?: return 0
        return ceil(g.target - g.depth * ln(Config.s.bandLow) - 1e-9).toInt().coerceAtLeast(g.target)
    }

    fun infinite(item: String): Boolean = goods[item]?.infinite == true

    /** Lots the market still takes; infinite goods never fill up, they are only limited per player by [capLeft]. */
    fun room(item: String): Int = if (infinite(item)) Int.MAX_VALUE / 2 else stock(item)?.let { (limit(item) - it.lots).coerceAtLeast(0) } ?: 0

    fun room(item: String, player: String): Int = if (infinite(item)) capLeft(player, item) else room(item)

    fun tax(gross: Long): Long = (gross * Config.s.taxPct.coerceIn(0, 100) + 99) / 100

    fun ask(item: String): Int? = stock(item)?.takeIf { it.lots > 0 && !infinite(item) }?.let { (price(item) * (100 + Config.s.taxPct) / 100).roundToInt().coerceAtLeast(1) }

    fun bid(player: String, item: String): Int? =
        stock(item)?.takeIf { room(item, player) > 0 }?.let { if (capLeft(player, item) > 0) basePrice(item) else price(item).roundToInt() }

    private fun floorSpurs(x: Double): Long = floor(x + 1e-9).toLong()

    private fun ceilSpurs(x: Double): Long = ceil(x - 1e-9).toLong()

    private fun capKey(item: String) = if (goods[item]?.dailyCap != null) item else ALL

    private fun cap(item: String): Int = goods[item]?.dailyCap ?: Config.s.dailySellLots

    fun capLeft(player: String, item: String): Int {
        if (item !in goods) return 0
        return (cap(item) - (Market.data.sold[player]?.get(capKey(item)) ?: 0)).coerceAtLeast(0)
    }

    fun sale(player: String, item: String, lots: Int): StockSale? {
        val s = stock(item) ?: return null
        val guaranteed = minOf(lots, capLeft(player, item))
        if (infinite(item)) {
            val gross = guaranteed.toLong() * basePrice(item)
            return StockSale(lots, guaranteed, gross, tax(gross), basePrice(item), full = lots > guaranteed)
        }
        val gross = floorSpurs(guaranteed.toDouble() * basePrice(item) + value(item, s.lots + guaranteed, s.lots + lots))
        return StockSale(lots, guaranteed, gross, tax(gross), price(item, s.lots + lots).roundToInt(), full = lots > room(item))
    }

    fun plan(item: String, qty: Int): FillLinePlan? {
        val s = stock(item) ?: return null
        if (infinite(item)) return null
        val lot = goods.getValue(item).lot
        val lots = minOf(qty / lot, s.lots)
        if (lots <= 0) return null
        val cost = ceilSpurs(value(item, s.lots - lots, s.lots))
        return FillLinePlan(ORDER_ID, "", lots * lot, (cost / lots).toInt(), lot, cost, tax(cost))
    }

    fun take(item: String, lots: Int) {
        val s = stock(item) ?: return
        s.lots = (s.lots - lots).coerceAtLeast(0)
        Market.dirty = true
    }

    fun sold(player: String, item: String, lots: Int, guaranteed: Int, perLot: Int) {
        val s = stock(item) ?: return
        if (!infinite(item)) s.lots += lots
        if (guaranteed > 0) Market.data.sold.getOrPut(player) { mutableMapOf() }.merge(capKey(item), guaranteed, Int::plus)
        val book = Market.book(item)
        book.lastFill = perLot
        book.recentFills += Fill(perLot, lots * goods.getValue(item).lot, now())
        Market.dirty = true
    }

    fun observe(item: String, perLot: Int) {
        val s = stock(item) ?: return
        val sample = perLot.toDouble().coerceIn(s.base * Config.s.bandLow, s.base * Config.s.bandHigh)
        s.observed = if (s.observed <= 0.0) sample else s.observed * 0.8 + sample * 0.2
    }

    fun vendorPrice(item: String): Int? = vendorPrice(item, Market.data.vendors.filter { it.item == item })

    private fun vendorPrice(item: String, vendors: List<Vendor>): Int? {
        val lot = Config.s.lotOf(item)
        val prices = vendors.filter { it.price > 0 && it.count > 0 }
            .groupBy { it.owner.ifEmpty { "${it.dim}:${it.x},${it.y},${it.z}" } }
            .map { (_, list) -> list.minOf { (it.price.toLong() * lot / it.count.toDouble()).roundToInt().coerceAtLeast(1) } }.sorted()
        if (prices.isEmpty()) return null
        val mid = prices.size / 2
        return if (prices.size % 2 == 1) prices[mid] else (prices[mid - 1] + prices[mid] + 1) / 2
    }

    fun day(millis: Long): Long =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).minusHours(Config.s.resetHour.toLong()).toLocalDate().toEpochDay()

    fun nextReset(): Long =
        LocalDate.ofEpochDay(day(now()) + 1).atTime(Config.s.resetHour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun rollover(millis: Long = now()) {
        val today = day(millis)
        val data = Market.data
        if (today == data.day) return
        if (data.day != 0L) recover()
        data.day = today
        data.sold.clear()
        Market.dirty = true
    }

    private fun recover() {
        val byItem = Market.data.vendors.groupBy { it.item }
        Market.data.stocks.forEach { (item, s) ->
            val g = goods[item] ?: return@forEach
            byItem[item]?.let { vendorPrice(item, it) }?.let { observe(item, it) }
            val gap = g.target - s.lots
            val move = (gap * Config.s.recoveryPct / 100.0).roundToInt()
            s.lots += if (move == 0 && Config.s.recoveryPct > 0) gap.sign else move
            val moved = if (s.observed > 0.0) s.base + (s.observed - s.base).coerceIn(-s.base * 0.1, s.base * 0.1) else s.base
            s.base = clampBase(g, moved)
            if (s.observed > 0.0) s.observed = s.base + (s.observed - s.base) * 0.95
        }
    }
}
