package kami.economy.compat

import kami.economy.economy.Trade
import kami.economy.net.vendorVisible
import kami.economy.world.VendorRegistry
import kami.libs.claims.ClaimsApi
import kami.libs.jade.KamiJade
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation

object JadeEconomy {
    val VENDOR: ResourceLocation = ResourceLocation.fromNamespaceAndPath("kami_economy", "vendor")

    fun register() = KamiJade.blockData(VENDOR, { VendorRegistry.isVendor(it.level, it.pos) }) { player, pos ->
        val level = player.serverLevel()
        val dim = level.dimension().location().toString()
        val v = VendorRegistry.at(dim, pos.x, pos.y, pos.z)
        if (v == null || v.item.isEmpty() || !vendorVisible(v, Trade.country(player.stringUUID), ClaimsApi::hostile)) null
        else CompoundTag().apply {
            putString("item", v.item)
            putInt("price", v.price)
            putInt("count", v.count)
            putBoolean("sell", v.sell)
        }
    }
}
