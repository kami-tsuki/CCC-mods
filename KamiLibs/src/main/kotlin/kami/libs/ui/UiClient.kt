package kami.libs.ui

import kami.libs.ui.gallery.Gallery
import kami.libs.ui.map.TerrainScanner
import kami.libs.ui.pin.Pins
import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object UiClient {
    fun init() {
        TerrainScanner.init()
        MOD_BUS.addListener<RegisterClientReloadListenersEvent> { e ->
            e.registerReloadListener(ResourceManagerReloadListener {
                Palette.load(it)
                Format.locale = Format.localeOf(Minecraft.getInstance().languageManager.selected)
            })
        }
        MOD_BUS.addListener<RegisterGuiLayersEvent> { it.registerAboveAll(ResourceLocation.fromNamespaceAndPath("kami_libs", "pins")) { g, _ -> Pins.renderHud(g) } }
        FORGE_BUS.addListener<RegisterClientCommandsEvent> { e ->
            e.dispatcher.register(Commands.literal("kamiui").requires { it.hasPermission(2) }.then(Commands.literal("gallery").executes {
                Minecraft.getInstance().tell { Gallery.open() }
                1
            }))
        }
    }
}
