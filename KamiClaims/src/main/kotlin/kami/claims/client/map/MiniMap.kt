package kami.claims.client.map

import kami.claims.client.ClientClaims
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.map.ChunkMap
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import net.minecraft.client.Minecraft

object MiniMap {
    private val maps = HashMap<String, ChunkMap>()

    fun dim(): String = Minecraft.getInstance().level?.dimension()?.location()?.toString() ?: "minecraft:overworld"

    fun draw(ui: Ui, r: Rect, x: Int, z: Int, radius: Int, key: String, highlight: Collection<Pair<Int, Int>> = emptyList(), color: Int = Palette.brass, mode: MapMode = MapMode.POLITICAL, you: Pair<Int, Int>? = null) {
        val map = maps.getOrPut(key) { ChunkMap() }
        val zoom = (minOf(r.w, r.h) / (radius * 2 + 1).toFloat()).coerceIn(2f, 48f)
        map.zoom = zoom
        map.zoomTo(zoom)
        map.center(x, z)
        val d = dim()
        ui.clip(r) {
            map.drawBase(ui, r, d, ClientClaims.prefs.terrain, false)
            ClaimsLayer.draw(ui, map, d, mode, setOf("borders", "markers"), you)
            highlight.forEach { (hx, hz) ->
                val c = map.cell(hx, hz)
                Draw.fill(ui.g, c, Palette.alpha(color, 0x50))
                Draw.outline(ui.g, c, color)
            }
        }
        Draw.outline(ui.g, r, Palette.border)
    }
}
