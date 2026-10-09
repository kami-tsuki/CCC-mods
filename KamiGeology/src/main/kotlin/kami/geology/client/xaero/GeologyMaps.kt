package kami.geology.client.xaero

import com.google.gson.Gson
import kami.geology.KamiGeology
import kami.geology.client.HeatmapScreen
import kami.geology.command.GeoText
import kami.libs.xaero.ChunkLayer
import kami.libs.xaero.ChunkPaint
import kami.libs.xaero.MapAction
import kami.libs.xaero.MapPoint
import kami.libs.xaero.MapToggle
import kami.libs.xaero.PointSource
import kami.libs.xaero.XaeroMaps
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.client.event.ClientTickEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.nio.file.Files

private const val GROUP = "kami_geology.xaero.group"
private const val MAX_POINTS = 150
private const val TICK_INTERVAL = 20

private class Prefs(var scans: Boolean = true, var points: Boolean = true, var global: Boolean = false)

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
        var h = 1
        for (cx in rx * 32 until rx * 32 + 32) for (cz in rz * 32 until rz * 32 + 32) {
            val c = cell(dim, cx, cz) ?: continue
            h = hash(h, cx, cz, c.ore.hashCode(), (c.intensity * 255).toInt())
        }
        return h
    }

    override fun paint(dim: String, cx: Int, cz: Int) = paintOf(cell(dim, cx, cz), ::color)

    override fun tooltip(dim: String, cx: Int, cz: Int, blunt: Boolean): Component? {
        val c = cell(dim, cx, cz) ?: return null
        return Component.translatable("kami_geology.xaero.tip", GeoText.oreLabel(c.ore), (c.intensity * 100).toInt())
    }
}

private object ScanLayer : CellLayer("geology.scans", 30) {
    override fun cell(dim: String, cx: Int, cz: Int) = ScanStore.view().cell(dim, cx, cz)
    override fun color(ore: String) = ScanStore.view().legend[ore] ?: 0xC0C8D0
}

private object GlobalLayer : CellLayer("geology.global", 29) {
    override fun cell(dim: String, cx: Int, cz: Int) = if (GlobalHeat.enabled) GlobalHeat.cell(dim, cx, cz) else null
    override fun color(ore: String) = GlobalHeat.color(ore)
}

private object GeologyPoints : PointSource {
    override val id = "geology"
    override val toggle = "geology.points"

    override fun rev() = ScanStore.view().rev

    override fun points(dim: String): List<MapPoint> {
        val scans = ScanStore.view()
        val out = ArrayList<MapPoint>()
        scans.lastFind()?.takeIf { it.first.dim == dim }?.let { (scan, find) ->
            out.add(point("geo:last", scan.dim, find, Component.translatable("kami_geology.xaero.last").string, 0xFFD24A))
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
        lateinit var self: MapPoint
        self = MapPoint(
            id, dim, find.x, null, find.z, name, label.take(2).uppercase(), color and 0xFFFFFF,
            listOf(Component.literal(label), Component.translatable("kami_geology.xaero.deposit", find.x, find.z)),
            listOf(MapAction(Component.translatable("kami_geology.xaero.waypoint")) { XaeroMaps.tempWaypoint(self) })
        )
        return self
    }
}

object GeologyMaps {
    private val file get() = FMLPaths.GAMEDIR.get().resolve("kami").resolve("geology").resolve("xaero.json")
    private val prefs: Prefs by lazy {
        runCatching { Gson().fromJson(Files.readString(file), Prefs::class.java) }.getOrNull() ?: Prefs()
    }
    private var ticks = 0

    private fun save() {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, Gson().toJson(prefs))
        }.onFailure { KamiGeology.LOG.warn("Could not save map settings: {}", it.message) }
        GlobalHeat.enabled = prefs.global
        XaeroMaps.refresh()
    }

    fun init() {
        GlobalHeat.enabled = prefs.global
        XaeroMaps.layer(ScanLayer)
        XaeroMaps.layer(GlobalLayer)
        XaeroMaps.waypoints(GeologyPoints)
        XaeroMaps.markers(GeologyPoints)
        XaeroMaps.toggle(MapToggle("geology.scans", GROUP, "kami_geology.xaero.scans", "kami_geology.xaero.scans.tip", { prefs.scans }, { prefs.scans = it; save() }))
        XaeroMaps.toggle(MapToggle("geology.points", GROUP, "kami_geology.xaero.points", "kami_geology.xaero.points.tip", { prefs.points }, { prefs.points = it; save() }))
        XaeroMaps.toggle(
            MapToggle(
                "geology.global", GROUP, "kami_geology.xaero.global", "kami_geology.xaero.global.tip", { prefs.global }, { prefs.global = it; save() },
                { admin() }
            )
        )
        FORGE_BUS.addListener<ClientTickEvent.Post> {
            if (++ticks % TICK_INTERVAL == 0) GlobalHeat.tick(admin(), Minecraft.getInstance().screen is HeatmapScreen)
        }
    }

    fun admin() = Minecraft.getInstance().player?.hasPermissions(2) == true

    fun logout() {
        ScanStore.reset()
        GlobalHeat.reset()
    }
}
