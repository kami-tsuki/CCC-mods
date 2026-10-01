package kami.libs.economy

import net.minecraft.world.item.ItemStack
import java.util.UUID

interface MarketProvider {
    fun bestPrice(item: String): Int?
    fun available(item: String): Int
    fun used(player: UUID, key: String): Int = 0
    fun value(item: ItemStack): Long = 0
}

object MarketApi {
    private var provider: MarketProvider? = null

    fun register(p: MarketProvider) {
        provider = p
    }

    val present get() = provider != null

    fun bestPrice(item: String): Int? = provider?.bestPrice(item)
    fun available(item: String): Int = provider?.available(item) ?: 0
    fun used(player: UUID, key: String): Int = provider?.used(player, key) ?: 0
    fun value(item: ItemStack): Long = provider?.value(item) ?: 0
}
