package kami.libs.admin

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

object Durations {
    private const val PERM = "perm"
    private const val MAX = 1_000_000L
    private val units = linkedMapOf('w' to 7.days, 'd' to 1.days, 'h' to 1.hours, 'm' to 1.minutes, 's' to 1.seconds)

    fun parse(raw: String): Duration? {
        val s = raw.trim().lowercase()
        if (s == PERM) return null
        val unit = s.lastOrNull()?.let { units[it] }
        val n = s.dropLast(1).toLongOrNull()
        if (unit == null || n == null || n !in 1..MAX) throw AdminFailure("kami_libs.admin.bad_duration", listOf(raw))
        return unit * n.toInt()
    }

    fun valid(text: String) = runCatching { parse(text) }.isSuccess
}
