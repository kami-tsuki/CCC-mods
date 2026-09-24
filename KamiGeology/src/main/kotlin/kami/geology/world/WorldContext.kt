package kami.geology.world

import kami.geology.config.ConfigStore
import kami.geology.config.Grade
import kami.geology.config.Kind
import kami.geology.config.Ore
import kami.geology.config.Settings
import kami.geology.util.Hash
import kami.geology.util.Rng
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.XoroshiroRandomSource
import net.minecraft.world.level.levelgen.synth.ImprovedNoise
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class WorldContext(val level: ServerLevel, val settings: Settings) {
    val seed: Long = level.seed
    val noise = ImprovedNoise(XoroshiroRandomSource(seed xor NOISE_SALT))
    private val generator = level.chunkSource.generator
    private val randomState = level.chunkSource.randomState()
    private val cache = ConcurrentHashMap<Long, Any>()
    private val limit = settings.general.siteCache.coerceAtLeast(64)

    fun surface(x: Int, z: Int): Int = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState)

    fun biome(x: Int, y: Int, z: Int): Holder<Biome> =
        generator.biomeSource.getNoiseBiome(x shr 2, y shr 2, z shr 2, randomState.sampler())

    fun sitesIn(ore: Ore, x0: Int, z0: Int, x1: Int, z1: Int): List<Site> {
        val pad = ore.reach.toInt() + 1
        return centers(ore, x0 - pad, z0 - pad, x1 + pad, z1 + pad).filter { it.overlaps(x0, z0, x1, z1) }
    }

    fun sitesNear(pos: BlockPos, radius: Int): List<Site> =
        settings.ores.flatMap { sitesIn(it, pos.x - radius, pos.z - radius, pos.x + radius, pos.z + radius) }

    fun gradeAt(ore: Ore, pos: BlockPos): Grade? =
        sitesIn(ore, pos.x, pos.z, pos.x, pos.z)
            .firstOrNull { pos.y in it.minY..it.maxY && it.radius(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, noise) <= 1.0 }?.grade

    private fun centers(ore: Ore, x0: Int, z0: Int, x1: Int, z1: Int): List<Site> {
        val deposit = ore.deposit ?: return emptyList()
        val anchor = deposit.anchor
        val parent = ore.anchor
        if (anchor != null && parent != null) {
            val padX = max(abs(anchor.offsetX[0]), abs(anchor.offsetX[1]))
            val padZ = max(abs(anchor.offsetZ[0]), abs(anchor.offsetZ[1]))
            return centers(parent, x0 - padX, z0 - padZ, x1 + padX, z1 + padZ)
                .mapNotNull { derived(ore, it) }
                .filter { it.x >= x0 && it.x <= x1 && it.z >= z0 && it.z <= z1 }
        }
        val spacing = deposit.spacing
        val found = ArrayList<Site>()
        for (cx in Math.floorDiv(x0, spacing)..Math.floorDiv(x1, spacing)) {
            for (cz in Math.floorDiv(z0, spacing)..Math.floorDiv(z1, spacing)) {
                val site = cell(ore, cx, cz)
                if (site != null && site.x >= x0 && site.x <= x1 && site.z >= z0 && site.z <= z1) found += site
            }
        }
        return found
    }

    private inline fun cached(key: Long, compute: () -> Site?): Site? {
        cache[key]?.let { return it as? Site }
        val site = compute()
        if (cache.size >= limit) cache.clear()
        cache[key] = site ?: NONE
        return site
    }

    private fun cell(ore: Ore, cx: Int, cz: Int): Site? = cached(Hash.of(ore.salt, cx.toLong(), cz.toLong())) {
        val deposit = ore.deposit!!
        val rng = Rng(Hash.of(seed, ore.salt, cx.toLong(), cz.toLong()))
        if (!rng.chance((deposit.chance * ore.maxWeight).coerceAtMost(1.0))) return@cached null
        val spacing = deposit.spacing
        val x = cx * spacing + rng.int(spacing)
        val z = cz * spacing + rng.int(spacing)
        val surface = surface(x, z)
        val weight = ore.weightIn(settings.provinces.of(biome(x, surface, z)))
        if (weight <= 0.0 || !rng.chance(weight / ore.maxWeight)) return@cached null
        if (deposit.minSurface != null && surface < deposit.minSurface) return@cached null
        val depth = deposit.surfaceDepth
        val y = if (depth != null) (surface - rng.pick(depth)).coerceIn(deposit.height[0], deposit.height[1]) else rng.pick(deposit.height)
        if (y > surface - deposit.minDepth) return@cached null
        build(ore, rng, x + 0.5, y.toDouble(), z + 0.5)
    }

    private fun derived(ore: Ore, parent: Site): Site? = cached(Hash.of(ore.salt, parent.id, 1L)) {
        val anchor = ore.deposit!!.anchor!!
        if (anchor.tiers.isNotEmpty() && parent.tier.name !in anchor.tiers) return@cached null
        val rng = Rng(Hash.of(seed, ore.salt, parent.id))
        if (!rng.chance(anchor.chance)) return@cached null
        val y = if (anchor.absoluteY) rng.pick(ore.deposit!!.height).toDouble() else parent.y + rng.pick(anchor.offsetY)
        if (y < level.minBuildHeight + 2 || y > level.maxBuildHeight - 2) return@cached null
        build(ore, rng, parent.x + rng.pick(anchor.offsetX), y, parent.z + rng.pick(anchor.offsetZ))
    }

    private fun build(ore: Ore, rng: Rng, x: Double, y: Double, z: Double): Site {
        val deposit = ore.deposit!!
        val shape = deposit.shape
        var pick = rng.double() * deposit.tiers.sumOf { it.weight }
        val tier = deposit.tiers.firstOrNull { pick -= it.weight; pick < 0.0 } ?: deposit.tiers.last()
        val semiLength = rng.pick(tier.length) / 2.0
        val semiWidth = rng.pick(tier.width) / 2.0
        val semiThickness = rng.pick(tier.thickness) / 2.0
        val yaw = rng.between(0.0, TWO_PI)
        val dip = Math.toRadians(rng.spread(shape.dip))
        val frame = frame(yaw, dip)
        val hasCore = ore.config.core?.let { ore.core != null && rng.chance(it.chance) } ?: false
        val grade = settings.grade(rng.double())
        val lumps = if (shape.kind == Kind.PODS) lumps(rng, frame, semiLength, semiWidth, semiThickness, rng.pick(shape.lumps)) else NO_LUMPS
        val leanX = if (shape.kind == Kind.PIPE) shape.lean * rng.between(-1.0, 1.0) else 0.0
        val leanZ = if (shape.kind == Kind.PIPE) shape.lean * rng.between(-1.0, 1.0) else 0.0
        return Site(ore, rng.long(), x, y, z, tier, grade, hasCore, frame, semiLength, semiWidth, semiThickness, lumps, leanX, leanZ)
    }

    private fun frame(yaw: Double, dip: Double): DoubleArray {
        val sy = sin(yaw)
        val cy = cos(yaw)
        val sd = sin(dip)
        val cd = cos(dip)
        return doubleArrayOf(cy, 0.0, sy, -sy * cd, -sd, cy * cd, -sy * sd, cd, cy * sd)
    }

    private fun lumps(rng: Rng, frame: DoubleArray, a: Double, b: Double, c: Double, count: Int): DoubleArray {
        val out = DoubleArray(count.coerceAtLeast(1) * 4)
        for (i in 0 until out.size / 4) {
            var px: Double
            var py: Double
            var pz: Double
            do {
                px = rng.between(-1.0, 1.0)
                py = rng.between(-1.0, 1.0)
                pz = rng.between(-1.0, 1.0)
            } while (px * px + py * py + pz * pz > 1.0)
            val l0 = px * a * 0.75
            val l1 = py * b * 0.75
            val l2 = pz * c * 0.75
            out[i * 4] = frame[0] * l0 + frame[3] * l1 + frame[6] * l2
            out[i * 4 + 1] = frame[1] * l0 + frame[4] * l1 + frame[7] * l2
            out[i * 4 + 2] = frame[2] * l0 + frame[5] * l1 + frame[8] * l2
            out[i * 4 + 3] = max(rng.between(0.3, 0.55) * (a + b) / 2.0, 1.8)
        }
        return out
    }

    companion object {
        private const val NOISE_SALT = 0x5DEECE66DL
        private const val TWO_PI = 6.283185307179586
        private val NONE = Any()
        private val NO_LUMPS = DoubleArray(0)
    }
}

object Worlds {
    private val contexts = ConcurrentHashMap<ResourceKey<Level>, WorldContext>()

    fun of(level: ServerLevel): WorldContext? {
        val settings = ConfigStore.current ?: return null
        if (level.dimension().location() !in settings.dimensions) return null
        contexts[level.dimension()]?.takeIf { it.level === level && it.settings === settings }?.let { return it }
        return contexts.compute(level.dimension()) { _, old ->
            old?.takeIf { it.level === level && it.settings === settings } ?: WorldContext(level, settings)
        }
    }

    fun clear() = contexts.clear()
}
