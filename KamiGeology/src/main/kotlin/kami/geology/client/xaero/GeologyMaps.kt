package kami.geology.client.xaero

import kami.geology.client.HeatmapScreen
import kami.geology.command.GeoText
import kami.libs.xaero.ChunkLayer
import kami.libs.xaero.ChunkPaint
import kami.libs.xaero.MapPoint
import kami.libs.xaero.MapToggle
import kami.libs.xaero.PointSource
import kami.libs.xaero.XaeroMaps
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.client.event.ClientTickEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

private const val GROUP = "kami_geology.maps.group"
private const val GLOBAL = "geology.global"
private const val MAX_POINTS = 150
private const val TICK_INTERVAL = 20

private fun hash(vararg parts: Int): Int = parts.fold(17) { a, b -> a * 31 + b }

private fun paintOf(cell: ChunkCell?, color: (String) -> Int): ChunkPaint? {
    cell ?: return null
    val alpha = (0x38 + (cell.intensity * 0x70).toInt()).coerceIn(0, 0xFF)
    return ChunkPaint((alpha shl 24) or (color(cell.ore) and 0xFFFFFF), 0, 0, 0, 0)
}

private abstract class CellLayer(override val id: String, override val order: Int) : ChunkLayer {
    abstract fun cell(dim: String, cx: Int, cz: Int): ChunkCell?
    abstract fun color(ore: String): Int

    override val toggle get() = id

    override fun regionHash(dim: String, rx: Int, rz: Int): Int {
        var h = 0
        for (cx in rx * 32 until rx * 32 + 32) for (cz in rz * 32 until rz * 32 + 32) {
            val c = cell(dim, cx, cz) ?: continue
            h = hash(h, cx, cz, c.ore.hashCode(), (c.intensity * 255).toInt(), color(c.ore))
        }
        return h
    }

    override fun paint(dim: String, cx: Int, cz: Int) = paintOf(cell(dim, cx, cz), ::color)

    override fun tooltip(dim: String, cx: Int, cz: Int, blunt: Boolean): Component? {
        val c = cell(dim, cx, cz) ?: return null
        return Component.translatable("kami_geology.maps.tip", GeoText.oreLabel(c.ore), (c.intensity * 100).toInt())
    }
}

private object ScanLayer : CellLayer("geology.scans", 30) {
    override fun cell(dim: String, cx: Int, cz: Int) = ScanStore.data.cell(dim, cx, cz)
    override fun color(ore: String) = ScanStore.data.legend[ore] ?: 0xC0C8D0
}

private object GlobalLayer : CellLayer(GLOBAL, 29) {
    override fun cell(dim: String, cx: Int, cz: Int) = GlobalHeat.cell(dim, cx, cz)
    override fun color(ore: String) = GlobalHeat.color(ore)
}

private object GeologyPoints : PointSource {
    override val id = "geology"
    override val toggle = "geology.points"

    override fun rev() = ScanStore.data.rev

    override fun points(dim: String): List<MapPoint> {
        val scans = ScanStore.data
        val out = ArrayList<MapPoint>()
        scans.lastFind()?.takeIf { it.first.dim == dim }?.let { (scan, find) ->
            out.add(point("geo:last", scan.dim, find, Component.translatable("kami_geology.maps.last").string, 0xFFD24A))
        }
        for (scan in scans.scans.asReversed()) {
            if (scan.dim != dim) continue
            for (find in scan.finds) {
                if (out.size >= MAX_POINTS) return out
                out.add(point("geo:${scan.at}:${find.ore}", dim, find, GeoText.oreLabel(find.ore), scans.legend[find.ore] ?: 0xC0C8D0))
            }
        }
        return out
    }

    private fun point(id: String, dim: String, find: Find, name: String, color: Int): MapPoint {
        val label = GeoText.oreLabel(find.ore)
        return MapPoint(
            id, dim, find.x, null, find.z, name, label.take(2).uppercase(), color and 0xFFFFFF,
            listOf(Component.literal(label), Component.translatable("kami_geology.maps.deposit", find.x, find.z))
        )
    }
}

object GeologyMaps {
    private var ticks = 0

    fun init() {
        XaeroMaps.toggle(MapToggle("geology.scans", GROUP, "kami_geology.maps.scans", "kami_geology.maps.scans.tip", true))
        XaeroMaps.toggle(MapToggle("geology.points", GROUP, "kami_geology.maps.points", "kami_geology.maps.points.tip", true))
        XaeroMaps.toggle(MapToggle(GLOBAL, GROUP, "kami_geology.maps.global", "kami_geology.maps.global.tip", false) { admin() })
        XaeroMaps.layer(ScanLayer)
        XaeroMaps.layer(GlobalLayer)
        XaeroMaps.waypoints(GeologyPoints)
        XaeroMaps.markers(GeologyPoints)
        FORGE_BUS.addListener<ClientTickEvent.Post> {
            if (++ticks % TICK_INTERVAL != 0) return@addListener
            ScanStore.sync()
            GlobalHeat.tick(admin() && XaeroMaps.on(GLOBAL), Minecraft.getInstance().screen is HeatmapScreen)
        }
    }

    fun admin() = Minecraft.getInstance().player?.hasPermissions(2) == true

    fun logout() {
        ScanStore.reset()
        GlobalHeat.reset()
    }
}
