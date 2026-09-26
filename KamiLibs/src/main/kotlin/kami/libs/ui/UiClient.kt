package kami.libs.ui

import kami.libs.ui.gallery.Gallery
import kami.libs.ui.map.TerrainScanner
import kami.libs.ui.style.Palette
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object UiClient {
    fun init() {
        TerrainScanner.init()
        MOD_BUS.addListener<RegisterClientReloadListenersEvent> { e ->
            e.registerReloadListener(ResourceManagerReloadListener { Palette.load(it) })
        }
        FORGE_BUS.addListener<RegisterClientCommandsEvent> { e ->
            e.dispatcher.register(Commands.literal("kamiui").then(Commands.literal("gallery").executes {
                Minecraft.getInstance().tell { Gallery.open() }
                1
            }))
        }
    }
}
