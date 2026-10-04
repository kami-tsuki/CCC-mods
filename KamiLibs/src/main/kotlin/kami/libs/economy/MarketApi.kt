package kami.libs.economy

import net.minecraft.world.item.ItemStack

class Quote(val name: String, val price: Long, val changePermille: Int)

fun interface MarketProvider {
    fun value(item: ItemStack): Long
    fun ticker(limit: Int): List<Quote> = emptyList()
}

object MarketApi {
    private var provider: MarketProvider? = null

    fun register(p: MarketProvider) {
        provider = p
    }

    fun value(item: ItemStack): Long = provider?.value(item) ?: 0
    fun ticker(limit: Int): List<Quote> = provider?.ticker(limit).orEmpty()
}
