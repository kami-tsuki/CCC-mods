package kami.libs.jei.impl

object ProfitMath {
    fun cheapest(prices: List<Double?>): Double? = prices.filter { it != null && it > 0.0 }.minOfOrNull { it!! }

    fun total(slots: List<Double?>): Double? =
        if (slots.isEmpty() || slots.any { it == null }) null else slots.sumOf { it!! }

    fun percent(input: Double, output: Double): Long? =
        if (input <= 0.0) null else Math.round((output - input) / input * 100)

    fun label(percent: Long): String = when {
        percent > 0 -> "+$percent%"
        percent < 0 -> "−${-percent}%"
        else -> "±0%"
    }
}
