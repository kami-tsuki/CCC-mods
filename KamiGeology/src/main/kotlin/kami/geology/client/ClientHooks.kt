package kami.geology.client

import kami.geology.client.compat.jade.SignalJade
import kami.geology.client.compat.jei.GuideJei
import kami.geology.client.compat.xaero.GeologyMaps
import kami.geology.client.compat.xaero.GlobalHeat
import kami.geology.net.MapDone
import kami.geology.net.MapLayer
import kami.geology.net.OpenMap
import kami.geology.net.ProbeResponse
import kami.geology.world.Hardness
import kami.libs.jei.KamiJei
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object ClientHooks {
    fun init() {
        FORGE_BUS.addListener<ClientPlayerNetworkEvent.LoggingOut> {
            Hardness.clear()
            GeologyMaps.logout()
        }
        GeologyMaps.init()
        if (KamiJei.present) KamiJei.module(GuideJei())
        SignalJade.init()
    }

    fun open(payload: OpenMap) {
        GlobalHeat.legend = payload.ores
        GlobalHeat.cancel()
        Minecraft.getInstance().setScreen(HeatmapScreen(payload))
    }

    fun layer(payload: MapLayer) {
        if (GlobalHeat.owns(payload.seq)) GlobalHeat.onLayer(payload)
        else (Minecraft.getInstance().screen as? HeatmapScreen)?.onLayer(payload)
    }

    fun done(payload: MapDone) {
        if (GlobalHeat.owns(payload.seq)) GlobalHeat.onDone(payload)
        else (Minecraft.getInstance().screen as? HeatmapScreen)?.onDone(payload)
    }

    fun probe(payload: ProbeResponse) = (Minecraft.getInstance().screen as? HeatmapScreen)?.onProbe(payload)
}
