package kami.essentials

import kami.essentials.chat.Feed
import kami.essentials.chat.Talk
import kami.essentials.command.EssentialsCommands
import kami.essentials.display.Sidebar
import kami.essentials.display.Tab
import kami.essentials.inv.Offline
import kami.essentials.trade.Trades
import kami.essentials.vanish.Vanish
import kami.libs.chat.tell
import kami.libs.log.Log
import net.minecraft.server.level.ServerPlayer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.common.util.TriState
import net.neoforged.neoforge.event.ServerChatEvent
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(KamiEssentials.ID)
object KamiEssentials {
    const val ID = "kami_essentials"
    val LOG = Log.of("essentials")

    init {
        MOD_BUS.addListener<FMLCommonSetupEvent> { Config.load() }
        FORGE_BUS.addListener<PermissionGatherEvent.Nodes> { Perms.register(it) }
        EssentialsCommands.register()
        FORGE_BUS.addListener<ServerStartedEvent> { Store.load(it.server) }
        FORGE_BUS.addListener<ServerStoppingEvent> { Trades.stop() }
        FORGE_BUS.addListener<ServerTickEvent.Post> {
            Trades.tick()
            if (it.server.tickCount % 20 == 0) {
                Tab.tick(it.server)
                Sidebar.tick(it.server)
            }
            if (it.server.tickCount % 40 == 0) Vanish.tick(it.server)
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { e ->
            val p = e.entity as? ServerPlayer ?: return@addListener
            Offline.joined(p)
            Feed.join(p)
            if (Vanish.active(p)) p.tell(Talk.chat.info("You joined {invisible}. Use {/invis} to show yourself."))
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { e ->
            val p = e.entity as? ServerPlayer ?: return@addListener
            Trades.drop(p, "${p.gameProfile.name} left")
            Sidebar.forget(p)
            Feed.leave(p)
        }
        FORGE_BUS.addListener<PlayerEvent.TabListNameFormat>(Tab::onName)
        FORGE_BUS.addListener<ServerChatEvent>(Talk::onChat)
        FORGE_BUS.addListener<LivingDeathEvent> { e -> (e.entity as? ServerPlayer)?.let { Trades.drop(it, "${it.gameProfile.name} died") } }
        FORGE_BUS.addListener<LivingChangeTargetEvent> { e -> if ((e.newAboutToBeSetTarget as? ServerPlayer)?.let(Vanish::active) == true) e.isCanceled = true }
        FORGE_BUS.addListener<ItemEntityPickupEvent.Pre> { e -> if (Trades.busy(e.player)) e.setCanPickup(TriState.FALSE) }
    }
}
