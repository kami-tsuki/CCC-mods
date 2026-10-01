package kami.libs.economy

import net.minecraft.world.item.ItemStack

fun interface MarketProvider {
    fun value(item: ItemStack): Long
}

object MarketApi {
    private var provider: MarketProvider? = null

    fun register(p: MarketProvider) {
        provider = p
    }

    fun value(item: ItemStack): Long = provider?.value(item) ?: 0
}
