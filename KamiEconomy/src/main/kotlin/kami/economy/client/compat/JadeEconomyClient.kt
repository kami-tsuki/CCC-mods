package kami.economy.client.compat

import kami.economy.client.MarketLink
import kami.economy.client.PriceCache
import kami.economy.compat.JadeEconomy
import kami.libs.jade.KamiJade
import kami.libs.mc.ItemSpec
import kami.libs.ui.style.Format
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Items

object JadeEconomyClient {
    private val PRICE = ResourceLocation.fromNamespaceAndPath("kami_economy", "price")

    fun register() {
        KamiJade.block(PRICE) { look ->
            val item = look.state.block.asItem()
            if (item == Items.AIR) return@block emptyList()
            val id = MarketLink.market(item.defaultInstance) ?: return@block emptyList()
            val price = PriceCache.shown(id)?.price ?: return@block emptyList()
            val lot = PriceCache.shownLot(id) ?: return@block emptyList()
            listOf(Component.translatable("kami_economy.jade.price", Format.rate(price.toDouble() / lot)))
        }
        KamiJade.block(JadeEconomy.VENDOR) { look ->
            val tag = look.data.getCompound(JadeEconomy.VENDOR.toString())
            val stack = ItemSpec.stack(tag.getString("item"))
            if (stack.isEmpty) return@block emptyList()
            listOf(Component.translatable(if (tag.getBoolean("sell")) "kami_economy.jade.vendor.sell" else "kami_economy.jade.vendor.buy", tag.getInt("count"), stack.hoverName, Format.money(tag.getInt("price").toLong())))
        }
    }
}
