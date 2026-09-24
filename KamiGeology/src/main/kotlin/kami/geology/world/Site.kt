package kami.geology.world

import kami.geology.config.Grade
import kami.geology.config.Kind
import kami.geology.config.Ore
import kami.geology.config.Tier
import net.minecraft.world.level.levelgen.synth.ImprovedNoise
import java.util.function.DoubleUnaryOperator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class Site(
    val ore: Ore,
    val id: Long,
    val x: Double,
    val y: Double,
    val z: Double,
    val tier: Tier,
    val grade: Grade,
    val hasCore: Boolean,
    private val frame: DoubleArray,
    private val semiLength: Double,
    private val semiWidth: Double,
    private val semiThickness: Double,
    private val lumps: DoubleArray,
    private val leanX: Double,
    private val leanZ: Double
) {
    private val shape = ore.deposit!!.shape
    private val warpAmplitude = shape.warp * (semiLength + semiWidth + semiThickness) / 3.0
    private val warpFrequency = 1.0 / shape.warpScale
    private val pipeTaper = shape.taper.coerceIn(0.0, 0.95)

    val minX: Int
    val maxX: Int
    val minY: Int
    val maxY: Int
    val minZ: Int
    val maxZ: Int

    init {
        val scale = ore.haloScale
        val lumpPad = (lumps.indices step 4).maxOfOrNull { lumps[it + 3] * scale } ?: 0.0
        val pad = warpAmplitude + 1.5 + lumpPad
        fun extent(i: Int) = sqrt(
            (frame[i] * semiLength).pow(2) + (frame[3 + i] * semiWidth).pow(2) + (frame[6 + i] * semiThickness).pow(2)
        ) * scale + pad
        val lean = if (shape.kind == Kind.PIPE) semiThickness else 0.0
        val hx = extent(0) + abs(leanX) * lean
        val hy = extent(1)
        val hz = extent(2) + abs(leanZ) * lean
        minX = (x - hx).toInt() - 1
        maxX = (x + hx).toInt() + 1
        val limit = ore.deposit!!.limit
        minY = max((y - hy).toInt() - 1, limit?.get(0) ?: Int.MIN_VALUE)
        maxY = min((y + hy).toInt() + 1, limit?.get(1) ?: Int.MAX_VALUE)
        minZ = (z - hz).toInt() - 1
        maxZ = (z + hz).toInt() + 1
    }

    val length: Int get() = (semiLength * 2).roundToInt()
    val width: Int get() = (semiWidth * 2).roundToInt()
    val thickness: Int get() = (semiThickness * 2).roundToInt()

    fun overlaps(x0: Int, z0: Int, x1: Int, z1: Int) = maxX >= x0 && minX <= x1 && maxZ >= z0 && minZ <= z1

    fun radius(px: Double, py: Double, pz: Double, noise: ImprovedNoise): Double {
        var dx = px - x
        var dy = py - y
        var dz = pz - z
        if (warpAmplitude > 0.0) {
            val f = warpFrequency
            dx += warpAmplitude * noise.noise(px * f, py * f, pz * f)
            dy += warpAmplitude * noise.noise(px * f + 31.7, py * f + 11.3, pz * f - 17.9)
            dz += warpAmplitude * noise.noise(px * f - 23.1, py * f + 47.9, pz * f + 5.3)
        }
        if (shape.kind == Kind.PODS) return podRadius(dx, dy, dz)

        val u0 = frame[0] * dx + frame[1] * dy + frame[2] * dz
        val u1 = frame[3] * dx + frame[4] * dy + frame[5] * dz
        val u2 = frame[6] * dx + frame[7] * dy + frame[8] * dz
        var a = u0 / semiLength
        var b = u1 / semiWidth
        var c = u2 / semiThickness

        return when (shape.kind) {
            Kind.SEAM -> {
                val pinch = 0.4 + 0.6 * (noise.noise(u0 * 0.07, u1 * 0.07, (id and 0xFFFFL) * 0.37) * 0.5 + 0.5)
                sqrt(a * a + b * b + (layered(c) / pinch).pow(2))
            }
            Kind.PIPE -> {
                a = (u0 - leanX * u2) / semiLength
                b = (u1 - leanZ * u2) / semiWidth
                val scale = max(1.0 - pipeTaper * (1.0 - (c + 1.0) * 0.5), 0.05)
                val horizontal = sqrt(a * a + b * b) / scale
                (horizontal.pow(4) + c.pow(4)).pow(0.25)
            }
            Kind.BAND -> {
                val r = sqrt(a * a + b * b + c * c)
                val phase = u2 / shape.bandWidth + 0.6 * noise.noise(u0 * 0.05, u1 * 0.05, u2 * 0.05)
                if (r < 1.0 && sin(phase * TWO_PI) < shape.bandCut) OUTSIDE else r
            }
            Kind.CLOUD -> {
                val r = sqrt(a * a + b * b + c * c)
                if (r < 1.0 && noise.noise(px * 0.12, py * 0.12, pz * 0.12) < shape.cut) OUTSIDE else r
            }
            else -> sqrt(a * a + b * b + c * c)
        }
    }

    fun columnOre(px: Double, pz: Double, ya: Int, yb: Int, noise: ImprovedNoise, density: DoubleUnaryOperator): Double {
        if (shape.kind == Kind.PIPE) return Double.NaN
        var dx = px - x
        var dz = pz - z
        var wy = 0.0
        if (warpAmplitude > 0.0) {
            val f = warpFrequency
            dx += warpAmplitude * noise.noise(px * f, y * f, pz * f)
            wy = warpAmplitude * noise.noise(px * f + 31.7, y * f + 11.3, pz * f - 17.9)
            dz += warpAmplitude * noise.noise(px * f - 23.1, y * f + 47.9, pz * f + 5.3)
        }
        val top = yb + 1.0
        if (shape.kind == Kind.PODS) return podsColumn(dx, dz, wy, ya.toDouble(), top, density)

        val b0 = frame[1] / semiLength
        val b1 = frame[4] / semiWidth
        val b2 = frame[7] / semiThickness
        val a0 = (frame[0] * dx + frame[2] * dz) / semiLength
        val a1 = (frame[3] * dx + frame[5] * dz) / semiWidth
        val a2 = (frame[6] * dx + frame[8] * dz) / semiThickness
        val quad = b0 * b0 + b1 * b1 + b2 * b2
        if (quad <= 1e-12) return 0.0
        val lin = a0 * b0 + a1 * b1 + a2 * b2
        val rest = a0 * a0 + a1 * a1 + a2 * a2
        val dStar = -lin / quad
        val minR2 = rest - lin * lin / quad
        if (minR2 >= 1.0) return 0.0
        val half = sqrt((1.0 - minR2) / quad)
        val lo = max(y + dStar - half - wy, ya.toDouble())
        val hi = min(y + dStar + half - wy, top)
        if (hi <= lo) return 0.0
        val step = (hi - lo) / CHORD_SAMPLES
        var sum = 0.0
        for (k in 0 until CHORD_SAMPLES) {
            val d = lo + (k + 0.5) * step - y + wy - dStar
            sum += density.applyAsDouble(sqrt(minR2 + quad * d * d))
        }
        return sum * step * fill()
    }

    private fun podsColumn(dx: Double, dz: Double, wy: Double, ya: Double, top: Double, density: DoubleUnaryOperator): Double {
        var total = 0.0
        var i = 0
        while (i < lumps.size) {
            val ex = dx - lumps[i]
            val ez = dz - lumps[i + 2]
            val radius = lumps[i + 3]
            val h2 = ex * ex + ez * ez
            if (h2 < radius * radius) {
                val half = sqrt(radius * radius - h2) / POD_STRETCH
                val centre = y + lumps[i + 1] - wy
                val lo = max(centre - half, ya)
                val hi = min(centre + half, top)
                if (hi > lo) {
                    val step = (hi - lo) / POD_SAMPLES
                    for (k in 0 until POD_SAMPLES) {
                        val ey = (lo + (k + 0.5) * step - y + wy - lumps[i + 1]) * POD_STRETCH
                        total += density.applyAsDouble(sqrt(h2 + ey * ey) / radius) * step
                    }
                }
            }
            i += 4
        }
        return total
    }

    private fun fill(): Double = when (shape.kind) {
        Kind.SEAM -> 0.7
        Kind.BAND -> 0.5 + asin(shape.bandCut.coerceIn(-1.0, 1.0)) / PI
        Kind.CLOUD -> 1.0 / (1.0 + exp(1.702 * shape.cut / 0.33))
        else -> 1.0
    }

    private fun layered(c: Double): Double {
        val layers = shape.layers
        if (layers <= 1) return c
        val gap = 1.7 / layers
        val index = Math.round(c / gap).toDouble().coerceIn(-(layers - 1) / 2.0, (layers - 1) / 2.0)
        return (c - index * gap) * layers
    }

    private fun podRadius(dx: Double, dy: Double, dz: Double): Double {
        var best = OUTSIDE
        var i = 0
        while (i < lumps.size) {
            val ex = dx - lumps[i]
            val ey = (dy - lumps[i + 1]) * 1.25
            val ez = dz - lumps[i + 2]
            best = minOf(best, sqrt(ex * ex + ey * ey + ez * ez) / lumps[i + 3])
            i += 4
        }
        return best
    }

    companion object {
        const val OUTSIDE = 9.0
        private const val CHORD_SAMPLES = 5
        private const val POD_SAMPLES = 3
        private const val POD_STRETCH = 1.25
        private const val TWO_PI = 6.283185307179586
    }
}
