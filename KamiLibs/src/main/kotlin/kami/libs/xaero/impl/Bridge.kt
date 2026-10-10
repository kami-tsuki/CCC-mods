package kami.libs.xaero.impl

import kami.libs.KamiLibs
import kami.libs.ui.app.Personal
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object Bridge {
    private var ticks = 0
    private var fairTicks = 0
    private var forced = false
    private var radarTicks = 0

    private const val RADAR_GRACE = 100

    fun init() {
        Personal.register(MapsSection.section)
        MapScreenButtons.register()
        FORGE_BUS.addListener<ClientTickEvent.Post> { tick() }
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> {
            MinimapAdapter.clear()
            if (Detect.plus) PlusAdapter.clear()
            if (Detect.worldMap) WorldMapAdapter.clear()
            Registry.clearTemp()
            forced = false
        }
    }

    private fun tick() {
        if (!Detect.any) return
        if (Detect.minimap) {
            MinimapAdapter.pollScreen()
            countRadar()
        }
        if (Minecraft.getInstance().level == null) return
        val dirty = Registry.take()
        if (!dirty && ++ticks % 20 != 0) return
        clearReachedTemp()
        if (Detect.worldMap) WorldMapAdapter.tick(dirty)
        if (Detect.minimap) {
            MinimapAdapter.tick(dirty)
            fairFallback()
        }
        if (Detect.plus) PlusAdapter.tick(dirty)
    }

    private fun clearReachedTemp() {
        val t = Registry.temp ?: return
        val player = Minecraft.getInstance().player ?: return
        if (player.level().dimension().location().toString() != t.dim) return
        val dx = player.x - (t.x + 0.5)
        val dz = player.z - (t.z + 0.5)
        if (dx * dx + dz * dz < 36) Registry.clearTemp()
    }

    private fun countRadar() {
        if (Registry.radarHooked || radarTicks >= RADAR_GRACE) return
        val level = Minecraft.getInstance().level ?: return
        if (level.players().size > 1 && (Registry.radar.isNotEmpty() || Registry.fairPlayActive()) && ++radarTicks == RADAR_GRACE) {
            KamiLibs.LOG.warn("Xaero's radar hook never ran, forcing fair play so war-hidden players stay hidden")
        }
    }

    private fun fairFallback() {
        val fair = Registry.fairPlayActive()
        val radarGone = !Registry.radarHooked && radarTicks >= RADAR_GRACE
        if (radarGone || fair && !Registry.caveHooked) {
            if (++fairTicks >= 3 && !forced) {
                forced = true
                MinimapAdapter.forceFairPlay(true)
            }
        } else {
            fairTicks = 0
            if (forced && !fair) {
                forced = false
                MinimapAdapter.forceFairPlay(false)
            }
        }
    }
}
