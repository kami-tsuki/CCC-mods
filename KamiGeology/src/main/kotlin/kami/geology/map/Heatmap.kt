package kami.geology.map

import kami.geology.config.Kind
import kami.geology.config.Ore
import kami.geology.util.Hash
import kami.geology.world.Site
import kami.geology.world.WorldContext
import java.util.concurrent.atomic.AtomicLong
import java.util.function.DoubleUnaryOperator
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Heatmap {
    const val UNIT_MAX = 16384.0
    const val MAX_ORES = 63

    const val ANALYTIC_CELL = 8

    private const val SAMPLE_Y = 64
    private val logMax = ln(1.0 + UNIT_MAX)

    class Query(val x0: Int, val z0: Int, val cell: Int, val w: Int, val h: Int, val y0: Int, val y1: Int) {
        val xb get() = x0 + w * cell - 1
        val zb get() = z0 + h * cell - 1
    }

    class Timing {
        val provinces = AtomicLong()
        val sites = AtomicLong()
        val paint = AtomicLong()
    }

    fun encode(unit: Double): Int = if (unit <= 0.0) 0 else (ln(1.0 + unit) / logMax * 255.0).roundToInt().coerceIn(1, 255)

    fun decode(value: Int): Double = if (value <= 0) 0.0 else exp(value / 255.0 * logMax) - 1.0

    fun provinces(world: WorldContext, q: Query, cancelled: () -> Boolean = { false }, timing: Timing? = null): ByteArray? {
        val started = System.nanoTime()
        val names = world.settings.provinces.names.toList()
        val out = ByteArray(q.w * q.h)
        for (j in 0 until q.h) {
            if (cancelled()) return null
            for (i in 0 until q.w) {
                val biome = world.biome(q.x0 + i * q.cell + q.cell / 2, SAMPLE_Y, q.z0 + j * q.cell + q.cell / 2)
                val name = world.settings.provinces.of(biome)
                out[j * q.w + i] = ((if (name == null) -1 else names.indexOf(name)) + 1).toByte()
            }
        }
        timing?.provinces?.addAndGet(System.nanoTime() - started)
        return out
    }

    fun deposits(world: WorldContext, q: Query, ore: Ore, cancelled: () -> Boolean = { false }, timing: Timing? = null): FloatArray? {
        val out = FloatArray(q.w * q.h)
        if (ore.deposit == null) return out
        var started = System.nanoTime()
        val sites = world.sitesIn(ore, q.x0, q.z0, q.xb, q.zb)
        timing?.sites?.addAndGet(System.nanoTime() - started)
        started = System.nanoTime()
        for (site in sites) {
            if (cancelled()) return null
            paint(world, site, q, out)
        }
        timing?.paint?.addAndGet(System.nanoTime() - started)
        return out
    }

    fun scatterColumn(ore: Ore): Double {
        val scatter = ore.scatter ?: return 0.0
        return scatter.perChunk * (scatter.size[0] + scatter.size[1]) / 2.0 / 256.0
    }

    fun scatterShare(low: Int, high: Int, triangle: Boolean, y0: Int, y1: Int): Double {
        val lo = low.toDouble()
        val hi = high.toDouble()
        if (hi <= lo) return if (lo >= y0 && lo <= y1) 1.0 else 0.0
        val a = ((max(y0.toDouble(), lo) - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        val b = ((min(y1 + 1.0, hi) - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        if (b <= a) return 0.0
        return if (triangle) tri(b) - tri(a) else b - a
    }

    private fun tri(t: Double) = if (t <= 0.5) 2.0 * t * t else 1.0 - 2.0 * (1.0 - t) * (1.0 - t)

    fun totals(world: WorldContext, q: Query, mask: Long, cancelled: () -> Boolean = { false }): FloatArray? {
        val ores = world.settings.ores
        val names = world.settings.provinces.names.toList()
        val provinces = provinces(world, q, cancelled) ?: return null
        val totals = FloatArray(ores.size)
        val area = q.cell.toFloat() * q.cell
        for (o in ores.indices) {
            if (o >= MAX_ORES || (mask shr o) and 1L == 0L) continue
            val ore = ores[o]
            val layer = deposits(world, q, ore, cancelled) ?: return null
            var sum = layer.sum() * area
            val scatter = ore.scatter
            if (scatter != null) {
                val perColumn = scatterColumn(ore) * scatterShare(scatter.height[0], scatter.height[1], scatter.distribution == kami.geology.config.Distribution.TRIANGLE, q.y0, q.y1)
                val homes = ore.scatterProvinces
                val cells = if (homes == null) provinces.size else provinces.count { p -> val index = (p.toInt() and 0xFF) - 1; index >= 0 && names[index] in homes }
                sum += (perColumn * cells * area).toFloat()
            }
            totals[o] = sum
        }
        return totals
    }

    private fun paint(world: WorldContext, site: Site, q: Query, out: FloatArray) {
        val deposit = site.ore.deposit ?: return
        val cell = q.cell
        val yA = max(site.minY, q.y0)
        val yB = min(site.maxY, q.y1)
        val x0 = max(site.minX, q.x0)
        val x1 = min(site.maxX, q.xb)
        val z0 = max(site.minZ, q.z0)
        val z1 = min(site.maxZ, q.zb)
        if (yA > yB || x0 > x1 || z0 > z1) return

        val sub = if (cell >= 16) 2 else 1
        val weight = 1.0 / (sub * sub)
        val density = min(1.0, deposit.density * site.grade.density)
        val coreRadius = if (site.hasCore) site.ore.config.core?.radius ?: 0.0 else 0.0
        val chance = DoubleUnaryOperator { r -> if (r < coreRadius) 1.0 else density * fringe(r, deposit.bodyRadius, deposit.fringe) }
        val analytic = cell >= ANALYTIC_CELL && deposit.shape.kind != Kind.PIPE
        val step = if (cell <= 4) 2 else 4
        val noise = world.noise

        for (j in (z0 - q.z0) / cell..(z1 - q.z0) / cell) for (i in (x0 - q.x0) / cell..(x1 - q.x0) / cell) {
            var sum = 0.0
            for (sx in 0 until sub) for (sz in 0 until sub) {
                val px = q.x0 + i * cell + (sx + 0.5) * cell / sub
                val pz = q.z0 + j * cell + (sz + 0.5) * cell / sub
                if (analytic) {
                    sum += site.columnOre(px, pz, yA, yB, noise, chance)
                } else {
                    var y = yA
                    while (y <= yB) {
                        val jitter = Hash.unit(Hash.at(site.id, i, j, y * 4 + sx * 2 + sz))
                        val r = site.radius(px, y + jitter * step, pz, noise)
                        if (r <= 1.0) sum += chance.applyAsDouble(r) * step
                        y += step
                    }
                }
            }
            out[j * q.w + i] += (sum * weight).toFloat()
        }
    }

    private fun fringe(r: Double, body: Double, edge: Double) = if (r <= body) 1.0 else 1.0 - (1.0 - edge) * (r - body) / (1.0 - body)

    fun probe(world: WorldContext, x: Int, z: Int, y0: Int, y1: Int): List<String> {
        val lines = ArrayList<String>()
        val holder = world.biome(x, SAMPLE_Y, z)
        val province = world.settings.provinces.of(holder)
        lines += "X $x  Z $z  surface Y ${world.surface(x, z)}"
        lines += "${holder.unwrapKey().map { it.location().toString() }.orElse("?")}  [${province ?: "no province"}]"
        var any = false
        for (ore in world.settings.ores) for (site in world.sitesIn(ore, x, z, x, z)) {
            any = true
            var low = Int.MAX_VALUE
            var high = Int.MIN_VALUE
            for (y in max(site.minY, y0)..min(site.maxY, y1)) {
                if (site.radius(x + 0.5, y + 0.5, z + 0.5, world.noise) <= 1.0) {
                    low = min(low, y)
                    high = max(high, y)
                }
            }
            val where = if (low <= high) "ore Y $low..$high" else "halo only"
            lines += "${ore.id} ${site.tier.name} ${site.grade.name}${if (site.hasCore) " core" else ""}, ${site.length}x${site.width}x${site.thickness}: $where, center ${site.x.toInt()} ${site.y.toInt()} ${site.z.toInt()}"
        }
        if (!any) lines += "no deposit here"
        return lines
    }

    /** Single-column check for one ore, used by the tier 1 prospector (scans just the block the player stands on). */
    fun probeOre(world: WorldContext, ore: Ore, x: Int, z: Int, y0: Int, y1: Int): String {
        for (site in world.sitesIn(ore, x, z, x, z)) {
            var low = Int.MAX_VALUE
            var high = Int.MIN_VALUE
            for (y in max(site.minY, y0)..min(site.maxY, y1)) {
                if (site.radius(x + 0.5, y + 0.5, z + 0.5, world.noise) <= 1.0) {
                    low = min(low, y)
                    high = max(high, y)
                }
            }
            if (low <= high) return "${ore.id} detected here, Y $low..$high (${site.tier.name} ${site.grade.name})"
        }
        return "No ${ore.id} detected in this block"
    }
}
