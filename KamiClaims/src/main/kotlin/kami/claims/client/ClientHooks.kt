package kami.claims.client

import com.mojang.blaze3d.platform.InputConstants
import kami.claims.client.admin.CountryAdminSection
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.personal.ClaimsPersonal
import kami.claims.client.app.ResearchPins
import kami.claims.client.hud.Hud
import kami.claims.client.xaero.XaeroClaims
import kami.claims.client.map.MiniMap
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.ClientResearch
import kami.claims.net.DefsView
import kami.claims.net.StateView
import kami.claims.client.world.BlockHint
import kami.claims.client.world.Borders
import kami.claims.net.Denied
import kami.claims.net.Snap
import kami.claims.war.Selection
import kami.libs.net.SnapshotPayload
import kami.libs.net.decode
import kami.claims.service.View
import kami.libs.ui.app.AppModule
import kami.libs.ui.app.Modules
import kami.libs.ui.app.OpenWait
import kami.libs.ui.app.registerOpenKey
import kami.libs.ui.app.Route
import kami.libs.ui.style.Icons
import kami.libs.ui.map.TerrainCache
import kami.libs.ui.text.tr
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
import net.neoforged.neoforge.client.event.RenderNameTagEvent
import net.neoforged.neoforge.common.util.TriState
import org.lwjgl.glfw.GLFW
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

object ClientHooks {
    private val open = registerOpenKey("kami_claims", GLFW.GLFW_KEY_K) { openApp() }
    fun openKey(): String = open.translatedKeyMessage.string
    fun bordersKey(): String = borders.translatedKeyMessage.string
    fun openKeyId() = open.key
    private val borders = KeyMapping("key.kami_claims.borders", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.kami_claims")
    private var ticks = 0
    private var echoUntil = 0L
    private val opening = OpenWait({ tr("kami_libs.common.loading") }, { tr("kami_claims.open.timeout") }, { tr("kami_libs.common.no_answer") })

    fun init() {
        Modules.register(AppModule("country", "kami_libs.common.country", Icons.FLAG, 10) { openApp() })
        ClaimsPersonal.register()
        CountryAdminSection.register()
        XaeroClaims.register()
        ResearchTooltip.init()
        ResearchPins.register()
        Hud.register()
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { it.register(borders) }
        opening.register("kami_claims")
        MOD_BUS.addListener<RegisterGuiLayersEvent> {
            it.registerAboveAll(ResourceLocation.fromNamespaceAndPath("kami_claims", "territory")) { g, _ -> Hud.render(g) }
        }
        FORGE_BUS.addListener<ClientTickEvent.Post> { tick() }
        FORGE_BUS.addListener<RenderHighlightEvent.Block> { BlockHint.highlight(it) }
        FORGE_BUS.addListener<CustomizeGuiOverlayEvent.BossEventProgress> { hideWarBar(it) }
        FORGE_BUS.addListener<RenderLevelStageEvent> { Borders.renderWalls(it) }
        FORGE_BUS.addListener<RenderNameTagEvent> { if (VisibilityState.isHidden(it.entity.uuid)) it.setCanRender(TriState.FALSE) }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingIn> {
            applyPrefs()
            VisibilityState.clear()
            ClaimsStore.watch(emptyList(), force = true)
        }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> { TerrainCache.clear(); ClientResearch.clear(); ClaimsStore.clear(); opening.done(); MiniMap.clear(); Hud.reset(); VisibilityState.clear(); ClientClaims.war = 0 }
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
        while (borders.consumeClick()) if (mc.screen == null) Borders.cycle()
        ClaimsStore.tick()
        Borders.tick()
        if (mc.player != null && ++ticks % 1200 == 0 && mc.screen == null) ClaimsStore.watch(Hud.idleWatch(), force = true)
    }

    fun request(name: String, vararg args: String) = ClaimsStore.quiet(name, *args)

    fun openApp(wait: Boolean = false) {
        request("open")
        if (wait || ClaimsStore.snap == null) return opening.start()
        opening.done()
        echoUntil = System.currentTimeMillis() + ECHO_MS
        if (!ClaimsApp.instance.isOpen) ClaimsApp.open()
    }

    fun openRoute(target: Route) {
        val app = ClaimsApp.instance
        if (app.route == target) app.page(target.page).opened(target) else app.navigate(target, sound = false)
        openApp()
    }

    fun openResearch(key: String) = openRoute(Route("research", focus = key))

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

    fun positions(s: Selection) = VisibilityState.receive(s)

    fun researchDefs(defs: DefsView) = ClientResearch.receive(defs)

    fun researchState(state: StateView) = ClientResearch.receive(state)

    private const val ECHO_MS = 10_000L
}
