package kami.geology.client.xaero

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kami.geology.KamiGeology
import kami.geology.map.Sparse
import kami.geology.net.OpenMap
import kami.libs.xaero.XaeroMaps
import net.minecraft.client.Minecraft
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

const val MAX_SCANS = 200
private const val INTENSITY_FULL = 32f

class Find(val ore: String, val x: Int, val z: Int, val value: Float)

class ChunkCell(val ore: String, val intensity: Float)

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
    private val list = ArrayList<Scan>()
    private val grid = HashMap<String, HashMap<Long, ChunkCell>>()
    val legend = LinkedHashMap<String, Int>()
    var rev = 0
        private set

    val scans: List<Scan> get() = list

    fun lastFind(): Pair<Scan, Find>? {
        val scan = list.lastOrNull() ?: return null
        val find = scan.finds.maxByOrNull { it.value } ?: return null
        return scan to find
    }

    fun cell(dim: String, cx: Int, cz: Int): ChunkCell? = grid[dim]?.get(ScanChunks.key(cx, cz))

    fun add(scan: Scan) {
        val replaced = list.removeAll { it.same(scan) }
        list.add(scan)
        val dropped = list.size > cap
        while (list.size > cap) list.removeAt(0)
        if (replaced || dropped) rebuild() else apply(scan)
        rev++
    }

    fun clear() {
        list.clear()
        grid.clear()
        legend.clear()
        rev++
    }

    private fun rebuild() {
        grid.clear()
        list.forEach(::apply)
    }

    private fun apply(scan: Scan) {
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
        legend.putAll(colors)
        add(Scan(dim, x0, z0, w, h, y0, y1, at, packed))
    }

    fun toJson(): String {
        val root = JsonObject()
        root.addProperty("v", 1)
        val legendArray = JsonArray()
        legend.forEach { (id, color) -> legendArray.add(JsonObject().also { it.addProperty("id", id); it.addProperty("color", color) }) }
        root.add("legend", legendArray)
        val scanArray = JsonArray()
        list.forEach { s ->
            val o = JsonObject()
            o.addProperty("dim", s.dim)
            o.addProperty("x0", s.x0)
            o.addProperty("z0", s.z0)
            o.addProperty("w", s.w)
            o.addProperty("h", s.h)
            o.addProperty("y0", s.y0)
            o.addProperty("y1", s.y1)
            o.addProperty("at", s.at)
            val ores = JsonObject()
            s.ores.forEach { (id, data) -> ores.addProperty(id, Base64.getEncoder().encodeToString(data)) }
            o.add("ores", ores)
            scanArray.add(o)
        }
        root.add("scans", scanArray)
        return Gson().toJson(root)
    }

    fun fromJson(text: String) {
        clear()
        val root = Gson().fromJson(text, JsonObject::class.java) ?: return
        if (root.get("v")?.asInt != 1) return
        root.getAsJsonArray("legend")?.forEach { e ->
            val o = e.asJsonObject
            legend[o.get("id").asString] = o.get("color").asInt
        }
        root.getAsJsonArray("scans")?.forEach { e ->
            val o = e.asJsonObject
            val ores = o.getAsJsonObject("ores").entrySet().associate { it.key to Base64.getDecoder().decode(it.value.asString) }
            list.add(
                Scan(
                    o.get("dim").asString, o.get("x0").asInt, o.get("z0").asInt, o.get("w").asInt, o.get("h").asInt,
                    o.get("y0").asInt, o.get("y1").asInt, o.get("at").asLong, ores
                )
            )
        }
        while (list.size > cap) list.removeAt(0)
        rebuild()
    }
}

object ScanStore {
    val data = Scans()
    private var key: String? = null

    private fun serverKey(): String {
        val ip = Minecraft.getInstance().currentServer?.ip ?: return "local"
        return ip.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
    }

    private fun path(k: String): Path = FMLPaths.GAMEDIR.get().resolve("kami").resolve("geology").resolve("scans").resolve("$k.json")

    fun view(): Scans {
        val k = serverKey()
        if (k != key) {
            key = k
            data.clear()
            runCatching {
                val p = path(k)
                if (Files.exists(p)) data.fromJson(Files.readString(p))
            }.onFailure { KamiGeology.LOG.warn("Could not read prospector scans: {}", it.message) }
        }
        return data
    }

    fun record(info: OpenMap, x0: Int, z0: Int, w: Int, h: Int, deposits: Array<FloatArray?>, yMin: Int, yMax: Int) {
        val scans = view()
        val layers = HashMap<String, FloatArray>()
        val colors = HashMap<String, Int>()
        info.ores.forEachIndexed { i, ore ->
            val values = deposits.getOrNull(i)
            if (!ore.deposit || values == null) return@forEachIndexed
            layers[ore.id] = values
            colors[ore.id] = ore.color and 0xFFFFFF
        }
        if (layers.isEmpty()) return
        scans.record(info.dimension, colors, x0, z0, w, h, yMin, yMax, System.currentTimeMillis(), layers)
        val k = key ?: return
        runCatching {
            val p = path(k)
            Files.createDirectories(p.parent)
            Files.writeString(p, scans.toJson())
        }.onFailure { KamiGeology.LOG.warn("Could not save prospector scans: {}", it.message) }
        XaeroMaps.refresh()
    }

    fun reset() {
        key = null
        data.clear()
    }
}
