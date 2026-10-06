package kami.libs

import kami.libs.command.KamiCommands
import kami.libs.discord.KeyFile
import kami.libs.economy.Statement
import kami.libs.log.Log
import kami.libs.ui.UiClient
import net.minecraft.world.level.Level
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(KamiLibs.ID)
object KamiLibs {
    const val ID = "kami_libs"
    @JvmField
    val LOG = Log.of("library")

    init {
        KamiCommands.module("library", "Shared settings of all Kami mods") {}
        MOD_BUS.addListener<FMLCommonSetupEvent> { LibConfig.file.load() }
        FORGE_BUS.addListener<RegisterCommandsEvent> { KamiCommands.register(it.dispatcher) }
        FORGE_BUS.addListener<ServerStartingEvent> { if (it.server.isDedicatedServer) KeyFile.load() }
        FORGE_BUS.addListener<ServerAboutToStartEvent> { Statement.load(it.server) }
        FORGE_BUS.addListener<LevelEvent.Save> { if ((it.level as? Level)?.dimension() == Level.OVERWORLD) Statement.save() }
        FORGE_BUS.addListener<ServerStoppedEvent> { Statement.save() }
        if (FMLEnvironment.dist == Dist.CLIENT) UiClient.init()
    }
}
