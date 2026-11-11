package kami.claims.press

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

object PressSchedule {
    private val days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    class Slot(val day: Int?, val minute: Int)

    fun parse(text: String): Slot? {
        val parts = text.trim().lowercase().split(Regex("\\s+"))
        if (parts.size > 2) return null
        val day = if (parts.size == 2) days.indexOf(parts[0]).takeIf { it >= 0 } ?: return null else null
        val clock = parts.last().split(':')
        val h = clock.getOrNull(0)?.toIntOrNull() ?: return null
        val m = clock.getOrNull(1)?.toIntOrNull() ?: return null
        return if (clock.size == 2 && h in 0..23 && m in 0..59) Slot(day, h * 60 + m) else null
    }

    private fun latest(slot: Slot, now: Long): Long {
        val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        return (0L..7L).map { today.minusDays(it) }.filter { slot.day == null || it.dayOfWeek.value - 1 == slot.day }
            .map { at(it, slot.minute) }.first { it <= now }
    }

    private fun at(date: LocalDate, minute: Int) = date.atTime(minute / 60, minute % 60).toInstant(ZoneOffset.UTC).toEpochMilli()

    fun due(times: List<String>, lastRun: Long, now: Long): Boolean =
        times.mapNotNull(::parse).maxOfOrNull { latest(it, now) }?.let { it > lastRun } == true
}
