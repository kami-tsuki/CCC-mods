package kami.libs.ui.style

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object Format {
    private val date = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneOffset.UTC)

    fun number(n: Long): String = "%,d".format(n)
    fun number(n: Int): String = number(n.toLong())

    fun compact(n: Long): String {
        val v = abs(n)
        val sign = if (n < 0) "-" else ""
        if (v < 100_000) return sign + number(v)
        val units = listOf(1_000_000_000_000L to "T", 1_000_000_000L to "B", 1_000_000L to "M", 1_000L to "K")
        val (div, unit) = units.first { v >= it.first }
        val scaled = v.toDouble() / div
        return sign + (if (scaled < 10) "%.1f".format(scaled) else scaled.toLong().toString()) + unit
    }

    fun money(n: Long, compact: Boolean = false) = (if (compact) compact(n) else number(n)) + " " + Glyphs.Glyph.COIN.fallback
    fun signed(n: Long) = (if (n > 0) "+" else if (n < 0) "-" else "±") + number(abs(n))
    fun percent(fraction: Double, digits: Int = 0) = "%.${digits}f%%".format(fraction * 100)

    fun duration(ms: Long): String {
        val m = ms.coerceAtLeast(0) / 60_000
        val d = m / 1440
        val h = (m % 1440) / 60
        return when {
            d > 0 -> if (h > 0) "${d}d ${h}h" else "${d}d"
            h > 0 -> "${h}h ${m % 60}m"
            else -> "${m}m"
        }
    }

    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        if (at <= 0) return "never"
        val s = (now - at) / 1000
        return when {
            s < 90 -> "just now"
            s < 3600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }

    fun until(at: Long, now: Long = System.currentTimeMillis()) = if (at <= now) "now" else "in " + duration(at - now)
    fun exact(at: Long) = date.format(Instant.ofEpochMilli(at)) + " UTC"

    fun parseAmount(text: String): Long? {
        val t = text.trim().lowercase().replace(",", "").replace("_", "")
        if (t.isEmpty()) return null
        val multiplier = when (t.last()) { 'k' -> 1_000L; 'm' -> 1_000_000L; 'b' -> 1_000_000_000L; else -> 1L }
        val body = if (multiplier > 1) t.dropLast(1) else t
        val v = body.toDoubleOrNull() ?: return null
        return (v * multiplier).toLong()
    }

    fun plural(n: Int, word: String, many: String = word + "s") = "${number(n)} ${if (n == 1) word else many}"
}
