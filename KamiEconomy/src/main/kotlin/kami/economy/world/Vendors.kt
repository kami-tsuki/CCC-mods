package kami.economy.world

import kami.economy.Config
import kami.libs.claims.ClaimsApi
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent

object Vendors {
    private const val CREATIVE_VENDOR = "numismatics:creative_vendor"
    private val MARKET_ONLY = setOf("numismatics:vendor", "numismatics:creative_vendor", "numismatics:salepoint", "create:stock_ticker")

    private fun isVendorLike(id: String) = id in MARKET_ONLY || (id.startsWith("create:") && id.endsWith("_table_cloth"))

    fun onUse(e: PlayerInteractEvent.RightClickBlock) {
        val id = BuiltInRegistries.BLOCK.getKey(e.level.getBlockState(e.pos).block).toString()

        if (id == CREATIVE_VENDOR && !Config.s.allowCreativeVendors) {
            deny(e, "Creative vendors are disabled on this server.")
            return
        }
        if (!isVendorLike(id) || !ClaimsApi.present) return

        val dim = e.level.dimension().location().toString()
        val info = ClaimsApi.at(dim, e.pos.x shr 4, e.pos.z shr 4)
        if (info == null || info.type != "market") {
            deny(e, "This can only be used inside a market claim.")
            return
        }
        if (ClaimsApi.isBanished(e.entity.uuid, info.country)) deny(e, "You are banished from ${info.country}.")
    }

    private fun deny(e: PlayerInteractEvent.RightClickBlock, msg: String) {
        e.isCanceled = true
        e.entity.displayClientMessage(Component.literal(msg), true)
    }
}
