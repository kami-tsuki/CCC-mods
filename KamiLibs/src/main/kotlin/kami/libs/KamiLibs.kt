package kami.libs

import kami.libs.command.KamiCommands
import kami.libs.config.Configs
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import org.slf4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(KamiLibs.ID)
object KamiLibs {
    const val ID = "kami_libs"
    val LOG: Logger = Configs.log

    init {
        KamiCommands.module("library", "Shared settings of all Kami mods") {}
        MOD_BUS.addListener<FMLCommonSetupEvent> { LibConfig.file.load() }
        FORGE_BUS.addListener<RegisterCommandsEvent> { KamiCommands.register(it.dispatcher) }
    }
}
