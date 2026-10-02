package kami.economy

import kami.economy.client.ClientEconomy
import kami.economy.command.EconomyCommands
import kami.economy.economy.Auctions
import kami.economy.economy.Blacklist
import kami.economy.economy.History
import kami.economy.economy.EconomyProvider
import kami.economy.economy.Pricing
import kami.economy.economy.Stocks
import kami.economy.economy.StackCodec
import kami.economy.net.Net
import kami.economy.world.Vendors
import kami.economy.world.VendorRegistry
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.LevelEvent
import kami.libs.economy.MarketApi
import kami.libs.log.Log
import kami.libs.mc.Registry
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
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

val LOG = Log.of("economy")

@Mod(KamiEconomy.ID)
object KamiEconomy {
    const val ID = "kami_economy"
    private const val HOUSEKEEPING_TICKS = 6000
    val registry = Registry(LOG)

    init {
        MOD_BUS.addListener<FMLCommonSetupEvent> {
            Config.load()
            MarketApi.register(EconomyProvider)
        }
        MOD_BUS.addListener<RegisterPayloadHandlersEvent> { Net.register(it) }
        if (FMLEnvironment.dist == Dist.CLIENT) ClientEconomy.init()
        EconomyCommands.register()
        FORGE_BUS.addListener<ServerStartedEvent> { Market.load(it.server); History.load(it.server) }
        FORGE_BUS.addListener<ServerStoppingEvent> { Market.save(true); History.save(true) }
        FORGE_BUS.addListener<LevelEvent.Save> { if ((it.level as? Level)?.dimension() == Level.OVERWORLD) { Market.save(); History.save() } }
        FORGE_BUS.addListener<ServerTickEvent.Post> {
            val server = it.server
            val t = server.tickCount
            VendorRegistry.drain()
            if (t % Config.s.marketTickInterval == 0) {
                Stocks.rollover()
                Pricing.tick()
                Net.pushPrices(server)
                Net.broadcastOpen(server)
            }
            if (t % Config.s.auctionCheckIntervalTicks == 0) Auctions.sweep()
            if (t % HOUSEKEEPING_TICKS == 0) { VendorRegistry.recheck(server); Market.save(); History.save() }
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { (it.entity as? ServerPlayer)?.let { p -> deliver(p, login = true); Net.sendFullPrices(p) } }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { (it.entity as? ServerPlayer)?.let { p -> Net.forget(p) } }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickBlock> { Vendors.onUse(it); if (!it.isCanceled) VendorRegistry.touch(it.level, it.pos) }
        FORGE_BUS.addListener<BlockEvent.EntityPlaceEvent> { VendorRegistry.touch(it.level, it.pos) }
        FORGE_BUS.addListener<BlockEvent.BreakEvent> { VendorRegistry.touch(it.level, it.pos) }
    }

    fun deliver(p: ServerPlayer, login: Boolean = false) {
        Market.deliver(p.stringUUID, login).forEach { d ->
            runCatching {
                if (d.stackData.isNotEmpty()) {
                    val stack = StackCodec.decode(d.stackData, p.server.registryAccess())
                    if (stack.isEmpty) park(p, d, d.id) else if (!p.inventory.add(stack)) p.drop(stack, false)
                } else if (!give(p, d.item, d.qty)) park(p, d, d.item)
            }.onFailure { e ->
                LOG.error("Delivery {} for {} failed, parked", d.id.ifEmpty { d.item }, p.name.string, e)
                runCatching { park(p, d, d.id.ifEmpty { d.item }) }
            }
        }
    }

    private fun park(p: ServerPlayer, d: Delivery, what: String) {
        if (!d.parked) LOG.error("Could not resolve delivery {} for {}, parked until login", what, p.name.string)
        if (d.stackData.isNotEmpty()) Market.queueStackDelivery(p.stringUUID, d.stackData, d.id, parked = true)
        else Market.queueDelivery(p.stringUUID, d.item, d.qty, d.id, parked = true)
    }

    fun give(p: ServerPlayer, id: String, qty: Int): Boolean {
        val proto = Blacklist.prototype(id) ?: return false
        var remaining = qty
        while (remaining > 0) {
            val n = remaining.coerceAtMost(proto.maxStackSize)
            val stack = proto.copyWithCount(n)
            if (!p.inventory.add(stack)) p.drop(stack, false)
            remaining -= n
        }
        return true
    }
}
