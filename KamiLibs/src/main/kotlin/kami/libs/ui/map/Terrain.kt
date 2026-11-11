package kami.libs.ui.map

import com.mojang.blaze3d.platform.NativeImage
import kami.libs.KamiLibs
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object TerrainSampler {
    fun sample(level: Level, chunk: LevelChunk): IntArray {
        val out = IntArray(256)
        val pos = BlockPos.MutableBlockPos()
        val ceiling = level.dimensionType().hasCeiling()
        val baseX = chunk.pos.minBlockX
        val baseZ = chunk.pos.minBlockZ
        val heights = IntArray(16) { x -> surface(level, chunk, baseX + x, baseZ - 1, ceiling, pos).first }
        for (z in 0 until 16) for (x in 0 until 16) {
            val (y, state, depth) = column(level, chunk, baseX + x, baseZ + z, ceiling, pos)
            val north = heights[x]
            heights[x] = y
            val color = state?.getMapColor(level, pos.set(baseX + x, y, baseZ + z)) ?: MapColor.NONE
            if (color == MapColor.NONE) continue
            val brightness = if (depth > 0) {
                val d = depth * 0.1 + ((x + z) and 1) * 0.2
                when { d < 0.5 -> MapColor.Brightness.HIGH; d > 0.9 -> MapColor.Brightness.LOW; else -> MapColor.Brightness.NORMAL }
            } else {
                val d = (y - north) * 0.8 + (((x + z) and 1) - 0.5) * 0.4
                when { d > 0.6 -> MapColor.Brightness.HIGH; d < -0.6 -> MapColor.Brightness.LOW; else -> MapColor.Brightness.NORMAL }
            }
            out[x + z * 16] = color.calculateRGBColor(brightness)
        }
        return out
    }

    private fun surface(level: Level, chunk: LevelChunk, x: Int, z: Int, ceiling: Boolean, pos: BlockPos.MutableBlockPos): Triple<Int, BlockState?, Int> {
        val inside = (x shr 4) == chunk.pos.x && (z shr 4) == chunk.pos.z
        if (!inside) return Triple(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, null, 0)
        return column(level, chunk, x, z, ceiling, pos)
    }

    private fun column(level: Level, chunk: LevelChunk, x: Int, z: Int, ceiling: Boolean, pos: BlockPos.MutableBlockPos): Triple<Int, BlockState?, Int> {
        var y = if (ceiling) findFloor(chunk, x, z, pos) else chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x and 15, z and 15)
        val floor = level.minBuildHeight
        var state = chunk.getBlockState(pos.set(x, y, z))
        var steps = 0
        while (y > floor && steps < 40 && state.getMapColor(level, pos) == MapColor.NONE) {
            y--; steps++
            state = chunk.getBlockState(pos.set(x, y, z))
        }
        var depth = 0
        if (!state.fluidState.isEmpty) {
            var d = y - 1
            while (d > floor && depth < 16 && !chunk.getBlockState(pos.set(x, d, z)).fluidState.isEmpty) { d--; depth++ }
            depth++
            pos.set(x, y, z)
        }
        return Triple(y, state, depth)
    }

    private fun findFloor(chunk: LevelChunk, x: Int, z: Int, pos: BlockPos.MutableBlockPos): Int {
        var y = 100
        var wasAir = false
        while (y > chunk.minBuildHeight) {
            val air = chunk.getBlockState(pos.set(x, y, z)).isAir
            if (wasAir && !air) return y
            wasAir = air
            y--
        }
        return chunk.minBuildHeight
    }
}

private val unsafeDim = Regex("[^a-z0-9._-]")

private fun safeDim(dim: String) = dim.lowercase().replace(unsafeDim, "_").replace("..", "__")

private fun tilePath(root: Path?, dim: String, rx: Int, rz: Int): Path? = root?.let {
    val base = it.normalize()
    base.resolve("${safeDim(dim)}/r.$rx.$rz.png").normalize().takeIf { p -> p.startsWith(base) }
}

class TerrainTile(val dim: String, val rx: Int, val rz: Int, val image: NativeImage, val folder: Path?) {
    private val safeDim = safeDim(dim)
    val id: ResourceLocation = ResourceLocation.fromNamespaceAndPath(KamiLibs.ID, "terrain/$safeDim/${rx}_${rz}".replace('-', 'm'))
    private var texture: DynamicTexture? = null
    var dirtyGpu = true
    var dirtyDisk = false
    var used = 0L

    fun path(): Path? = tilePath(folder, dim, rx, rz)

    fun bind(): ResourceLocation {
        val tex = texture ?: DynamicTexture(image).also {
            texture = it
            Minecraft.getInstance().textureManager.register(id, it)
        }
        if (dirtyGpu) { tex.upload(); dirtyGpu = false }
        return id
    }

    fun release() {
        texture?.let { Minecraft.getInstance().textureManager.release(id) }
        texture = null
    }
}

object TerrainCache {
    const val TILE_CHUNKS = 32
    private const val SIZE = TILE_CHUNKS * 16
    private val unsafe = Regex("[^a-z0-9._-]")
    private val tiles = LinkedHashMap<String, TerrainTile>()
    private val missing = HashSet<String>()
    private val io = ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue(), { Thread(it, "kami-map-io") }).apply { allowCoreThreadTimeOut(true) }
    var maxTiles = 48
    var diskLimitMb = 256
    var enabled = true
    private var folder: Path? = null
    private var session = ""

    private fun key(dim: String, rx: Int, rz: Int) = "$dim|$rx|$rz"

    private fun serverFolder(): Path? {
        val mc = Minecraft.getInstance()
        val name = mc.singleplayerServer?.worldData?.levelName?.let { "local-$it" } ?: mc.currentServer?.ip ?: return null
        val clean = name.lowercase().replace(unsafe, "_")
        if (clean != session) {
            clear()
            session = clean
            folder = mc.gameDirectory.toPath().resolve("kami/mapcache/$clean")
        }
        return folder
    }

    private const val MAX_LOADING = 48
    private val loading = HashMap<String, CompletableFuture<NativeImage?>>()

    private fun readTile(path: Path): NativeImage? =
        runCatching { if (Files.exists(path)) Files.newInputStream(path).use { NativeImage.read(it) }.takeIf { it.width == SIZE && it.height == SIZE } else null }.getOrNull()

    fun tile(dim: String, rx: Int, rz: Int, create: Boolean): TerrainTile? {
        val k = key(dim, rx, rz)
        tiles[k]?.let { it.used = System.currentTimeMillis(); return it }
        if (!create && k in missing) return null
        val dir = serverFolder()
        val path = tilePath(dir, dim, rx, rz)
        if (loading.size >= MAX_LOADING && k !in loading) dropPending()
        val job = loading[k] ?: CompletableFuture.supplyAsync({ path?.let(::readTile) }, io).also { loading[k] = it }
        if (!job.isDone) return null
        loading.remove(k)
        val image = job.getNow(null) ?: if (create) NativeImage(SIZE, SIZE, true).also { it.fillRect(0, 0, SIZE, SIZE, 0) } else null
        if (image == null) { missing += k; return null }
        missing -= k
        val tile = TerrainTile(dim, rx, rz, image, dir)
        tile.used = System.currentTimeMillis()
        tiles[k] = tile
        evict()
        return tile
    }

    fun ready(dim: String, cx: Int, cz: Int): Boolean =
        !enabled || tile(dim, Math.floorDiv(cx, TILE_CHUNKS), Math.floorDiv(cz, TILE_CHUNKS), true) != null

    fun put(dim: String, cx: Int, cz: Int, pixels: IntArray) {
        if (!enabled) return
        val tile = tile(dim, Math.floorDiv(cx, TILE_CHUNKS), Math.floorDiv(cz, TILE_CHUNKS), true) ?: return
        val ox = Math.floorMod(cx, TILE_CHUNKS) * 16
        val oz = Math.floorMod(cz, TILE_CHUNKS) * 16
        var changed = false
        for (z in 0 until 16) for (x in 0 until 16) {
            val v = pixels[x + z * 16]
            if (v == 0) continue
            if (tile.image.getPixelRGBA(ox + x, oz + z) != v) { tile.image.setPixelRGBA(ox + x, oz + z, v); changed = true }
        }
        if (changed) { tile.dirtyGpu = true; tile.dirtyDisk = true }
    }

    private fun evict() {
        while (tiles.size > maxTiles) {
            val oldest = tiles.values.minByOrNull { it.used } ?: return
            save(oldest)
            oldest.release()
            oldest.image.close()
            tiles.remove(key(oldest.dim, oldest.rx, oldest.rz))
        }
    }

    private fun save(tile: TerrainTile) {
        if (!tile.dirtyDisk) return
        tile.dirtyDisk = false
        val path = tile.path() ?: return
        val snapshot = NativeImage(tile.image.format(), tile.image.width, tile.image.height, false).also { it.copyFrom(tile.image) }
        CompletableFuture.runAsync({
            runCatching {
                Files.createDirectories(path.parent)
                val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
                snapshot.writeToFile(temp)
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure { KamiLibs.LOG.warn("Could not save map tile {}: {}", path, it.message) }
            snapshot.close()
        }, io)
    }

    fun flush(limit: Int = Int.MAX_VALUE) {
        tiles.values.filter { it.dirtyDisk }.take(limit).forEach(::save)
    }

    fun trimDisk() {
        val root = folder ?: return
        val limit = diskLimitMb.toLong() * 1024 * 1024
        io.execute {
            runCatching {
                if (!Files.exists(root)) return@execute
                val (stale, files) = Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.toList() }.partition { it.fileName.toString().endsWith(".tmp") }
                stale.forEach { Files.deleteIfExists(it) }
                var total = files.sumOf { Files.size(it) }
                if (total <= limit) return@execute
                files.sortedBy { Files.getLastModifiedTime(it).toMillis() }.forEach { f ->
                    if (total <= limit) return@execute
                    total -= Files.size(f)
                    Files.deleteIfExists(f)
                }
            }.onFailure { KamiLibs.LOG.warn("Could not trim map cache: {}", it.message) }
        }
    }

    fun dropPending() {
        loading.values.forEach { j -> j.whenComplete { img, _ -> img?.close() } }
        loading.clear()
    }

    fun clear() {
        flush()
        tiles.values.forEach { it.release(); it.image.close() }
        dropPending()
        tiles.clear()
        missing.clear()
    }

    fun wipe() {
        val root = serverFolder()
        tiles.values.forEach { it.release(); it.image.close() }
        dropPending()
        tiles.clear()
        missing.clear()
        io.execute { root?.takeIf { Files.exists(it) }?.let { r -> Files.walk(r).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } } }
    }
}
