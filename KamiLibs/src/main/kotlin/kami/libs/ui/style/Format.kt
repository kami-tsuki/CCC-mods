package kami.libs.ui.style

import kami.libs.ui.text.tr
import kami.libs.util.Compact
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
        set(value) {
            if (field != value) { field = value; reset() }
        }

    fun localeOf(code: String): Locale = code.split('_', '-').let { Locale.of(it[0], it.getOrElse(1) { "" }.uppercase()) }

    const val COIN = "◎"
    private const val COMPACT_FROM = 100_000L
    private val compacts = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 512
    }

    private val units = listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")
    private val integerFormats = HashMap<Locale, NumberFormat>()

    @Volatile
    var hasCompacts = false
        private set

    fun full(n: Long, locale: Locale = this.locale): String =
        synchronized(integerFormats) { integerFormats.getOrPut(locale) { NumberFormat.getIntegerInstance(locale) }.format(n) }

    fun number(n: Int): String = number(n.toLong())

    fun expand(text: String): String? {
        if (!hasCompacts || text.none { it.isDigit() }) return null
        synchronized(compacts) {
            for ((key, value) in compacts) {
                val at = text.indexOf(key)
                if (at >= 0 && (at == 0 || !text[at - 1].isLetterOrDigit())) return text.replaceFirst(key, value)
            }
        }
        return null
    }

    fun decimal(v: Double, digits: Int = 1, locale: Locale = this.locale): String = String.format(locale, "%.${digits}f", v)

    fun reset() {
        synchronized(numbers) { numbers.clear() }
        synchronized(compacts) { compacts.clear() }
        hasCompacts = false
    }

    private val numbers = object : LinkedHashMap<Long, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 512
    }

    fun number(n: Long): String = if (abs(n) < COMPACT_FROM) full(n, locale) else synchronized(numbers) { numbers.getOrPut(n) { numberText(n, locale) } }

    private fun numberText(n: Long, loc: Locale): String {
        val v = abs(n)
        val sign = if (n < 0) "-" else ""
        if (v < COMPACT_FROM) return full(n, loc)
        var index = units.indexOfFirst { v >= it.first }
        if (index > 0 && v.toDouble() / units[index].first >= 999.95) index--
        val (div, unit) = units[index]
        val text = sign + tr("kami_libs.unit.$unit", Compact.scaled(v, div, DecimalFormatSymbols.getInstance(loc).decimalSeparator))
        synchronized(compacts) { compacts[text] = full(n, loc) }
        hasCompacts = true
        return text
    }

    fun money(n: Long) = tr("kami_libs.unit.money", number(n))
    fun rate(v: Double): String = if (abs(v) < 100) decimal(v, if (abs(v) < 10) 2 else 1) else number(Math.round(v))
    fun rateMoney(v: Double): String = if (v <= 0) "–" else tr("kami_libs.unit.money", rate(v))
    fun unitMoney(lotPrice: Long, lot: Int): String = if (lotPrice <= 0 || lot <= 0) "–" else rateMoney(lotPrice.toDouble() / lot)

    fun signed(n: Long) = (if (n > 0) "+" else if (n < 0) "-" else "±") + number(abs(n))
    fun signedMoney(n: Long) = tr("kami_libs.unit.money", signed(n))
    fun perDay(amount: String) = tr("kami_libs.unit.per_day", amount)
    fun percent(fraction: Double, digits: Int = 0) = tr("kami_libs.unit.percent", decimal(fraction * 100, digits))

    fun days(n: Long) = tr("kami_libs.unit.day.short", number(n))
    private fun hours(n: Long) = tr("kami_libs.unit.hour.short", number(n))
    private fun minutes(n: Long) = tr("kami_libs.unit.minute.short", number(n))

    fun duration(ms: Long): String {
        if (ms < 60_000) return tr("kami_libs.unit.second.short", number(ms.coerceAtLeast(0) / 1000))
        val m = ms / 60_000
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

    private val dateFormats = HashMap<Pair<Locale, String>, DateTimeFormatter>()

    fun exact(at: Long): String {
        val loc = locale
        val pattern = tr("kami_libs.time.date_pattern")
        val f = synchronized(dateFormats) {
            dateFormats.getOrPut(loc to pattern) {
                runCatching { DateTimeFormatter.ofPattern(pattern, loc) }.getOrElse { DateTimeFormatter.ISO_LOCAL_DATE_TIME }.withZone(ZoneId.systemDefault())
            }
        }
        return f.format(Instant.ofEpochMilli(at))
    }

    fun parseAmount(text: String, allowNegative: Boolean = false): Long? {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val grouping = symbols.groupingSeparator
        val decimal = symbols.decimalSeparator
        val s = text.trim().lowercase().replace("_", "")
        if (s.isEmpty()) return null
        val multiplier = when (s.last()) { 'k' -> 1_000.0; 'm' -> 1_000_000.0; 'b' -> 1_000_000_000.0; else -> 1.0 }
        val body = if (multiplier > 1) s.dropLast(1) else s
        val t = if (multiplier > 1 && grouping == '.' && decimal !in body) body.replace('.', decimal) else body
        val v = t.replace(grouping.toString(), "").replace(decimal, '.').toDoubleOrNull() ?: return null
        val scaled = v * multiplier
        if (scaled.isNaN() || scaled.isInfinite() || scaled >= 9.2e18 || scaled <= -9.2e18) return null
        if (scaled < 0 && !allowNegative) return null
        return Math.round(scaled)
    }
}
