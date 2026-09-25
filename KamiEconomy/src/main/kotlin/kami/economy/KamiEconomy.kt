package kami.economy

import kami.economy.client.ClientEconomy
import kami.economy.command.EconomyCommands
import kami.economy.economy.Auctions
import kami.economy.economy.History
import kami.economy.economy.Matching
import kami.economy.economy.Pricing
import kami.economy.economy.StackCodec
import kami.economy.net.Net
import kami.economy.world.Vendors
import kami.libs.economy.MarketApi
import kami.libs.economy.MarketProvider
import kami.libs.log.Log
import kami.libs.mc.Registry
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(KamiEconomy.ID)
object KamiEconomy {
    const val ID = "kami_economy"
    val LOG = Log.of("economy")
    val registry = Registry(LOG)

    init {
        MOD_BUS.addListener<FMLCommonSetupEvent> {
            Config.load()
            MarketApi.register(object : MarketProvider {
                override fun bestPrice(item: String) = Matching.bestPrice(item)
                override fun available(item: String) = Matching.available(item)
            })
        }
        MOD_BUS.addListener<RegisterPayloadHandlersEvent> { Net.register(it) }
        if (FMLEnvironment.dist == Dist.CLIENT) ClientEconomy.init()
        EconomyCommands.register()
        FORGE_BUS.addListener<ServerStartedEvent> { Market.load(it.server); History.load(it.server) }
        FORGE_BUS.addListener<ServerStoppingEvent> { Market.save(true); History.save(true) }
        FORGE_BUS.addListener<ServerTickEvent.Post> {
            val server = it.server
            val t = server.tickCount
            if (t % Config.s.marketTickInterval == 0) {
                Pricing.tick()
                Net.pushPrices(server)
                Net.broadcastOpen(server)
            }
            if (t % Config.s.auctionCheckIntervalTicks == 0) Auctions.sweep()
            if (t % 6000 == 0) { Market.save(); History.save() }
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { (it.entity as? ServerPlayer)?.let { p -> deliver(p); Net.sendFullPrices(p) } }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { (it.entity as? ServerPlayer)?.let { p -> Net.forget(p) } }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickBlock> { Vendors.onUse(it) }
    }

    fun deliver(p: ServerPlayer) {
        Market.deliver(p.stringUUID).forEach { d ->
            if (d.stackData.isNotEmpty()) {
                val stack = StackCodec.decode(d.stackData, p.server.registryAccess())
                if (!stack.isEmpty && !p.inventory.add(stack)) p.drop(stack, false)
                return@forEach
            }
            val item = registry.findItem(d.item) ?: return@forEach
            var remaining = d.qty
            while (remaining > 0) {
                val n = remaining.coerceAtMost(item.defaultMaxStackSize)
                val stack = ItemStack(item, n)
                if (!p.inventory.add(stack)) p.drop(stack, false)
                remaining -= n
            }
        }
    }
}
