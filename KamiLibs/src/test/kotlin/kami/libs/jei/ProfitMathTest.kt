package kami.libs.jei

import kami.libs.jei.impl.ProfitMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfitMathTest {
    @Test
    fun cheapestSkipsUnknown() {
        assertEquals(3.0, ProfitMath.cheapest(listOf(null, 5.0, 3.0, 0.0)))
        assertNull(ProfitMath.cheapest(listOf(null)))
    }

    @Test
    fun totalNeedsEveryPrice() {
        assertEquals(7.0, ProfitMath.total(listOf(3.0, 4.0)))
        assertNull(ProfitMath.total(listOf(3.0, null)))
        assertNull(ProfitMath.total(emptyList()))
    }

    @Test
    fun percentAndLabel() {
        assertEquals(46L, ProfitMath.percent(100.0, 146.0))
        assertEquals(-12L, ProfitMath.percent(100.0, 88.0))
        assertNull(ProfitMath.percent(0.0, 5.0))
        assertEquals("+46%", ProfitMath.label(46))
        assertEquals("\u221212%", ProfitMath.label(-12))
    }
}
