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

class TerrainTile(val dim: String, val rx: Int, val rz: Int, val image: NativeImage) {
    val id: ResourceLocation = ResourceLocation.fromNamespaceAndPath(KamiLibs.ID, "terrain/${dim.replace(':', '_').replace('/', '_')}/${rx}_${rz}".replace('-', 'm'))
    private var texture: DynamicTexture? = null
    var dirtyGpu = true
    var dirtyDisk = false
    var used = 0L

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
    private val tiles = LinkedHashMap<String, TerrainTile>()
    private val missing = HashSet<String>()
    var maxTiles = 48
    var diskLimitMb = 256
    var enabled = true
    private var folder: Path? = null
    private var session = ""

    private fun key(dim: String, rx: Int, rz: Int) = "$dim|$rx|$rz"

    private fun serverFolder(): Path? {
        val mc = Minecraft.getInstance()
        val name = mc.singleplayerServer?.worldData?.levelName?.let { "local-$it" } ?: mc.currentServer?.ip ?: return null
        val clean = name.lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        if (clean != session) {
            session = clean
            clear()
        }
        return mc.gameDirectory.toPath().resolve("kami/mapcache/$clean").also { folder = it }
    }

    private fun file(dim: String, rx: Int, rz: Int) = serverFolder()?.resolve("${dim.replace(':', '_')}/r.$rx.$rz.png")

    fun tile(dim: String, rx: Int, rz: Int, create: Boolean): TerrainTile? {
        val k = key(dim, rx, rz)
        tiles[k]?.let { it.used = System.currentTimeMillis(); return it }
        if (!create && k in missing) return null
        val path = file(dim, rx, rz)
        val image = path?.takeIf { Files.exists(it) }?.let { p -> runCatching { Files.newInputStream(p).use { NativeImage.read(it) } }.getOrNull()?.takeIf { it.width == SIZE && it.height == SIZE } }
            ?: if (create) NativeImage(SIZE, SIZE, true).also { it.fillRect(0, 0, SIZE, SIZE, 0) } else null
        if (image == null) { missing += k; return null }
        missing -= k
        val tile = TerrainTile(dim, rx, rz, image)
        tile.used = System.currentTimeMillis()
        tiles[k] = tile
        evict()
        return tile
    }

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
        val path = file(tile.dim, tile.rx, tile.rz) ?: return
        runCatching {
            Files.createDirectories(path.parent)
            tile.image.writeToFile(path)
            tile.dirtyDisk = false
        }.onFailure { KamiLibs.LOG.warn("Could not save map tile {}: {}", path, it.message) }
    }

    fun flush(limit: Int = Int.MAX_VALUE) {
        tiles.values.filter { it.dirtyDisk }.take(limit).forEach(::save)
    }

    fun trimDisk() {
        val root = folder ?: return
        if (!Files.exists(root)) return
        val files = Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
        var total = files.sumOf { Files.size(it) }
        val limit = diskLimitMb.toLong() * 1024 * 1024
        if (total <= limit) return
        files.sortedBy { Files.getLastModifiedTime(it).toMillis() }.forEach { f ->
            if (total <= limit) return
            total -= Files.size(f)
            Files.deleteIfExists(f)
        }
    }

    fun clear() {
        flush()
        tiles.values.forEach { it.release(); it.image.close() }
        tiles.clear()
        missing.clear()
    }

    fun wipe() {
        val root = serverFolder()
        tiles.values.forEach { it.release(); it.image.close() }
        tiles.clear()
        missing.clear()
        root?.takeIf { Files.exists(it) }?.let { r -> Files.walk(r).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}
