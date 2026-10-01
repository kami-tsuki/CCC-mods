package kami.libs.ui.anim

class Countdown(val totalMs: Long, private val remainingAtMs: Long, private val at: Long, val running: Boolean) {
    fun remaining(now: Long): Long {
        val left = if (running) remainingAtMs - (now - at) else remainingAtMs
        return left.coerceIn(0L, totalMs.coerceAtLeast(0L))
    }

    fun fraction(now: Long): Float = if (totalMs <= 0L) 1f else 1f - remaining(now).toFloat() / totalMs
}
