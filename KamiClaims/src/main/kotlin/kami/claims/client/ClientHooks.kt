package kami.claims.client

import com.mojang.blaze3d.platform.InputConstants
import kami.claims.client.app.ClaimsApp
import kami.claims.client.hud.Hud
import kami.claims.client.store.ClaimsStore
import kami.claims.client.world.BlockHint
import kami.claims.client.world.Borders
import kami.claims.net.Denied
import kami.claims.net.Snap
import kami.claims.net.Snapshot
import kami.claims.service.View
import kami.libs.ui.app.AppScreen
import kami.libs.ui.map.TerrainCache
import kami.libs.ui.style.Palette
import kami.libs.xaero.Highlights
import kotlinx.serialization.json.Json
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.RenderHighlightEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object ClientHooks {
    private val json = Json { ignoreUnknownKeys = true }
    private val open = KeyMapping("key.kami_claims.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.kami_claims")
    private val borders = KeyMapping("key.kami_claims.borders", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.kami_claims")
    private var ticks = 0

    fun init() {
        Highlights.register(KamiHighlighter())
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { it.register(open); it.register(borders) }
        MOD_BUS.addListener<RegisterGuiLayersEvent> {
            it.registerAboveAll(ResourceLocation.fromNamespaceAndPath("kami_claims", "territory")) { g, _ -> Hud.render(g) }
        }
        FORGE_BUS.addListener<ClientTickEvent.Post> { tick() }
        FORGE_BUS.addListener<RenderHighlightEvent.Block> { BlockHint.highlight(it) }
        FORGE_BUS.addListener<RenderLevelStageEvent> { Borders.renderWalls(it) }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingIn> {
            applyPrefs()
            ClaimsStore.quiet("watch")
        }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> { TerrainCache.clear() }
    }

    private fun applyPrefs() {
        val p = ClientClaims.prefs
        TerrainCache.enabled = p.terrain
        TerrainCache.diskLimitMb = p.terrainCacheMb
        Palette.vision = runCatching { Palette.Vision.valueOf(p.vision) }.getOrDefault(Palette.Vision.NORMAL)
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        while (open.consumeClick()) if (mc.screen == null) request("open")
        while (borders.consumeClick()) if (mc.screen == null) Borders.cycle()
        ClaimsStore.tick()
        Borders.tick()
        if (mc.player != null && ++ticks % 1200 == 0 && mc.screen == null) ClaimsStore.quiet("watch")
    }

    fun request(name: String, vararg args: String) = ClaimsStore.quiet(name, *args)

    fun snapshot(s: Snapshot) {
        val snap = runCatching { json.decodeFromString<Snap>(s.json) }.getOrNull() ?: return
        ClaimsStore.receive(snap)
        Hud.alertsChanged()
        val mc = Minecraft.getInstance()
        val screen = mc.screen
        if (snap.open && !(screen is AppScreen && screen.app is ClaimsApp)) ClaimsApp.open()
    }

    fun claims(payload: View.Payload) = ClientClaims.update(payload)

    fun denied(d: Denied) = BlockHint.denied(d)
}
