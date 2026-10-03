package kami.economy.economy

import kami.economy.Config
import kami.libs.economy.MarketProvider
import net.minecraft.world.item.ItemStack

object EconomyProvider : MarketProvider {
    override fun value(item: ItemStack): Long = if (item.isEmpty) 0 else valueOf(Blacklist.itemId(item), item.count)

    fun valueOf(id: String, count: Int): Long {
        val perLot = when {
            Stocks.infinite(id) -> Matching.bestBid(id) ?: Stocks.basePrice(id)
            Stocks.good(id) != null -> Stocks.basePrice(id)
            else -> Matching.bestPrice(id)
        } ?: return 0
        return perLot.toLong() * count / Config.s.lotOf(id)
    }
}
