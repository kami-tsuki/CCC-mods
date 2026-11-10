package kami.economy.villager

import kami.economy.VillagerSettings
import kami.libs.economy.CleanStep
import java.util.UUID
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

enum class Side { SELL, BUY }

object VillagerMath {
    const val DAY_MS = 20 * 60 * 1000L

    fun spread(id: UUID, index: Int, epoch: Int, pct: Double): Double {
        var x = id.mostSignificantBits xor id.leastSignificantBits.rotateLeft(17) xor index * -0x61c8864680b583ebL xor epoch * 0x632BE59BD9B4E019L
        x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
        x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
        x = x xor (x ushr 31)
        return ((x ushr 11).toDouble() / (1L shl 53) * 2 - 1) * pct
    }

    fun demand(demand: Int, multiplier: Float, cap: Double) = 1 + (max(0, demand) * multiplier.toDouble()).coerceIn(0.0, cap)

    fun price(value: Double, side: Side, spread: Double, demand: Double, min: Int): Long {
        val raw = value * (1 + spread) * if (side == Side.SELL) demand else 1 / demand
        return max(min.toLong(), raw.roundToLong())
    }

    fun tax(price: Long, pct: Int) = CleanStep.charge(price, pct)

    fun seed(recipe: Double?, emerald: Double?, side: Side, s: VillagerSettings): Double? {
        val raw = recipe?.takeIf { it > 0 }?.let { it * s.craftMul } ?: emerald?.takeIf { it > 0 } ?: return null
        return raw * seedMul(side, s)
    }

    fun seedMul(side: Side, s: VillagerSettings) = if (side == Side.SELL) s.unknownMul else s.buySeedMul

    fun enchantEmeralds(treasure: Boolean) = if (treasure) 24.0 else 12.0

    fun decayed(course: Double, floor: Double, since: Long, now: Long, pct: Double): Double {
        if (course <= floor || pct <= 0) return course
        val days = (now - since).coerceAtLeast(0) / DAY_MS.toDouble()
        return max(floor, course * (1 - pct).pow(days))
    }

    fun pull(course: Double, price: Double, weight: Double, depth: Int, clip: Double): Double {
        if (course <= 0) return price
        val p = price.coerceIn(course * (1 - clip), course * (1 + clip))
        return course + weight / (weight + depth) * (p - course)
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
