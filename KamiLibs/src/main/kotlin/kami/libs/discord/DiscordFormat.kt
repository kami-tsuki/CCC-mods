package kami.libs.discord

import net.dv8tion.jda.api.utils.MarkdownSanitizer
import kami.libs.util.Compact
import java.util.Locale
import kotlin.math.roundToLong

object DiscordFormat {
    const val GOOD = 0x57F287
    const val BAD = 0xED4245
    const val NEUTRAL = 0x5865F2
    const val GOLD = 0xF1C40F

    private const val BLOCKS = "▁▂▃▄▅▆▇█"
    private const val SPARK_MAX = 24

    const val SPUR = "¤"

    fun money(n: Number): String = "${Compact.of(n.toLong())} $SPUR"

    fun moneyFull(n: Number): String = String.format(Locale.ROOT, "%,d $SPUR", n.toLong())

    fun each(v: Double): String = when {
        !v.isFinite() || v <= 0 -> "0"
        v < 0.01 -> java.math.BigDecimal(v).round(java.math.MathContext(2)).toPlainString()
        v < 10 -> trimmed(String.format(Locale.ROOT, "%.2f", v))
        v < 100 -> trimmed(String.format(Locale.ROOT, "%.1f", v))
        v < 10_000 -> String.format(Locale.ROOT, "%,d", v.roundToLong())
        else -> Compact.of(v.roundToLong())
    } + " $SPUR"

    private fun trimmed(s: String) = if ('.' in s) s.trimEnd('0').trimEnd('.') else s

    fun num(n: Long): String = Compact.of(n)

    fun pct(x: Double): String = trimmed(String.format(Locale.ROOT, "%.1f", x)) + "%"

    fun player(name: String): String = literal(name)

    fun players(names: List<String>, max: Int = 5): String =
        names.take(max).joinToString(", ") { player(it) } + if (names.size > max) " +${names.size - max}" else ""

    fun country(name: String): String = "**${escape(name)}**"

    fun fields(vararg entries: Pair<String, String>?): List<EmbedField> =
        entries.filterNotNull().filter { it.first.isNotBlank() && it.second.isNotBlank() }.map { EmbedField(it.first, it.second) }

    fun wide(name: String, value: String): EmbedField = EmbedField(name, value, false)

    private val spaces = Regex("\\s+")
    private val special = Regex("[\\[\\]()#>]")

    fun escape(text: String): String = MarkdownSanitizer.escape(text).replace(special, "\\\\$0")

    fun literal(text: String, max: Int = 100): String {
        val clean = text.replace('`', '\'').replace(spaces, " ").trim().ifEmpty { "?" }
        val cut = if (clean.length <= max) clean else clean.take(max).substringBeforeLast(' ').ifEmpty { clean.take(max) } + "…"
        return "`$cut`"
    }

    fun ago(ms: Long): String = "<t:${ms / 1000}:R>"

    fun change(pct: Double): String = when {
        pct > 0 -> String.format(Locale.ROOT, "▲ %.1f%%", pct)
        pct < 0 -> String.format(Locale.ROOT, "▼ %.1f%%", -pct)
        else -> "= 0.0%"
    }

    fun spark(values: List<Number>): String {
        val v = values.takeLast(SPARK_MAX).map { it.toDouble() }
        val lo = v.minOrNull() ?: return ""
        val span = v.max() - lo
        return v.joinToString("") { BLOCKS[if (span <= 0) BLOCKS.length / 2 else ((it - lo) / span * (BLOCKS.length - 1)).toInt().coerceIn(0, BLOCKS.length - 1)].toString() }
    }

    fun duration(ms: Long): String {
        val minutes = maxOf(ms, 0L) / 60_000
        return when {
            minutes >= 1_440 -> "${minutes / 1_440}d ${minutes % 1_440 / 60}h"
            minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
            else -> "${minutes}m"
        }
    }

    fun signed(n: Long): String = (if (n > 0) "+" else "") + money(n)
}
