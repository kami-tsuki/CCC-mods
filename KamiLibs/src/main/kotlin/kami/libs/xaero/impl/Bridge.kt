package kami.libs.xaero.impl

import kami.libs.ui.app.Personal
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object Bridge {
    private var ticks = 0
    private var fairTicks = 0
    private var forced = false

    fun init() {
        Personal.register(MapsSection.section)
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
        if (Detect.minimap) MinimapAdapter.pollScreen()
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

    private fun fairFallback() {
        val fair = Registry.fairPlayActive()
        if (fair && !Registry.caveHooked) {
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
