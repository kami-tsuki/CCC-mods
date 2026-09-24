package kami.geology.client

import kami.geology.net.MapDone
import kami.geology.net.MapLayer
import kami.geology.net.OpenMap
import kami.geology.net.ProbeResponse
import net.minecraft.client.Minecraft

object ClientHooks {
    fun open(payload: OpenMap) = Minecraft.getInstance().setScreen(HeatmapScreen(payload))

    fun layer(payload: MapLayer) {
        (Minecraft.getInstance().screen as? HeatmapScreen)?.onLayer(payload)
    }

    fun done(payload: MapDone) {
        (Minecraft.getInstance().screen as? HeatmapScreen)?.onDone(payload)
    }

    fun probe(payload: ProbeResponse) {
        (Minecraft.getInstance().screen as? HeatmapScreen)?.onProbe(payload)
    }
}
