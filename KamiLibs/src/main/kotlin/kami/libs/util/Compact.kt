package kami.libs.util

import kotlin.math.abs

object Compact {
    fun scaled(v: Long, div: Long, decimalSeparator: Char = '.'): String {
        val tenths = Math.round(abs(v).toDouble() * 10.0 / div)
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) whole.toString() else "$whole$decimalSeparator$frac"
    }

    fun of(n: Long, k: String = "k", m: String = "M", b: String = "B"): String {
        val v = abs(n)
        if (v < 1_000) return n.toString()
        var (div, suffix) = when {
            v >= 1_000_000_000L -> 1_000_000_000L to b
            v >= 1_000_000L -> 1_000_000L to m
            else -> 1_000L to k
        }
        if (div == 1_000L && v.toDouble() / div >= 999.95) { div = 1_000_000L; suffix = m }
        else if (div == 1_000_000L && v.toDouble() / div >= 999.95) { div = 1_000_000_000L; suffix = b }
        return (if (n < 0) "-" else "") + scaled(v, div) + suffix
    }
}
