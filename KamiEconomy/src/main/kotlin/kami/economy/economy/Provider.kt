package kami.economy.economy

import kami.economy.Config
import kami.economy.Market
import kami.libs.economy.MarketProvider
import kami.libs.economy.Quote
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack

object EconomyProvider : MarketProvider {
    override fun value(item: ItemStack): Long = if (item.isEmpty) 0 else valueOf(Blacklist.itemId(item), item.count)

    override fun ticker(limit: Int): List<Quote> {
        val now = System.currentTimeMillis()
        val traded = Market.data.traded.entries.sortedByDescending { it.value.sold + it.value.bought }.map { it.key }
        return (traded + Config.s.starterGoods.map { it.item }.filterNot { it.startsWith("#") }).distinct().asSequence()
            .mapNotNull { id ->
                val item = ResourceLocation.tryParse(id)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: return@mapNotNull null
                valueOf(id, Config.s.lotOf(id)).takeIf { it > 0 }?.let { Quote(item.description.string, it, History.change(id, now)) }
            }
            .take(limit).toList()
    }

    fun valueOf(id: String, count: Int): Long {
        val perLot = when {
            Stocks.infinite(id) -> Matching.bestBid(id) ?: Stocks.basePrice(id)
            Stocks.good(id) != null -> Stocks.basePrice(id)
            else -> Matching.bestPrice(id)
        } ?: return 0
        return perLot.toLong() * count / Config.s.lotOf(id)
    }
}
