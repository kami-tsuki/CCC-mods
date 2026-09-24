package kami.libs.economy

interface MarketProvider {
    fun bestPrice(item: String): Int?
    fun available(item: String): Int
}

object MarketApi {
    private var provider: MarketProvider? = null

    fun register(p: MarketProvider) {
        provider = p
    }

    val present get() = provider != null

    fun bestPrice(item: String): Int? = provider?.bestPrice(item)
    fun available(item: String): Int = provider?.available(item) ?: 0
}
