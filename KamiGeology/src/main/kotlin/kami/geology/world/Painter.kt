package kami.geology.world

import kami.geology.config.Distribution
import kami.geology.config.Ore
import kami.geology.util.Hash
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.synth.ImprovedNoise
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object Painter {
    fun deposit(level: WorldGenLevel, chunk: ChunkAccess, site: Site, noise: ImprovedNoise) {
        val ore = site.ore
        val config = ore.deposit ?: return
        val chunkPos = chunk.pos
        val x0 = max(chunkPos.minBlockX, site.minX)
        val x1 = min(chunkPos.maxBlockX, site.maxX)
        val z0 = max(chunkPos.minBlockZ, site.minZ)
        val z1 = min(chunkPos.maxBlockZ, site.maxZ)
        val y0 = max(level.minBuildHeight, site.minY)
        val y1 = min(level.maxBuildHeight - 1, site.maxY)
        if (x0 > x1 || z0 > z1 || y0 > y1) return

        val haloScale = ore.haloScale
        val halo = ore.halo
        val haloDensity = ore.config.halo?.density ?: 0.0
        val coreRadius = if (site.hasCore) ore.config.core?.radius ?: 0.0 else 0.0
        val coreDiscard = ore.config.core?.airDiscard ?: 0.0
        val body = config.bodyRadius
        val density = (config.density * site.grade.density).coerceAtMost(1.0)
        val pos = BlockPos.MutableBlockPos()

        for (x in x0..x1) for (z in z0..z1) for (y in y0..y1) {
            val r = site.radius(x + 0.5, y + 0.5, z + 0.5, noise)
            if (r >= haloScale) continue
            pos.set(x, y, z)
            val host = chunk.getBlockState(pos)
            val hash = Hash.at(site.id, x, y, z)
            if (r <= 1.0) {
                if (!ore.replaceable.test(host)) continue
                val core = r < coreRadius
                val chance = if (core) 1.0 else density * fringe(r, body, config.fringe)
                if (Hash.unit(hash) >= chance) continue
                val discard = if (core) coreDiscard else config.airDiscard
                if (discard > 0.0 && Hash.unit(Hash.mix(hash)) < discard && exposed(level, chunk, x, y, z)) continue
                chunk.setBlockState(pos, if (core) ore.core ?: ore.stateFor(host) else ore.stateFor(host), false)
            } else if (halo != null) {
                val chance = haloDensity * (1.0 - (r - 1.0) / (haloScale - 1.0))
                if (Hash.unit(hash) >= chance || !ore.haloRule.test(host)) continue
                chunk.setBlockState(pos, halo.pick(Hash.unit(Hash.mix(hash xor HALO_SALT))), false)
            }
        }
    }

    fun outcrop(chunk: ChunkAccess, site: Site, noise: ImprovedNoise) {
        val ore = site.ore
        val config = ore.config.outcrop ?: return
        val palette = ore.outcrop ?: return
        val chunkPos = chunk.pos
        val x0 = max(chunkPos.minBlockX, site.minX)
        val x1 = min(chunkPos.maxBlockX, site.maxX)
        val z0 = max(chunkPos.minBlockZ, site.minZ)
        val z1 = min(chunkPos.maxBlockZ, site.maxZ)
        val pos = BlockPos.MutableBlockPos()

        for (x in x0..x1) for (z in z0..z1) {
            val top = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1
            val from = min(top, site.maxY)
            val to = max(site.minY, top - config.maxDepth)
            var gap = -1
            var y = from
            while (y >= to) {
                if (site.radius(x + 0.5, y + 0.5, z + 0.5, noise) <= 1.0) {
                    gap = top - y
                    break
                }
                y -= 3
            }
            if (gap < 0) continue
            val hash = Hash.at(site.id xor OUTCROP_SALT, x, 0, z)
            if (Hash.unit(hash) >= config.density * (1.0 - gap.toDouble() / config.maxDepth)) continue
            pos.set(x, top, z)
            val host = chunk.getBlockState(pos)
            if (!ore.outcropRule.test(host) || !chunk.getBlockState(pos.above()).fluidState.isEmpty) continue
            val pick = Hash.unit(Hash.mix(hash))
            chunk.setBlockState(pos, if (pick < config.oreShare) ore.stone else palette.pick(Hash.unit(Hash.mix(hash + 1))), false)
        }
    }

    fun scatter(level: WorldGenLevel, chunk: ChunkAccess, ore: Ore, random: RandomSource, world: WorldContext) {
        val config = ore.scatter ?: return
        val chunkPos = chunk.pos
        val whole = floor(config.perChunk)
        val veins = whole.toInt() + if (random.nextDouble() < config.perChunk - whole) 1 else 0
        repeat(veins) {
            val x = chunkPos.minBlockX + random.nextInt(16)
            val z = chunkPos.minBlockZ + random.nextInt(16)
            val span = config.height[1] - config.height[0]
            val unit = if (config.distribution == Distribution.TRIANGLE) (random.nextDouble() + random.nextDouble()) / 2.0 else random.nextDouble()
            val y = (config.height[0] + unit * span).toInt().coerceIn(level.minBuildHeight, level.maxBuildHeight - 1)
            val allowed = ore.scatterProvinces
            if (allowed != null && world.settings.provinces.of(level.getBiome(BlockPos(x, y, z))) !in allowed) return@repeat
            val size = if (config.size[1] <= config.size[0]) config.size[0] else config.size[0] + random.nextInt(config.size[1] - config.size[0] + 1)
            vein(level, chunk, ore, x, y, z, size, random.nextLong())
        }
    }

    private fun vein(level: WorldGenLevel, chunk: ChunkAccess, ore: Ore, cx: Int, cy: Int, cz: Int, size: Int, seed: Long) {
        val discard = ore.scatter?.airDiscard ?: 0.0
        val radius = (size * 0.2387).pow(1.0 / 3.0) + 0.5
        val reach = ceil(radius).toInt()
        val chunkPos = chunk.pos
        val pos = BlockPos.MutableBlockPos()
        for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
            val x = cx + dx
            val y = cy + dy
            val z = cz + dz
            if (x shr 4 != chunkPos.x || z shr 4 != chunkPos.z || y < level.minBuildHeight || y >= level.maxBuildHeight) continue
            val hash = Hash.at(seed, x, y, z)
            if ((dx * dx + dy * dy + dz * dz) > radius * radius * (0.5 + Hash.unit(hash))) continue
            pos.set(x, y, z)
            val host = chunk.getBlockState(pos)
            if (!ore.replaceable.test(host)) continue
            if (discard > 0.0 && Hash.unit(Hash.mix(hash)) < discard && exposed(level, chunk, x, y, z)) continue
            chunk.setBlockState(pos, ore.stateFor(host), false)
        }
    }

    private fun fringe(r: Double, body: Double, edge: Double) =
        if (r <= body) 1.0 else 1.0 - (1.0 - edge) * (r - body) / (1.0 - body)

    private fun exposed(level: WorldGenLevel, chunk: ChunkAccess, x: Int, y: Int, z: Int): Boolean {
        val pos = BlockPos.MutableBlockPos()
        val chunkPos = chunk.pos
        for (direction in Direction.entries) {
            pos.set(x + direction.stepX, y + direction.stepY, z + direction.stepZ)
            if (pos.y < level.minBuildHeight || pos.y >= level.maxBuildHeight) continue
            val state = if (pos.x shr 4 == chunkPos.x && pos.z shr 4 == chunkPos.z) chunk.getBlockState(pos) else level.getBlockState(pos)
            if (state.isAir) return true
        }
        return false
    }

    private const val HALO_SALT = 0x51F15EL
    private const val OUTCROP_SALT = 0x0C7C20L
}
