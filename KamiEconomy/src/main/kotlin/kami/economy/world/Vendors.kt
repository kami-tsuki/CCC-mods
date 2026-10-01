package kami.economy.world

import kami.libs.text.Phrase
import kami.economy.Config
import kami.economy.economy.Gate
import kami.libs.claims.ClaimsApi
import kami.libs.claims.Locks
import net.minecraft.core.registries.BuiltInRegistries
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent

object Vendors {
    private const val CREATIVE_VENDOR = "numismatics:creative_vendor"
    private val MARKET_ONLY = setOf("numismatics:vendor", "numismatics:creative_vendor", "numismatics:salepoint", "create:stock_ticker")

    private fun isVendorLike(id: String) = id in MARKET_ONLY || (id.startsWith("create:") && id.endsWith("_table_cloth"))

    fun onUse(e: PlayerInteractEvent.RightClickBlock) {
        val id = BuiltInRegistries.BLOCK.getKey(e.level.getBlockState(e.pos).block).toString()

        if (id == CREATIVE_VENDOR && !Config.s.allowCreativeVendors) {
            deny(e, Phrase.of("kami_economy.vendor.creative_off"))
            return
        }
        if (!isVendorLike(id) || !ClaimsApi.present) return

        val dim = e.level.dimension().location().toString()
        val info = ClaimsApi.at(dim, e.pos.x shr 4, e.pos.z shr 4)
        if (info == null || info.type != "market") {
            deny(e, Phrase.of("kami_economy.vendor.market_only"))
            return
        }
        if (ClaimsApi.isBanished(e.entity.uuid, info.country)) return deny(e, Phrase.of("kami_economy.vendor.banished", Phrase.value(info.country)))
        Gate.locked(e.entity.uuid, Gate.VENDORS)?.let { return deny(e, it) }
        val home = ClaimsApi.countryOf(e.entity.uuid) ?: return deny(e, Locks.noCountry())
        if (!ClaimsApi.canTrade(home, info.country)) deny(e, Locks.embargo(ClaimsApi.country(info.country)?.name ?: info.country))
    }

    private fun deny(e: PlayerInteractEvent.RightClickBlock, msg: Phrase) {
        e.isCanceled = true
        e.entity.displayClientMessage(Chat.bar(Tone.BAD, msg), true)
    }
}
