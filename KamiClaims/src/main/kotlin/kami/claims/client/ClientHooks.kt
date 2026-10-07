package kami.claims.client

import com.mojang.blaze3d.platform.InputConstants
import kami.claims.client.admin.CountryAdminSection
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.personal.ClaimsPersonal
import kami.claims.client.app.ResearchPins
import kami.claims.client.hud.Hud
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.ClientResearch
import kami.claims.net.DefsView
import kami.claims.net.StateView
import kami.claims.client.world.BlockHint
import kami.claims.client.world.Borders
import kami.claims.net.Denied
import kami.claims.net.Snap
import kami.libs.net.SnapshotPayload
import kami.libs.net.decode
import kami.claims.service.View
import kami.libs.ui.app.AppModule
import kami.libs.ui.app.Modules
import kami.libs.ui.app.OpenWait
import kami.libs.ui.app.Route
import kami.libs.ui.style.Icons
import kami.libs.ui.map.TerrainCache
import kami.libs.ui.text.tr
import kami.libs.xaero.Highlights
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.event.RenderHighlightEvent
import net.neoforged.neoforge.client.event.RenderLevelStageEvent
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object ClientHooks {
    private val open = KeyMapping("key.kami_claims.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.kami_claims")
    private val borders = KeyMapping("key.kami_claims.borders", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.kami_claims")
    private var ticks = 0
    private var echoUntil = 0L
    private val opening = OpenWait({ tr("kami_claims.open.loading") }, { tr("kami_claims.open.timeout") }, { tr("kami_claims.open.timeout.desc") })

    fun init() {
        Modules.register(AppModule("country", "kami_libs.common.country", Icons.FLAG, 10) { openApp() })
        ClaimsPersonal.register()
        CountryAdminSection.register()
        Highlights.register(KamiHighlighter())
        ResearchTooltip.init()
        ResearchPins.register()
        Hud.register()
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { it.register(open); it.register(borders) }
        MOD_BUS.addListener<RegisterGuiLayersEvent> {
            it.registerAboveAll(ResourceLocation.fromNamespaceAndPath("kami_claims", "territory")) { g, _ -> Hud.render(g); opening.render(g) }
        }
        FORGE_BUS.addListener<ClientTickEvent.Post> { tick() }
        FORGE_BUS.addListener<RenderHighlightEvent.Block> { BlockHint.highlight(it) }
        FORGE_BUS.addListener<CustomizeGuiOverlayEvent.BossEventProgress> { hideWarBar(it) }
        FORGE_BUS.addListener<RenderLevelStageEvent> { Borders.renderWalls(it) }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingIn> {
            applyPrefs()
            ClaimsStore.watch(emptyList(), force = true)
        }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> { TerrainCache.clear(); ClientResearch.clear(); ClaimsStore.clear(); opening.done() }
    }

    private fun hideWarBar(e: CustomizeGuiOverlayEvent.BossEventProgress) = runCatching {
        if (ClientClaims.prefs.warDisplay.let { it == WarDisplay.BOSS || it == WarDisplay.BOTH }) return@runCatching
        val key = (e.bossEvent?.name?.contents as? TranslatableContents)?.key ?: return@runCatching
        if (!key.startsWith("kami_claims.warbar.")) return@runCatching
        e.isCanceled = true
        e.increment = 0
    }

    private fun applyPrefs() {
        val p = ClientClaims.prefs
        TerrainCache.enabled = p.terrain
        TerrainCache.diskLimitMb = p.terrainCacheMb
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        while (open.consumeClick()) if (mc.screen == null) openApp()
        while (borders.consumeClick()) if (mc.screen == null) Borders.cycle()
        ClaimsStore.tick()
        opening.tick()
        Borders.tick()
        if (mc.player != null && ++ticks % 1200 == 0 && mc.screen == null) ClaimsStore.watch(emptyList(), force = true)
    }

    fun request(name: String, vararg args: String) = ClaimsStore.quiet(name, *args)

    fun openApp(wait: Boolean = false) {
        request("open")
        if (wait || ClaimsStore.snap == null) return opening.start()
        opening.done()
        echoUntil = System.currentTimeMillis() + ECHO_MS
        if (!ClaimsApp.instance.isOpen) ClaimsApp.open()
    }

    fun openResearch(key: String) {
        val app = ClaimsApp.instance
        val target = Route("research", focus = key)
        if (app.route == target) app.page(target.page).opened(target) else app.navigate(target, sound = false)
        openApp()
    }

    fun snapshot(s: SnapshotPayload) {
        val snap = s.decode<Snap>() ?: return
        ClaimsStore.receive(snap)
        Hud.alertsChanged()
        if (!snap.open) return
        val echo = !opening.active && System.currentTimeMillis() < echoUntil
        echoUntil = 0L
        opening.done()
        if (!echo && !ClaimsApp.instance.isOpen) ClaimsApp.open()
    }

    fun claims(payload: View.Payload) = ClientClaims.update(payload)

    fun denied(d: Denied) = BlockHint.denied(d)

    fun researchDefs(defs: DefsView) = ClientResearch.receive(defs)

    fun researchState(state: StateView) = ClientResearch.receive(state)

    private const val ECHO_MS = 10_000L
}
