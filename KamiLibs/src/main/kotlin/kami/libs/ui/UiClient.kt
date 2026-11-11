package kami.libs.ui

import kami.libs.xaero.XaeroMaps
import kami.libs.ui.text.ItemSearch
import kami.libs.ui.gallery.Gallery
import kami.libs.ui.map.TerrainScanner
import kami.libs.ui.hud.HudBars
import kami.libs.ui.pin.Pins
import kami.libs.ui.press.Gray
import kami.libs.ui.press.clearCharWidths
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import kami.libs.ui.widget.clearTagMembers
import net.minecraft.client.Minecraft
import net.minecraft.commands.Commands
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterShadersEvent
import net.neoforged.neoforge.event.TagsUpdatedEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object UiClient {
    fun init() {
        TerrainScanner.init()
        XaeroMaps.init()
        MOD_BUS.addListener<RegisterClientReloadListenersEvent> { e ->
            e.registerReloadListener(ResourceManagerReloadListener {
                Palette.load(it)
                UiPrefs.apply()
                Format.locale = Format.localeOf(Minecraft.getInstance().languageManager.selected)
                Draw.clearWidths()
                clearCharWidths()
                ItemSearch.reset()
                Format.reset()
                Gray.reset()
            })
        }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> { clearTagMembers(); AutoRefresh.reset() }
        FORGE_BUS.addListener<TagsUpdatedEvent> { if (it.updateCause == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) clearTagMembers() }
        MOD_BUS.addListener<RegisterShadersEvent> { Gray.register(it) }
        MOD_BUS.addListener<RegisterGuiLayersEvent> { it.registerAboveAll(ResourceLocation.fromNamespaceAndPath("kami_libs", "pins")) { g, _ -> Pins.renderHud(g); HudBars.render(g) } }
        FORGE_BUS.addListener<RegisterClientCommandsEvent> { e ->
            e.dispatcher.register(Commands.literal("kamiui").requires { it.hasPermission(2) }.then(Commands.literal("gallery").executes {
                Minecraft.getInstance().tell { Gallery.open() }
                1
            }))
        }
    }
}
