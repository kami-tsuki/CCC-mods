package kami.libs.discord

import net.dv8tion.jda.api.utils.MarkdownSanitizer
import java.util.Locale

object DiscordFormat {
    const val GOOD = 0x57F287
    const val BAD = 0xED4245
    const val NEUTRAL = 0x5865F2
    const val GOLD = 0xF1C40F

    private const val BLOCKS = "▁▂▃▄▅▆▇█"
    private const val SPARK_MAX = 24

    fun money(n: Number): String = String.format(Locale.ROOT, "%,d Spurs", n.toLong())

    private val spaces = Regex("\\s+")
    private val special = Regex("[\\[\\]()#>]")

    fun escape(text: String): String = MarkdownSanitizer.escape(text).replace(special, "\\\\$0")

    fun literal(text: String): String = "`${text.replace('`', '\'').replace(spaces, " ").trim().take(100)}`"

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

    internal fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    internal fun signed(n: Long): String = (if (n > 0) "+" else "") + money(n)
}
