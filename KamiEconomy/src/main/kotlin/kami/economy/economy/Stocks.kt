package kami.economy.economy

import kami.economy.Config
import kami.economy.Market
import kami.economy.Sale
import kami.economy.StarterGood
import kami.economy.Stock
import kami.economy.Vendor
import kami.economy.now
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

object Stocks {
    const val ORDER_ID = -1L
    const val INF = -1
    private const val UNSET = -2
    private const val WINDOW = 24 * 60 * 60 * 1000L
    private var goods: Map<String, StarterGood> = emptyMap()
    private var configured: Map<String, StarterGood> = emptyMap()
    private var present: (String) -> Boolean = { true }
    private var resolve: (String) -> List<String> = { emptyList() }

    fun index(present: (String) -> Boolean = { true }, resolve: (String) -> List<String>) {
        this.present = present
        this.resolve = resolve
        reindex()
    }

    fun reindex() {
        configured = Config.s.starterGoods.flatMap { g -> (if (g.item.startsWith("#")) resolve(g.item.drop(1)) else listOf(g.item).filter(present)).map { it to g } }.toMap()
        goods = configured.filterKeys { Variants.of(it) == null }
        Market.data.stocks.keys.toList().forEach { stock(it) }
        goods.keys.forEach { i -> Market.peek(i)?.takeIf { it.course > 0.0 }?.let { it.course = clampCourse(i, it.course) } }
    }

    fun good(item: String): StarterGood? = goods[item]

    fun configured(item: String): StarterGood? = configured[item]

    fun items(): Set<String> = goods.keys

    fun stock(item: String): Stock? {
        val g = goods[item] ?: return null
        val s = Market.data.stocks.getOrPut(item) { Market.dirty = true; Stock(g.supply, g.demand) }
        if (s.demand == UNSET) {
            s.demand = g.demand
            if (!g.resell) s.supply = g.supply
            Market.dirty = true
        }
        return s
    }

    fun course(item: String): Double = Market.peek(item)?.course?.takeIf { it > 0.0 } ?: goods[item]?.base?.toDouble() ?: 0.0

    fun spread(item: String): Double = goods[item]?.spread ?: Config.s.serverSpread

    fun floor(item: String): Int = goods[item]?.let { it.floor ?: maxOf(1, it.base / 2) } ?: 1

    fun ceiling(item: String): Int? = goods[item]?.ceiling

    fun depth(item: String): Int = goods[item]?.depth ?: Config.s.courseDepth

    fun infiniteDemand(item: String): Boolean = stock(item)?.demand == INF

    private fun clampCourse(item: String, c: Double): Double = floor(item).toDouble().let { lo -> c.coerceIn(lo, maxOf(lo, ceiling(item)?.toDouble() ?: Double.MAX_VALUE)) }

    private fun toInt(x: Double): Int = x.coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()

    fun bidAt(item: String, c: Double): Int = maxOf(floor(item), toInt(floor(c * (1 - spread(item)) + 1e-9)))

    fun askAt(item: String, c: Double): Int = maxOf(toInt(ceil(c * (1 + spread(item)) - 1e-9)), bidAt(item, c) + 1, 1)

    private fun step(item: String): Double = spread(item) / (1 + depth(item))

    fun afterBuy(item: String, c: Double, lots: Int): Double = clampCourse(item, c * (1 + step(item)).pow(lots))

    fun afterSell(item: String, c: Double, lots: Int): Double = clampCourse(item, c * (1 - step(item)).pow(lots))

    fun afterPlayer(item: String, c: Double, price: Double, lots: Int): Double {
        if (c <= 0.0) return clampCourse(item, price)
        val clip = Config.s.courseClip
        val p = price.coerceIn(c * (1 - clip), c * (1 + clip))
        return clampCourse(item, c + lots.toDouble() / (lots + depth(item)) * (p - c))
    }

    fun supplyLeft(item: String): Int {
        val s = stock(item) ?: return 0
        return if (s.supply == INF) Int.MAX_VALUE else s.supply
    }

    fun demandLeft(item: String, player: String): Int {
        val s = stock(item) ?: return 0
        return minOf(if (s.demand == INF) Int.MAX_VALUE else s.demand.coerceAtLeast(0), capLeft(player, item))
    }

    fun ask(item: String): Int? = if (supplyLeft(item) > 0) askAt(item, course(item)) else null

    fun bid(item: String, player: String): Int? = if (demandLeft(item, player) > 0) bidAt(item, course(item)) else null

    fun capLeft(player: String, item: String): Int {
        val g = goods[item] ?: return Int.MAX_VALUE
        if (g.sellCap <= 0) return Int.MAX_VALUE
        val from = now() - WINDOW
        return (g.sellCap - Market.data.sold[player]?.get(g.item).orEmpty().sumOf { if (it.at > from) it.lots else 0 }).coerceAtLeast(0)
    }

    private fun grow(x: Int, lots: Int): Int = (x.toLong() + lots).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun take(item: String, lots: Int) {
        val s = stock(item) ?: return
        if (s.supply != INF) s.supply = (s.supply - lots).coerceAtLeast(0)
        if (s.demand != INF) s.demand = grow(s.demand, lots)
        Market.dirty = true
    }

    fun sold(player: String, item: String, lots: Int) {
        val s = stock(item) ?: return
        val g = goods.getValue(item)
        if (s.demand != INF) s.demand = (s.demand - lots).coerceAtLeast(0)
        if (s.supply != INF && g.resell) s.supply = grow(s.supply, lots)
        if (g.sellCap > 0) {
            val list = Market.data.sold.getOrPut(player) { mutableMapOf() }.getOrPut(g.item) { mutableListOf() }
            list.removeAll { it.at <= now() - WINDOW }
            list += Sale(now(), lots)
        }
        Market.dirty = true
    }

    fun prune() {
        val from = now() - WINDOW
        var changed = false
        Market.data.sold.values.forEach { m ->
            m.values.forEach { l -> if (l.removeAll { it.at <= from }) changed = true }
            m.values.removeAll { it.isEmpty() }
        }
        if (Market.data.sold.values.removeAll { it.isEmpty() }) changed = true
        if (changed) Market.dirty = true
    }

    fun set(item: String, supply: Int?, demand: Int?, course: Double?) {
        val s = stock(item) ?: return
        supply?.let { s.supply = it }
        demand?.let { s.demand = it }
        course?.takeIf { it > 0.0 }?.let { Market.book(item).course = clampCourse(item, it) }
        Market.dirty = true
    }

    fun vendorPrice(item: String): Int? = vendorPrices(item)[item]

    fun vendorPrices(only: String? = null): Map<String, Int> =
        Market.data.vendors.filter { it.price > 0 && it.count > 0 && (only == null || it.item == only) }.groupBy { it.item }.mapValues { (item, list) -> median(item, list) }

    private fun median(item: String, vendors: List<Vendor>): Int {
        val lot = Config.s.lotOf(item)
        val prices = vendors
            .groupBy { it.owner.ifEmpty { "${it.dim}:${it.x},${it.y},${it.z}" } }
            .map { (_, list) -> list.minOf { (it.price.toLong() * lot / it.count.toDouble()).roundToInt().coerceAtLeast(1) } }.sorted()
        val mid = prices.size / 2
        return if (prices.size % 2 == 1) prices[mid] else (prices[mid - 1] + prices[mid] + 1) / 2
    }
}
