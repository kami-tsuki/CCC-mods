package kami.economy.compat.create

import kami.libs.util.Compact
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

enum class Metric(val key: String) {
    PRICE("price"), BUY("buy"), SELL("sell"), DAY("day"), WEEK("week"), MONTH("month"), ALL("all"),
    SUPPLY("supply"), DEMAND("demand"), SPREAD("spread"), VOLUME("volume")
}

class Figures(val lot: Int, val buy: Int, val sell: Int, val last: Int, val change: Int, val available: Int, val demand: Int, val volume: Int)

object DisplayFormat {
    const val EMPTY = "—"

    fun render(metric: Metric, f: Figures, trend: Int?, perLot: Boolean, compact: Boolean, ascii: Boolean = false): String = when (metric) {
        Metric.PRICE -> money(f.last.takeIf { it > 0 } ?: f.buy, f.lot, perLot, compact, ascii)
        Metric.BUY -> money(f.buy, f.lot, perLot, compact, ascii)
        Metric.SELL -> money(f.sell, f.lot, perLot, compact, ascii)
        Metric.DAY -> change(f.change, compact, ascii)
        Metric.WEEK, Metric.MONTH, Metric.ALL -> trend?.let { change(it, compact, ascii) } ?: empty(ascii)
        Metric.SUPPLY -> count(f.available, compact, ascii)
        Metric.DEMAND -> count(f.demand, compact, ascii)
        Metric.SPREAD -> if (f.buy > 0 && f.sell > 0) number((f.buy - f.sell).coerceAtLeast(0) * 100.0 / f.buy, compact) + "%" else empty(ascii)
        Metric.VOLUME -> count(f.volume, compact, ascii)
    }

    fun empty(ascii: Boolean) = if (ascii) "-" else EMPTY

    fun money(lotPrice: Int, lot: Int, perLot: Boolean, compact: Boolean, ascii: Boolean = false): String {
        if (lotPrice <= 0 || lot <= 0) return empty(ascii)
        return number(if (perLot) lotPrice.toDouble() else lotPrice.toDouble() / lot, compact) + (if (ascii) " C" else " ◎") + if (perLot && lot > 1) "/lot" else ""
    }

    fun change(permille: Int, compact: Boolean, ascii: Boolean = false): String {
        val text = number(abs(permille) / 10.0, compact)
        val sign = if (permille > 0) (if (ascii) "+" else "▲") else if (ascii) "-" else "▼"
        return if (text == "0") "0%" else "$sign$text%"
    }

    fun count(n: Int, compact: Boolean, ascii: Boolean = false): String = when {
        n < 0 -> if (ascii) "INF" else "∞"
        compact -> Compact.of(n.toLong())
        else -> String.format(Locale.ROOT, "%,d", n)
    }

    fun number(v: Double, compact: Boolean): String {
        val a = abs(v)
        return when {
            a >= 1000 -> if (compact) Compact.of(Math.round(v)) else String.format(Locale.ROOT, "%,d", Math.round(v))
            a >= 100 -> Math.round(v).toString()
            else -> BigDecimal(v).setScale(if (a < 1) 2 else 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }
    }
}
