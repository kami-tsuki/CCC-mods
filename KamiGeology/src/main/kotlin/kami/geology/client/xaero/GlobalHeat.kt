package kami.geology.client.xaero

import kami.geology.map.Sparse
import kami.geology.net.MapDone
import kami.geology.net.MapLayer
import kami.geology.net.MapRequest
import kami.geology.net.OreInfo
import kami.libs.xaero.XaeroMaps
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.network.PacketDistributor

object GlobalHeat {
    const val BASE = 0x40000000
    private const val SPAN = 0x01000000
    const val START_RADIUS = 128
    const val MIN_RADIUS = 8
    private const val MOVE_CHUNKS = 64
    private const val TIMEOUT_MS = 30_000L
    private const val INTENSITY_PER_BLOCK = 8f

    fun owns(seq: Int) = seq >= BASE

    fun nextRadius(radius: Int, empty: Boolean) = if (empty) maxOf(MIN_RADIUS, radius / 2) else radius

    fun moved(lastCx: Int, lastCz: Int, cx: Int, cz: Int) = maxOf(Math.abs(cx - lastCx), Math.abs(cz - lastCz)) > MOVE_CHUNKS

    class Request(val seq: Int, val dim: String, val cx: Int, val cz: Int, val x0: Int, val z0: Int, val w: Int, val h: Int) {
        val layers = HashMap<Int, FloatArray>()
    }

    @Volatile
    var legend: List<OreInfo> = emptyList()
    var radius = START_RADIUS
        private set

    @Volatile
    var rev = 0
        private set

    private var counter = 0
    private var pending: Request? = null
    private var sentAt = 0L
    private var lastDim: String? = null
    private var lastCx = 0
    private var lastCz = 0

    @Volatile
    private var grid = emptyMap<String, Map<Long, ChunkCell>>()

    fun cell(dim: String, cx: Int, cz: Int): ChunkCell? = grid[dim]?.get(ScanChunks.key(cx, cz))

    fun color(ore: String) = legend.firstOrNull { it.id == ore }?.color?.and(0xFFFFFF) ?: 0xC0C8D0

    fun reset() {
        pending = null
        grid = emptyMap()
        lastDim = null
        radius = START_RADIUS
        rev++
        XaeroMaps.refresh()
    }

    fun cancel() {
        pending = null
    }

    fun tick(allowed: Boolean, screenOpen: Boolean) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val now = System.currentTimeMillis()
        if (pending != null && now - sentAt > TIMEOUT_MS) pending = null
        if (!allowed || screenOpen || pending != null || mc.connection == null) return
        val dim = player.level().dimension().location().toString()
        val cx = Math.floorDiv(player.blockX, 16)
        val cz = Math.floorDiv(player.blockZ, 16)
        if (dim == lastDim && !moved(lastCx, lastCz, cx, cz)) return
        val x0 = (cx - radius) * 16
        val z0 = (cz - radius) * 16
        val n = radius * 2 + 1
        val seq = BASE + (counter++ % SPAN)
        pending = Request(seq, dim, cx, cz, x0, z0, n, n)
        sentAt = now
        val level = player.level()
        PacketDistributor.sendToServer(MapRequest(seq, x0, z0, 16, n, n, level.minBuildHeight, level.maxBuildHeight - 1))
    }

    fun onLayer(p: MapLayer) {
        val r = pending ?: return
        if (p.seq != r.seq || p.ore < 0) return
        runCatching { r.layers[p.ore] = Sparse.unpack(p.data, r.w * r.h) }
    }

    fun onDone(p: MapDone) {
        val r = pending ?: return
        if (p.seq != r.seq) return
        pending = null
        val empty = r.layers.isEmpty() || r.layers.values.all { v -> v.all { it <= 0f } }
        if (r.layers.isEmpty()) {
            val next = nextRadius(radius, true)
            if (next != radius) {
                radius = next
                return
            }
        }
        lastDim = r.dim
        lastCx = r.cx
        lastCz = r.cz
        val cells = HashMap<Long, ChunkCell>()
        if (!empty) for (k in 0 until r.w * r.h) {
            var best = 0f
            var ore = -1
            for ((i, values) in r.layers) if (values[k] > best) {
                best = values[k]
                ore = i
            }
            val id = legend.getOrNull(ore)?.id ?: continue
            cells[ScanChunks.key(r.x0 / 16 + k % r.w, r.z0 / 16 + k / r.w)] = ChunkCell(id, (best * INTENSITY_PER_BLOCK).coerceIn(0f, 1f))
        }
        grid = grid + (r.dim to cells)
        rev++
        XaeroMaps.refresh()
    }
}
