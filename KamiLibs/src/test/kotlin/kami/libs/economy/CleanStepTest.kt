package kami.libs.economy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CleanStepTest {
    @Test
    fun quantumPerRate() {
        assertEquals(10, CleanStep.q(10))
        assertEquals(25, CleanStep.q(8))
        assertEquals(20, CleanStep.q(5))
        assertEquals(20, CleanStep.q(15))
        assertEquals(1, CleanStep.q(0))
    }

    @Test
    fun tenPercent() {
        val rates = listOf(10)
        assertEquals(1, CleanStep.step(20, rates))
        assertEquals(2, CleanStep.step(15, rates))
        assertEquals(10, CleanStep.step(3, rates))
        assertTrue(CleanStep.isClean(30, rates))
        assertFalse(CleanStep.isClean(15, rates))
        assertEquals(3, CleanStep.charge(30, 10))
    }

    @Test
    fun eightPercent() {
        val rates = listOf(8)
        assertEquals(5, CleanStep.step(20, rates))
        assertEquals(1, CleanStep.step(50, rates))
        assertEquals(25, CleanStep.step(7, rates))
        assertTrue(CleanStep.isClean(100, rates))
        assertFalse(CleanStep.isClean(90, rates))
        assertEquals(8, CleanStep.charge(100, 8))
    }

    @Test
    fun taxPlusTariff() {
        val rates = listOf(10, 15)
        assertEquals(20, CleanStep.q(rates))
        assertEquals(1, CleanStep.step(20, rates))
        assertEquals(2, CleanStep.step(10, rates))
        assertEquals(4, CleanStep.step(15, rates))
        assertTrue(CleanStep.isClean(60, rates))
        assertFalse(CleanStep.isClean(30, rates))
        assertEquals(100, CleanStep.q(listOf(8, 10, 5)))
    }
}
