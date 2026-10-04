package kami.libs

import kami.libs.command.KamiCommands
import kami.libs.discord.KeyFile
import kami.libs.log.Log
import kami.libs.ui.UiClient
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
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
        if (FMLEnvironment.dist == Dist.CLIENT) UiClient.init()
    }
}
