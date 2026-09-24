package kami.claims.client

import com.mojang.blaze3d.platform.InputConstants
import kami.claims.client.ui.ClaimsScreen
import kami.claims.client.ui.Ui
import kami.claims.net.Act
import kami.claims.net.Snap
import kami.claims.net.Snapshot
import kami.claims.service.View
import kami.libs.xaero.Highlights
import kotlinx.serialization.json.Json
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.network.PacketDistributor
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object ClientHooks {
    private val json = Json { ignoreUnknownKeys = true }
    private val open = KeyMapping("key.kami_claims.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.kami_claims")

    fun init() {
        Highlights.register(KamiHighlighter())
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { it.register(open) }
        MOD_BUS.addListener<RegisterGuiLayersEvent> {
            it.registerAboveAll(ResourceLocation.fromNamespaceAndPath("kami_claims", "territory")) { g, _ -> hud(g) }
        }
        FORGE_BUS.addListener<ClientTickEvent.Post> {
            while (open.consumeClick()) if (Minecraft.getInstance().screen == null) request("open")
        }
    }

    fun request(name: String, vararg args: String) = PacketDistributor.sendToServer(Act(name, args.toList(), ClientClaims.viewingAs))

    fun snapshot(s: Snapshot) {
        val snap = runCatching { json.decodeFromString<Snap>(s.json) }.getOrNull() ?: return
        ClientClaims.viewingAs = snap.info?.takeIf { it.delegated }?.name ?: ""
        val mc = Minecraft.getInstance()
        val screen = mc.screen
        if (screen is ClaimsScreen) screen.update(snap) else if (snap.open) mc.setScreen(ClaimsScreen(snap))
    }

    fun claims(payload: View.Payload) {
        ClientClaims.update(payload)
        (Minecraft.getInstance().screen as? ClaimsScreen)?.claimsChanged()
    }

    private fun hud(g: GuiGraphics) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (!ClientClaims.prefs.hud || mc.options.hideGui || mc.screen != null || ClientClaims.dims.isEmpty()) return
        val dim = player.level().dimension().location().toString()
        if (dim !in ClientClaims.dims) return
        val e = ClientClaims.at(dim, player.chunkPosition().x, player.chunkPosition().z)
        val text = if (e == null) "Nomansland" else (ClientClaims.country(e)?.name ?: "?") + (ClientClaims.typeName(e)?.let { " - $it" } ?: "")
        val color = e?.let { ClientClaims.country(it)?.color } ?: 0x888888
        val width = mc.font.width(text) + 14
        val x = (g.guiWidth() - width) / 2
        g.fill(x, 2, x + width, 13, 0x90000000.toInt())
        g.fill(x + 3, 5, x + 8, 10, Ui.rgb(color))
        g.drawString(mc.font, text, x + 11, 3, if (e == null) Ui.DIM else Ui.TEXT, false)
    }
}
