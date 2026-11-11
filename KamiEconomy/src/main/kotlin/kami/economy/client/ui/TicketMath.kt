package kami.economy.client.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

internal object TicketMath {
    private const val FAT_FINGER_PCT = 15L
    private const val NUDGE_PCT = 5L

    fun convert(n: Int, mul: Int, div: Int): Int =
        if (n > 0) (n.toLong() * div / mul).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else n

    fun fatFinger(price: Long, ref: Int): Boolean = ref > 0 && abs(price - ref) * 100 > ref * FAT_FINGER_PCT

    fun peg(course: Double, offsetPct: Long, limit: Long, buy: Boolean, max: Long): Long {
        val raw = course * (1 + offsetPct / 100.0)
        val capped = limit.takeIf { it > 0 }?.let { if (buy) minOf(raw, it.toDouble()) else maxOf(raw, it.toDouble()) } ?: raw
        return capped.roundToLong().coerceIn(1L, max)
    }

    fun nudge(price: Long, up: Boolean): Long {
        val step = max(1L, price * NUDGE_PCT / 100)
        return if (up) price + step else max(1L, price - step)
    }

    fun beat(best: Int, buy: Boolean): Long = if (buy) best + 1L else max(1L, best - 1L)
}
