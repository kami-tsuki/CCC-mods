package kami.economy.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TicketMathTest {
    @Test
    fun convertScalesPositiveAmountsOnly() {
        assertEquals(20, TicketMath.convert(10, 1, 2))
        assertEquals(5, TicketMath.convert(10, 2, 1))
        assertEquals(0, TicketMath.convert(0, 3, 1))
        assertEquals(-1, TicketMath.convert(-1, 3, 1))
        assertEquals(Int.MAX_VALUE, TicketMath.convert(Int.MAX_VALUE, 1, 9))
    }

    @Test
    fun fatFingerNeedsMoreThanFifteenPercent() {
        assertFalse(TicketMath.fatFinger(115, 100))
        assertTrue(TicketMath.fatFinger(116, 100))
        assertTrue(TicketMath.fatFinger(80, 100))
        assertFalse(TicketMath.fatFinger(500, 0))
    }

    @Test
    fun pegAppliesOffsetAndLimit() {
        assertEquals(110L, TicketMath.peg(100.0, 10, 0, true, 1000))
        assertEquals(105L, TicketMath.peg(100.0, 10, 105, true, 1000))
        assertEquals(110L, TicketMath.peg(100.0, 10, 105, false, 1000))
        assertEquals(1L, TicketMath.peg(0.2, 0, 0, true, 1000))
        assertEquals(1000L, TicketMath.peg(5000.0, 0, 0, true, 1000))
    }

    @Test
    fun nudgeAndBeatStayPositive() {
        assertEquals(105L, TicketMath.nudge(100, true))
        assertEquals(95L, TicketMath.nudge(100, false))
        assertEquals(2L, TicketMath.nudge(1, true))
        assertEquals(1L, TicketMath.nudge(1, false))
        assertEquals(51L, TicketMath.beat(50, true))
        assertEquals(1L, TicketMath.beat(1, false))
    }
}
