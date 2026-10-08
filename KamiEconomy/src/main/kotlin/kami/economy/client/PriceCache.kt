package kami.economy.client

import kami.economy.economy.ClientVariants as Variants

enum class Direction { UP, DOWN, FLAT }

class PriceInfo(val price: Int, val direction: Direction)

object PriceCache {
    private val prices = HashMap<String, PriceInfo>()
    private val lots = HashMap<String, Int>()

    fun lot(item: String, lot: Int) { lots[item] = lot }

    fun lotOf(item: String): Int = lots[item] ?: 1

    val items: Set<String> get() = prices.keys

    fun update(item: String, price: Int) {
        val prev = prices[item]?.price
        val dir = when {
            prev == null -> Direction.FLAT
            price > prev -> Direction.UP
            price < prev -> Direction.DOWN
            else -> Direction.FLAT
        }
        prices[item] = PriceInfo(price, dir)
    }

    fun of(item: String): PriceInfo? = prices[item]

    fun shown(item: String): PriceInfo? {
        if (Variants.of(item) == null) return prices[item]
        val core = Variants.core(item)
        val base = prices[core] ?: return null
        val cached = shownCache[item]
        if (cached != null && cached.first === base) return cached.second
        val made = PriceInfo(Variants.scale(item, base.price.toLong(), lotOf(core)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), base.direction)
        shownCache[item] = base to made
        return made
    }

    private val shownCache = HashMap<String, Pair<PriceInfo, PriceInfo>>()
}
