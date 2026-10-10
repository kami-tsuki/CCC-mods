package kami.geology.client.compat.xaero

import kami.geology.KamiGeology
import kami.geology.map.Sparse
import kami.geology.net.OpenMap
import kami.libs.xaero.XaeroMaps
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.client.Minecraft
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

const val MAX_SCANS = 200
private const val INTENSITY_FULL = 32f

class Find(val ore: String, val x: Int, val z: Int, val value: Float)

data class ChunkCell(val ore: String, val intensity: Float)

object ScanChunks {
    fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
    fun cx(key: Long) = (key shr 32).toInt()
    fun cz(key: Long) = key.toInt()
}

class Scan(val dim: String, val x0: Int, val z0: Int, val w: Int, val h: Int, val y0: Int, val y1: Int, val at: Long, val ores: Map<String, ByteArray>) {
    private val layers: Map<String, FloatArray> by lazy { ores.mapValues { (_, data) -> Sparse.unpack(data, w * h) } }

    val finds: List<Find> by lazy {
        layers.mapNotNull { (ore, values) ->
            var best = -1
            for (k in values.indices) if (values[k] > 0f && (best < 0 || values[k] > values[best])) best = k
            if (best < 0) null else Find(ore, x0 + best % w, z0 + best / w, values[best])
        }
    }

    fun chunks(): Map<Long, ChunkCell> {
        val sums = HashMap<Long, HashMap<String, Float>>()
        for ((ore, values) in layers) for (k in values.indices) {
            val v = values[k]
            if (v <= 0f) continue
            val key = ScanChunks.key(Math.floorDiv(x0 + k % w, 16), Math.floorDiv(z0 + k / w, 16))
            sums.getOrPut(key) { HashMap() }.merge(ore, v, Float::plus)
        }
        val out = HashMap<Long, ChunkCell>()
        for ((key, byOre) in sums) {
            val top = byOre.maxByOrNull { it.value } ?: continue
            out[key] = ChunkCell(top.key, (top.value / INTENSITY_FULL).coerceIn(0f, 1f))
        }
        return out
    }

    fun same(o: Scan) = dim == o.dim && x0 == o.x0 && z0 == o.z0 && w == o.w && h == o.h
}

class Scans(private val cap: Int = MAX_SCANS) {
    @Volatile
    var scans: List<Scan> = emptyList()
        private set

    @Volatile
    private var grid = emptyMap<String, Map<Long, ChunkCell>>()

    @Volatile
    var legend: Map<String, Int> = emptyMap()
        private set

    @Volatile
    var rev = 0
        private set

    fun lastFind(): Pair<Scan, Find>? {
        val scan = scans.lastOrNull() ?: return null
        val find = scan.finds.maxByOrNull { it.value } ?: return null
        return scan to find
    }

    fun cell(dim: String, cx: Int, cz: Int): ChunkCell? = grid[dim]?.get(ScanChunks.key(cx, cz))

    fun add(scan: Scan) {
        val kept = scans.filterNot { it.same(scan) }
        val next = (kept + scan).takeLast(cap)
        val rebuild = kept.size != scans.size || next.size != kept.size + 1
        scans = next
        grid = if (rebuild) build(emptyMap(), next) else build(grid, listOf(scan))
        rev++
    }

    fun clear() {
        scans = emptyList()
        grid = emptyMap()
        legend = emptyMap()
        rev++
    }

    private fun build(base: Map<String, Map<Long, ChunkCell>>, add: List<Scan>): Map<String, Map<Long, ChunkCell>> {
        val out = HashMap<String, HashMap<Long, ChunkCell>>()
        base.forEach { (dim, cells) -> out[dim] = HashMap(cells) }
        add.forEach { apply(out, it) }
        return out
    }

    private fun apply(grid: HashMap<String, HashMap<Long, ChunkCell>>, scan: Scan) {
        val target = grid.getOrPut(scan.dim) { HashMap() }
        val fresh = scan.chunks()
        val x1 = Math.floorDiv(scan.x0 + scan.w - 1, 16)
        val z1 = Math.floorDiv(scan.z0 + scan.h - 1, 16)
        for (cx in Math.floorDiv(scan.x0, 16)..x1) for (cz in Math.floorDiv(scan.z0, 16)..z1) {
            val key = ScanChunks.key(cx, cz)
            val cell = fresh[key]
            if (cell != null) target[key] = cell else target.remove(key)
        }
    }

    fun record(dim: String, colors: Map<String, Int>, x0: Int, z0: Int, w: Int, h: Int, y0: Int, y1: Int, at: Long, layers: Map<String, FloatArray>) {
        val packed = layers.mapValues { Sparse.pack(it.value) }.filter { it.value.size > 1 }
        legend = legend + colors
        add(Scan(dim, x0, z0, w, h, y0, y1, at, packed))
    }

    fun toJson(): String = json.encodeToString(
        StoreDto(
            1,
            legend.map { (id, color) -> LegendDto(id, color) },
            scans.map { s -> ScanDto(s.dim, s.x0, s.z0, s.w, s.h, s.y0, s.y1, s.at, s.ores.mapValues { Base64.getEncoder().encodeToString(it.value) }) }
        )
    )

    fun fromJson(text: String) {
        clear()
        val root = json.decodeFromString<StoreDto>(text)
        if (root.v != 1) return
        legend = root.legend.associate { it.id to it.color }
        scans = root.scans.map { o ->
            Scan(o.dim, o.x0, o.z0, o.w, o.h, o.y0, o.y1, o.at, o.ores.mapValues { Base64.getDecoder().decode(it.value) })
        }.takeLast(cap)
        grid = build(emptyMap(), scans)
    }
}

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private class LegendDto(val id: String, val color: Int)

@Serializable
private class ScanDto(val dim: String, val x0: Int, val z0: Int, val w: Int, val h: Int, val y0: Int, val y1: Int, val at: Long, val ores: Map<String, String>)

@Serializable
private class StoreDto(val v: Int = 0, val legend: List<LegendDto> = emptyList(), val scans: List<ScanDto> = emptyList())

object ScanStore {
    val data = Scans()
    private var key: String? = null
    private val unsafe = Regex("[^a-z0-9._-]")

    private fun serverKey(): String {
        val ip = Minecraft.getInstance().currentServer?.ip ?: return "local"
        return ip.lowercase().replace(unsafe, "_")
    }

    private fun path(k: String): Path = FMLPaths.GAMEDIR.get().resolve("kami").resolve("geology").resolve("scans").resolve("$k.json")

    fun sync() {
        val k = serverKey()
        if (k == key) return
        key = k
        val had = data.scans.isNotEmpty()
        data.clear()
        runCatching {
            val p = path(k)
            if (Files.exists(p)) data.fromJson(Files.readString(p))
        }.onFailure { KamiGeology.LOG.warn("Could not read prospector scans: {}", it.message) }
        if (had || data.scans.isNotEmpty()) XaeroMaps.refresh()
    }

    fun record(info: OpenMap, x0: Int, z0: Int, w: Int, h: Int, deposits: Array<FloatArray?>, yMin: Int, yMax: Int) {
        sync()
        val layers = HashMap<String, FloatArray>()
        val colors = HashMap<String, Int>()
        info.ores.forEachIndexed { i, ore ->
            val values = deposits.getOrNull(i)
            if (!ore.deposit || values == null) return@forEachIndexed
            layers[ore.id] = values
            colors[ore.id] = ore.color and 0xFFFFFF
        }
        if (layers.isEmpty()) return
        data.record(info.dimension, colors, x0, z0, w, h, yMin, yMax, System.currentTimeMillis(), layers)
        val k = key ?: return
        runCatching {
            val p = path(k)
            Files.createDirectories(p.parent)
            Files.writeString(p, data.toJson())
        }.onFailure { KamiGeology.LOG.warn("Could not save prospector scans: {}", it.message) }
        XaeroMaps.refresh()
    }

    fun reset() {
        key = null
        val had = data.scans.isNotEmpty()
        data.clear()
        if (had) XaeroMaps.refresh()
    }
}
