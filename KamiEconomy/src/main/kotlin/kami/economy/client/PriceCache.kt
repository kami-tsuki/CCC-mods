package kami.economy.client

enum class Direction { UP, DOWN, FLAT }

class PriceInfo(val price: Int, val direction: Direction)

object PriceCache {
    private val prices = HashMap<String, PriceInfo>()

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
}
