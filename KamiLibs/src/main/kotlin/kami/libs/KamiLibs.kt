package kami.libs

import kami.libs.config.ConfigCommand
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(KamiLibs.ID)
object KamiLibs {
    const val ID = "kami_libs"
    val LOG: Logger = LoggerFactory.getLogger("kami_libs")

    init {
        MOD_BUS.addListener<FMLCommonSetupEvent> { LibConfig.file.load() }
        FORGE_BUS.addListener<RegisterCommandsEvent> { ConfigCommand.register(it.dispatcher) }
    }
}
