package kami.economy

import kami.economy.admin.MarketAdmin
import kami.economy.client.ClientEconomy
import kami.economy.command.EconomyCommands
import kami.economy.economy.Auctions
import kami.economy.economy.Blacklist
import kami.economy.db.EcoDb
import kami.economy.economy.DiscordMarket
import kami.economy.economy.History
import kami.economy.economy.Ledger
import kami.economy.economy.Notify
import kami.economy.economy.EconomyProvider
import kami.economy.economy.Pricing
import kami.economy.economy.Starters
import kami.economy.economy.VariantScan
import kami.economy.economy.StackCodec
import kami.economy.net.Net
import kami.economy.quest.Quests
import kami.economy.quest.Dishes
import kami.economy.world.CoinRules
import kami.economy.world.Vendors
import kami.economy.world.VendorRegistry
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.event.level.BlockEvent
import kami.libs.config.Configs
import kami.libs.economy.MarketApi
import kami.libs.economy.ServerBank
import kami.libs.log.Log
import kami.libs.mc.Registry
import net.minecraft.server.level.ServerPlayer
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.event.OnDatapackSyncEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
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
            Configs.onReload("economy") { if (Notify.server != null) { Market.reindex(); Starters.push() }; null }
            MarketApi.register(EconomyProvider)
        }
        MOD_BUS.addListener<RegisterPayloadHandlersEvent> { Net.register(it) }
        if (FMLEnvironment.dist == Dist.CLIENT) ClientEconomy.init()
        EconomyCommands.register()
        EcoDb.register()
        ServerBank.keep(Ledger::holds)
        WealthSources.register()
        MarketAdmin.register()
        DiscordMarket.register()
        CoinRules.register()
        FORGE_BUS.addListener<ServerStartedEvent> { Notify.server = it.server; History.load(); VariantScan.read(it.server); Market.load(); runCatching { Dishes.scan(it.server) }.onFailure { e -> LOG.error("Dish scan failed", e) }; DiscordMarket.reset() }
        FORGE_BUS.addListener<OnDatapackSyncEvent> { if (it.player == null) runCatching { VariantScan.read(it.playerList.server); Market.variantsChanged() }.onFailure { e -> LOG.error("Variant rescan failed", e) } }
        FORGE_BUS.addListener<ServerStoppingEvent> { Notify.server = null }
        FORGE_BUS.addListener<ServerStoppedEvent>(EventPriority.LOWEST) { Market.unload(); History.unload() }
        FORGE_BUS.addListener<ServerTickEvent.Post> {
            val server = it.server
            val t = server.tickCount
            fun task(name: String, every: Int, offset: Int = 0, run: () -> Unit) {
                if ((t + offset) % every == 0) runCatching(run).onFailure { e -> LOG.error("{} tick failed", name, e) }
            }
            task("Vendor drain", 1) { VendorRegistry.drain() }
            task("Pricing", Config.s.marketTickInterval) { Pricing.tick() }
            task("Quests", Config.s.marketTickInterval, 5) { Quests.tick() }
            task("Price push", Config.s.marketTickInterval, 10) { Net.pushPrices(server) }
            task("Discord market", Config.s.marketTickInterval, 15) { DiscordMarket.tick() }
            task("Market broadcast", Config.s.marketTickInterval, 20) { Net.broadcastOpen(server) }
            task("Auctions", Config.s.auctionCheckIntervalTicks) { Auctions.sweep() }
            task("Vendors", HOUSEKEEPING_TICKS) { VendorRegistry.recheck(server) }
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { (it.entity as? ServerPlayer)?.let { p -> deliver(p, login = true); Net.sendFullPrices(p); Net.sendVariants(p) } }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { (it.entity as? ServerPlayer)?.let { p -> Net.forget(p) } }
        FORGE_BUS.addListener<PlayerInteractEvent.RightClickBlock> { Vendors.onUse(it); if (!it.isCanceled) VendorRegistry.touch(it.level, it.pos) }
        FORGE_BUS.addListener<BlockEvent.EntityPlaceEvent> { VendorRegistry.touch(it.level, it.pos) }
        FORGE_BUS.addListener<BlockEvent.BreakEvent> { VendorRegistry.touch(it.level, it.pos) }
    }

    fun deliver(p: ServerPlayer, login: Boolean = false) {
        Market.deliver(p.stringUUID, login).forEach { d ->
            runCatching {
                if (Notify.note(p, d)) return@runCatching
                if (d.stackData.isNotEmpty()) {
                    val stack = StackCodec.decode(d.stackData, p.server.registryAccess())
                    if (stack.isEmpty) park(p, d, d.id) else {
                        p.inventory.add(stack)
                        if (!stack.isEmpty) dropOwned(p, stack)
                    }
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

    fun dropOwned(p: ServerPlayer, stack: ItemStack) {
        p.drop(stack, false, true)?.setTarget(p.uuid)
    }

    fun give(p: ServerPlayer, id: String, qty: Int): Boolean {
        val proto = Blacklist.prototype(id) ?: return false
        var remaining = qty
        while (remaining > 0) {
            val n = remaining.coerceAtMost(proto.maxStackSize)
            val stack = proto.copyWithCount(n)
            remaining -= n
            p.inventory.add(stack)
            if (stack.isEmpty) continue
            val overflow = stack.count + remaining
            if (overflow > proto.maxStackSize) {
                Market.queueDelivery(p.stringUUID, id, overflow, parked = true)
                return true
            }
            dropOwned(p, stack)
        }
        return true
    }
}
