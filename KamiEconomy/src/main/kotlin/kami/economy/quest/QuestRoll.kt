package kami.economy.quest

import kami.economy.QuestSettings
import kami.economy.Traded
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

internal object QuestRoll {
    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L
    private const val AGE_DAYS = 31.0
    private const val AGE_POW = 0.8
    private const val RESEARCH_STEP = 5
    private const val XP_STEP = 10

    fun rarity(clock: QuestClock, now: Long, s: QuestSettings, rnd: Random): Int {
        for (r in 4 downTo 2) {
            val (min, max) = s.pityHours[r - 2]
            val hours = (now - clock.last[r - 2]) / HOUR.toDouble()
            val chance = if (hours < min) 0.0 else if (max <= min) 1.0 else ((hours - min) / (max - min)).coerceAtMost(1.0)
            if (chance > 0.0 && rnd.nextDouble() < chance) return r
        }
        return if (rnd.nextDouble() < s.uncommonShare) 1 else 0
    }

    fun ref(s: QuestSettings, tier: Int): Double = (s.amounts[0][0] / 2.0.pow(tier - 1)).coerceAtLeast(1.0)

    fun factors(
        dishes: List<Dish>, now: Long, s: QuestSettings, traded: (String) -> Traded?, lastQuest: (String) -> Long?, demand: (String) -> Long, avail: (String) -> Long
    ): List<PickFactor> {
        val prices = dishes.map { it.price }.filter { it > 0.0 }.sorted()
        val median = prices.getOrNull(prices.size / 2) ?: 1.0
        val cap = (1 + AGE_DAYS).pow(AGE_POW)
        return dishes.map { d ->
            val t = traded(d.item)
            val age = if (t == null || t.last <= 0L || t.sold + t.bought <= 0L) cap else (1 + (now - t.last).coerceAtLeast(0) / DAY.toDouble()).pow(AGE_POW).coerceAtMost(cap)
            val volume = 1 / sqrt(1 + ln(1 + ((t?.sold ?: 0L) + (t?.bought ?: 0L)).toDouble()))
            val ratio = if (d.price > 0.0) d.price / median else 1.0
            val value = d.tier.toDouble().pow(0.7) * ratio.pow(0.3)
            val ref = ref(s, d.tier)
            val dem = 1 / (1 + demand(d.item) / ref).pow(2) * (1 / sqrt(1 + avail(d.item) / ref))
            val cool = lastQuest(d.item)?.takeIf { s.cooldownDays > 0.0 }?.let { 1 - exp(-((now - it).coerceAtLeast(0) / DAY.toDouble()) / s.cooldownDays) } ?: 1.0
            val w = age * volume * value * dem * cool
            PickFactor(d.item, if (w.isFinite() && w > 0.0) w else 1e-9, age, volume, value, dem, cool)
        }
    }

    fun pick(factors: List<PickFactor>, rnd: Random): PickFactor? {
        if (factors.isEmpty()) return null
        var x = rnd.nextDouble() * factors.sumOf { it.weight }
        for (f in factors) {
            x -= f.weight
            if (x < 0.0) return f
        }
        return factors.last()
    }

    fun info(factors: List<PickFactor>, chosen: PickFactor): PickInfo = PickInfo(chosen.item, factors.size, factors.sortedByDescending { it.weight }.take(10))

    private fun gauss(rnd: Random) = sqrt(-2 * ln(1 - rnd.nextDouble())) * cos(2 * Math.PI * rnd.nextDouble())

    fun minusChance(s: QuestSettings, demand: Long, avail: Long, ref: Double): Double =
        minOf(maxOf(s.minusShare + 0.2 * tanh((demand - avail) / ref), 0.05), s.minusMax)

    fun offset(s: QuestSettings, demand: Long, avail: Long, ref: Double, rnd: Random): Double {
        val m = (s.offsetMean + s.offsetSpread * gauss(rnd)).coerceIn(s.offsetMin, s.offsetMax)
        return if (rnd.nextDouble() < minusChance(s, demand, avail, ref)) -m else m
    }

    fun amount(range: List<Int>, tier: Int, lot: Int, stack: Int, rnd: Random): Int {
        val raw = (rnd.nextLong(range[0].toLong(), range[1].toLong() + 1) shr (tier - 1).coerceIn(0, 30)).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val unit = if (stack > lot && stack % lot == 0) stack else lot
        val u = if (raw >= unit) unit else lot
        return maxOf(lot, raw - raw % u)
    }

    private fun steps(min: Int, max: Int, step: Int): List<Int> = ((min + step - 1) / step * step..max step step).toList().ifEmpty { listOf(min) }

    private fun falling(values: List<Int>, rnd: Random): Int {
        val n = values.size
        var x = rnd.nextInt(n * (n + 1) / 2)
        values.forEachIndexed { i, v ->
            if (x < n - i) return v
            x -= n - i
        }
        return values.last()
    }

    fun boosts(rarity: Int, minus: Boolean, s: QuestSettings, rnd: Random): List<QuestBoost> {
        val kinds = if (minus) listOf("research", "xp") else listOf(if (rnd.nextBoolean()) "research" else "xp")
        return kinds.map { kind ->
            val research = kind == "research"
            val range = (if (research) s.research else s.xp)[rarity]
            QuestBoost(kind, falling(steps(range.minPct, range.maxPct, if (research) RESEARCH_STEP else XP_STEP), rnd), falling(steps(range.minHours, range.maxHours, 1), rnd))
        }
    }
}
