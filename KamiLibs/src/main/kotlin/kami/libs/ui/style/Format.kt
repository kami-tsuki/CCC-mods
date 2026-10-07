package kami.libs.ui.style

import kami.libs.ui.text.tr
import kami.libs.util.Time
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

object Format {
    @Volatile
    var locale: Locale = Locale.US

    fun localeOf(code: String): Locale = code.split('_', '-').let { Locale.of(it[0], it.getOrElse(1) { "" }.uppercase()) }

    private const val COMPACT_FROM = 100_000L
    private val compacts = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    }

    fun full(n: Long, locale: Locale = this.locale): String = NumberFormat.getIntegerInstance(locale).format(n)
    fun number(n: Int): String = number(n.toLong())

    fun expand(text: String): String? {
        if (compacts.isEmpty() || text.none { it.isDigit() }) return null
        return compacts.entries.firstOrNull { text.contains(it.key) }?.let { text.replace(it.key, it.value) }
    }

    fun decimal(v: Double, digits: Int = 1, locale: Locale = this.locale): String = String.format(locale, "%.${digits}f", v)

    fun number(n: Long): String {
        val v = abs(n)
        val sign = if (n < 0) "-" else ""
        if (v < COMPACT_FROM) return full(n)
        val units = listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")
        val (div, unit) = units.first { v >= it.first }
        val text = sign + tr("kami_libs.unit.$unit", decimal(v.toDouble() / div))
        compacts[text] = full(n)
        return text
    }

    fun money(n: Long) = tr("kami_libs.unit.money", number(n))
    fun signed(n: Long) = (if (n > 0) "+" else if (n < 0) "-" else "±") + number(abs(n))
    fun signedMoney(n: Long) = tr("kami_libs.unit.money", signed(n))
    fun perDay(amount: String) = tr("kami_libs.unit.per_day", amount)
    fun percent(fraction: Double, digits: Int = 0) = tr("kami_libs.unit.percent", decimal(fraction * 100, digits))

    fun days(n: Long) = tr("kami_libs.unit.day.short", number(n))
    fun hours(n: Long) = tr("kami_libs.unit.hour.short", number(n))
    fun minutes(n: Long) = tr("kami_libs.unit.minute.short", number(n))

    fun duration(ms: Long): String {
        val m = ms.coerceAtLeast(0) / 60_000
        val d = m / 1440
        val h = (m % 1440) / 60
        return when {
            d > 0 -> if (h > 0) "${days(d)} ${hours(h)}" else days(d)
            h > 0 -> "${hours(h)} ${minutes(m % 60)}"
            else -> minutes(m)
        }
    }

    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        if (at <= 0) return tr("kami_libs.time.never")
        val s = (now - at) / 1000
        return when {
            s < 90 -> tr("kami_libs.time.just_now")
            s < 3600 -> tr("kami_libs.time.ago", minutes(s / 60))
            s < 86_400 -> tr("kami_libs.time.ago", hours(s / 3600))
            else -> tr("kami_libs.time.ago", days(s / 86_400))
        }
    }

    fun dayLabel(day: Long) = ago((day + 1) * Time.DAY_MS - 1)

    fun until(at: Long, now: Long = System.currentTimeMillis()) = if (at <= now) tr("kami_libs.time.now") else tr("kami_libs.time.in", duration(at - now))

    fun exact(at: Long): String {
        val pattern = runCatching { DateTimeFormatter.ofPattern(tr("kami_libs.time.date_pattern"), locale) }.getOrElse { DateTimeFormatter.ISO_LOCAL_DATE_TIME }
        return pattern.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
    }

    fun parseAmount(text: String): Long? {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val t = text.trim().lowercase().replace(symbols.groupingSeparator.toString(), "").replace("_", "").replace(symbols.decimalSeparator, '.')
        if (t.isEmpty()) return null
        val multiplier = when (t.last()) { 'k' -> 1_000L; 'm' -> 1_000_000L; 'b' -> 1_000_000_000L; else -> 1L }
        val body = if (multiplier > 1) t.dropLast(1) else t
        val v = body.toDoubleOrNull() ?: return null
        return (v * multiplier).toLong()
    }
}
