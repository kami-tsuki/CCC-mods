package kami.libs

import kami.libs.admin.Admin
import kami.libs.admin.AdminClient
import kami.libs.admin.AdminNet
import kami.libs.admin.AdminPerms
import kami.libs.admin.AdminViews
import kami.libs.admin.AutoRefreshNet
import kami.libs.net.VersionCheck
import kami.libs.command.KamiCommands
import kami.libs.db.Db
import kami.libs.db.DbWatch
import kami.libs.db.SavePoint
import kami.libs.discord.Codes
import kami.libs.discord.DiscordBans
import kami.libs.discord.DiscordPrefs
import kami.libs.discord.KeyFile
import kami.libs.discord.Links
import kami.libs.economy.CoinWatch
import kami.libs.economy.Coins
import kami.libs.economy.MoneyGuard
import kami.libs.economy.ServerBank
import kami.libs.economy.Wealth
import kami.libs.stats.Staff
import kami.libs.stats.Stats
import kami.libs.log.Log
import kami.libs.ui.UiClient
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.event.CommandEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(KamiLibs.ID)
object KamiLibs {
    const val ID = "kami_libs"
    @JvmField
    val LOG = Log.of("library")

    init {
        AdminViews.register()
        CoinWatch.register()
        Wealth.builtins()
        KamiCommands.module("library", "Shared settings of all Kami mods") {}
        MOD_BUS.addListener<FMLCommonSetupEvent> { LibConfig.file.load() }
        MOD_BUS.addListener<RegisterPayloadHandlersEvent> { AdminNet.register(it); AutoRefreshNet.register(it); VersionCheck.register(it) }
        FORGE_BUS.addListener<PermissionGatherEvent.Nodes> { AdminPerms.register(it) }
        FORGE_BUS.addListener<RegisterCommandsEvent> { KamiCommands.register(it.dispatcher) }
        FORGE_BUS.addListener<ServerStartingEvent> { if (it.server.isDedicatedServer) KeyFile.load() }
        FORGE_BUS.addListener<ServerAboutToStartEvent>(EventPriority.HIGHEST) {
            Db.open(it.server)
            ServerBank.load()
            Wealth.load()
            Coins.load()
            runCatching { CoinWatch.ensureIndex() }.onFailure { LOG.warn("Could not index coin ledger: {}", it.message) }
            MoneyGuard.load()
            Stats.load()
            Staff.clear()
            Links.load()
            DiscordBans.load()
            DiscordPrefs.load()
            Codes.load()
            AutoRefreshNet.load()
        }
        FORGE_BUS.addListener<ServerTickEvent.Post> { Wealth.tick(); MoneyGuard.tick(it.server); CoinWatch.tick(it.server); Stats.tick(); if (it.server.tickCount % 20 == 0) Staff.tick(it.server.playerList.players); DbWatch.tick(it.server); AutoRefreshNet.tick(it.server) }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent>(EventPriority.HIGHEST) { (it.entity as? ServerPlayer)?.let { p -> Staff.refresh(p); AutoRefreshNet.login(p) } }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent>(EventPriority.LOWEST) { Admin.forget(it.entity.uuid); Coins.logout(it.entity.uuid); Staff.forget(it.entity.uuid) }
        FORGE_BUS.addListener<PlayerEvent.PlayerChangeGameModeEvent>(EventPriority.LOWEST) { e -> if (!e.isCanceled) (e.entity as? ServerPlayer)?.let { Staff.refresh(it, e.newGameMode) } }
        FORGE_BUS.addListener<CommandEvent>(EventPriority.LOWEST) { e -> if (!e.isCanceled) runCatching { Staff.command(e) } }
        FORGE_BUS.addListener<LevelEvent.Save> {
            if ((it.level as? ServerLevel)?.dimension() != Level.OVERWORLD) return@addListener
            Stats.flush()
            SavePoint.run()
        }
        FORGE_BUS.addListener<ServerStoppedEvent>(EventPriority.LOW) { Stats.flush(); SavePoint.run() }
        FORGE_BUS.addListener<ServerStoppedEvent>(EventPriority.LOWEST) { Db.close() }
        if (FMLEnvironment.dist == Dist.CLIENT) {
            UiClient.init()
            AdminClient.init()
        }
    }
}
