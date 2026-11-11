package kami.claims.client

import com.mojang.blaze3d.platform.InputConstants
import kami.claims.client.admin.CountryAdminSection
import kami.claims.client.admin.PressAdminSection
import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.personal.ClaimsPersonal
import kami.claims.client.app.ResearchPins
import kami.claims.client.hud.Hud
import kami.claims.client.compat.JadeClient
import kami.claims.client.compat.freecam.FreecamGuard
import kami.claims.client.compat.jei.ResearchJei
import kami.claims.client.compat.xaero.XaeroClaims
import kami.libs.jei.KamiJei
import kami.claims.client.map.MiniMap
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.ClientNews
import kami.claims.client.store.ClientResearch
import kami.claims.net.Act
import kami.claims.net.DefsView
import kami.claims.net.StateView
import kami.claims.client.world.BlockHint
import kami.claims.client.world.Borders
import kami.claims.client.world.NameTags
import kami.claims.net.TagView
import java.util.UUID
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
        PressAdminSection.register()
        XaeroClaims.register()
        JadeClient.register()
        FreecamGuard.register()
        if (KamiJei.present) KamiJei.module(ResearchJei)
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
        FORGE_BUS.addListener<RenderNameTagEvent> { if (it.entity is net.minecraft.world.entity.player.Player && VisibilityState.isHidden(it.entity.uuid)) it.setCanRender(TriState.FALSE) else NameTags.render(it) }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingIn> {
            applyPrefs()
            VisibilityState.clear()
            NameTags.clear()
            if (hasClaimsChannel()) ClaimsStore.watch(emptyList(), force = true)
        }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> { ClaimsApp.logout(); TerrainCache.clear(); ClientResearch.clear(); ClientNews.clear(); ClaimsStore.clear(); opening.done(); MiniMap.clear(); Hud.reset(); VisibilityState.clear(); NameTags.clear(); ClientClaims.clear(); FreecamGuard.clear() }
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
        if (mc.player != null && ++ticks % 1200 == 0 && mc.screen == null) Hud.idleWatch().takeIf { it.isNotEmpty() }?.let { ClaimsStore.watch(it, force = true) }
    }

    private fun hasClaimsChannel() = Minecraft.getInstance().connection?.hasChannel(Act.type) == true

    fun request(name: String, vararg args: String) { if (hasClaimsChannel()) ClaimsStore.quiet(name, *args) }

    fun openApp(wait: Boolean = false) {
        request("open")
        if (wait || ClaimsStore.snap == null) return if (hasClaimsChannel()) opening.start() else opening.fail()
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

    fun nameTags(t: Map<UUID, TagView>) = NameTags.receive(t)

    fun freecam(state: Int) = FreecamGuard.receive(state)

    fun researchDefs(defs: DefsView) = ClientResearch.receive(defs)

    fun researchState(state: StateView) = ClientResearch.receive(state)

    fun news(kind: String, json: String) = ClientNews.receive(kind, json)

    private const val ECHO_MS = 10_000L
}
