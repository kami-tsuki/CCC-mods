package kami.economy.economy

import kami.economy.Config
import kami.libs.claims.CountryCapacity
import kami.libs.economy.MarketProvider
import net.minecraft.world.item.ItemStack
import java.util.UUID

object EconomyProvider : MarketProvider {
    override fun bestPrice(item: String) = Matching.bestPrice(item)

    override fun available(item: String) = Matching.available(item)

    override fun used(player: UUID, key: String): Int = when (key) {
        CountryCapacity.MARKET_SLOTS -> Limits.marketUsed(player.toString())
        CountryCapacity.AUCTION_SLOTS -> Limits.auctionUsed(player.toString())
        else -> 0
    }

    override fun value(item: ItemStack): Long = if (item.isEmpty) 0 else valueOf(Blacklist.itemId(item), item.count)

    fun valueOf(id: String, count: Int): Long {
        val perLot = (if (Stocks.good(id) != null) Stocks.basePrice(id) else Matching.bestPrice(id)) ?: return 0
        return perLot.toLong() * count / Config.s.lotOf(id)
    }
}
