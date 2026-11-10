package kami.essentials

import kami.libs.player.Presence
import kami.libs.skills.SkillProfile
import kami.libs.skills.Skills
import kami.libs.text.Phrase
import kami.libs.tunnel.Tunnel
import kami.essentials.admin.Activity
import kami.essentials.admin.AdminServer
import kami.essentials.admin.DiscordAdmin
import kami.essentials.admin.Moderation
import kami.essentials.admin.Maintenance
import kami.essentials.admin.Mutes
import kami.essentials.admin.Warns
import kami.essentials.admin.PlayerRecords
import kami.essentials.admin.ServerStats
import kami.essentials.chat.Feed
import kami.essentials.chat.InlineFeatures
import kami.essentials.chat.Talk
import kami.essentials.client.ClientEssentials
import kami.essentials.command.EssentialsCommands
import kami.essentials.discord.Bot
import kami.essentials.discord.Console
import kami.essentials.discord.Gate
import kami.essentials.display.Sidebar
import kami.essentials.display.Motd
import kami.essentials.display.Tab
import kami.essentials.inv.Offline
import kami.essentials.net.Net
import kami.essentials.op.FakeDeop
import kami.essentials.skills.LavaWalk
import kami.essentials.skills.Ravagers
import kami.essentials.skills.SkillEvents
import kami.essentials.skills.SkillServer
import kami.essentials.trade.Deliveries
import kami.essentials.trade.Trades
import kami.essentials.vanish.Vanish
import kami.essentials.world.Unstuck
import kami.libs.admin.Players
import kami.libs.chat.tell
import kami.libs.log.Log
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.common.util.TriState
import net.neoforged.neoforge.event.CommandEvent
import net.neoforged.neoforge.event.ServerChatEvent
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(KamiEssentials.ID)
object KamiEssentials {
    const val ID = "kami_essentials"
    val LOG = Log.of("essentials")

    init {
        EssMigrations.register()
        MOD_BUS.addListener<FMLCommonSetupEvent> { Config.load(); Presence.hidden = { Store[Flag.INVISIBLE, it] }; Skills.profiles = { id -> if (SkillServer.enabled) SkillServer.peek(id)?.let { SkillProfile(it.cls, it.level) } else null } }
        FORGE_BUS.addListener<PermissionGatherEvent.Nodes> { Perms.register(it) }
        MOD_BUS.addListener<RegisterPayloadHandlersEvent> { Net.register(it) }
        if (FMLEnvironment.dist == Dist.CLIENT) ClientEssentials.init()
        EssentialsCommands.register()
        AdminServer.register()
        DiscordAdmin.register()
        Activity.register()
        Maintenance.register()
        SkillEvents.register()
        Ravagers.register()
        LavaWalk.register()
        FORGE_BUS.addListener<CommandEvent> { e -> if (!e.isCanceled) Maintenance.intercept(e) }
        FORGE_BUS.addListener<ServerStartedEvent> {
            loading("player flags") { Store.load() }
            loading("player records") { PlayerRecords.load(it.server) }
            Players.known = { name -> PlayerRecords.byName(name)?.id }
            loading("mutes") { Mutes.load() }
            loading("warns") { Warns.load() }
            loading("ban links") { Moderation.load() }
            loading("deliveries") { Deliveries.load() }
            loading("maintenance") { Maintenance.load() }
            loading("skills") { SkillServer.load() }
            ServerStats.start()
            Bot.start(it.server)
            if (it.server.isDedicatedServer) runCatching { Tunnel.start() }.onFailure { e -> LOG.error("Tunnel did not start: {}", e.toString()) }
        }
        FORGE_BUS.addListener<ServerStoppingEvent> { Tunnel.stop(); Bot.stop(); Trades.stop(); FakeDeop.reset(); SkillServer.unload() }
        FORGE_BUS.addListener<ServerStoppedEvent>(EventPriority.LOWEST) { Players.known = null; PlayerRecords.unload(); Mutes.unload(); Warns.unload(); Moderation.unload(); Deliveries.unload() }
        FORGE_BUS.addListener<ServerTickEvent.Post> {
            Trades.tick()
            Offline.tick()
            Bot.tick(it.server)
            ServerStats.tick(it.server)
            SkillServer.tick(it.server)
            if (it.server.tickCount % 20 == 0) {
                Tab.tick(it.server)
                Motd.tick(it.server)
                Sidebar.tick(it.server)
                InlineFeatures.prune()
                Unstuck.tick(it.server)
                Maintenance.tick(it.server)
            }
            if (it.server.tickCount % 40 == 0) Vanish.tick(it.server)
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { e ->
            val p = e.entity as? ServerPlayer ?: return@addListener
            Offline.joined(p)
            Activity.login(p, PlayerRecords[p.uuid] == null)
            PlayerRecords.joined(p)
            Gate.join(p)
            Warns.deliver(p)
            Deliveries.deliver(p)
            Bot.presenceDirty()
            Feed.join(p)
            SkillServer.joined(p)
            if (Vanish.active(p)) p.tell(Talk.chat.info(Phrase.of("kami_essentials.vanish.joined", Phrase.value("/invis"))))
        }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { e ->
            val p = e.entity as? ServerPlayer ?: return@addListener
            Trades.drop(p, Phrase.of("kami_essentials.trade.reason.left", p.gameProfile.name))
            Activity.logout(p)
            PlayerRecords.left(p)
            Sidebar.forget(p)
            Feed.leave(p)
            Bot.presenceDirty()
            Net.forget(p)
            SkillServer.forget(p)
            FakeDeop.forget(p)
        }
        FORGE_BUS.addListener<LevelEvent.Save>(EventPriority.HIGH) { e -> (e.level as? ServerLevel)?.takeIf { it.dimension() == Level.OVERWORLD }?.let { PlayerRecords.autosave(it.server) } }
        FORGE_BUS.addListener<PlayerEvent.TabListNameFormat>(Tab::onName)
        FORGE_BUS.addListener<ServerChatEvent>(Talk::onChat)
        FORGE_BUS.addListener<CommandEvent>(Talk::onCommand)
        FORGE_BUS.addListener<CommandEvent>(Console::onCommand)
        FORGE_BUS.addListener<PlayerEvent.Clone> { e -> if (!e.isWasDeath) (e.entity as? ServerPlayer)?.let { SkillServer.carry(it, e.original.health) } }
        FORGE_BUS.addListener<PlayerEvent.PlayerRespawnEvent> { e ->
            (e.entity as? ServerPlayer)?.let { Tab.forget(it.uuid); Deliveries.deliver(it); SkillServer.respawned(it, !e.isEndConquered) }
        }
        FORGE_BUS.addListener<LivingDeathEvent> { e -> (e.entity as? ServerPlayer)?.let { Trades.drop(it, Phrase.of("kami_essentials.trade.reason.died", it.gameProfile.name)) } }
        FORGE_BUS.addListener<LivingChangeTargetEvent> { e -> if ((e.newAboutToBeSetTarget as? ServerPlayer)?.let(Vanish::active) == true) e.isCanceled = true }
        FORGE_BUS.addListener<ItemEntityPickupEvent.Pre> { e -> if (Trades.busy(e.player)) e.setCanPickup(TriState.FALSE) }
    }

    private fun loading(name: String, load: () -> Unit) {
        runCatching(load).onFailure { LOG.error("Could not load the {}", name, it) }
    }
}
