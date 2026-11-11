package kami.claims.press

import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PressScheduleTest {
    private fun at(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, day, h, m).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun parsesDailyAndWeeklySlots() {
        assertNotNull(PressSchedule.parse("12:00"))
        assertEquals(1, PressSchedule.parse("tue 06:30")?.day)
        assertEquals(390, PressSchedule.parse("Tue 06:30")?.minute)
        assertNull(PressSchedule.parse("24:00"))
        assertNull(PressSchedule.parse("noon"))
        assertNull(PressSchedule.parse("Mon Tue 1:00"))
        assertNull(PressSchedule.parse("12"))
    }

    @Test
    fun dailySlotIsDueOncePerOccurrence() {
        val times = listOf("12:00", "20:00")
        assertFalse(PressSchedule.due(times, at(11, 12, 0), at(11, 12, 30)))
        assertTrue(PressSchedule.due(times, at(11, 11, 59), at(11, 12, 0)))
        assertFalse(PressSchedule.due(times, at(11, 12, 5), at(11, 19, 59)))
        assertTrue(PressSchedule.due(times, at(11, 12, 5), at(11, 20, 1)))
        assertFalse(PressSchedule.due(times, at(10, 20, 5), at(11, 0, 1)))
        assertTrue(PressSchedule.due(times, at(10, 20, 5), at(11, 12, 1)))
    }

    @Test
    fun weeklySlotOnlyFiresOnItsWeekday() {
        val monday = at(9, 12, 0)
        assertEquals(java.time.DayOfWeek.MONDAY, java.time.Instant.ofEpochMilli(monday).atZone(ZoneOffset.UTC).dayOfWeek)
        assertTrue(PressSchedule.due(listOf("Mon 12:00"), at(8, 13, 0), monday + 1))
        assertFalse(PressSchedule.due(listOf("Mon 12:00"), monday + 1, at(14, 11, 0)))
        assertTrue(PressSchedule.due(listOf("Mon 12:00"), monday + 1, at(16, 12, 1)))
    }

    @Test
    fun noValidTimesIsNeverDue() {
        assertFalse(PressSchedule.due(listOf("bad"), 0, at(11, 12, 0)))
        assertFalse(PressSchedule.due(emptyList(), 0, at(11, 12, 0)))
    }
}
