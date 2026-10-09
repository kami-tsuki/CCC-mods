package kami.claims

import kami.claims.war.WarDebt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpoilsMathTest {
    @Test
    fun tributeIsFlooredAndNeverNegative() {
        assertEquals(33L, tributeOwed(TaxMode.PERCENT, 0.1, 335))
        assertEquals(7L, tributeOwed(TaxMode.FLAT, 7.9, 1000))
        assertEquals(0L, tributeOwed(TaxMode.FLAT, -5.0, 1000))
    }

    @Test
    fun warDebtMultiplicationAndAdditionSaturate() {
        assertEquals(Long.MAX_VALUE, WarDebt.times(Long.MAX_VALUE / 2 + 1, 2))
        assertEquals(0L, WarDebt.times(5, 0))
        assertEquals(Long.MAX_VALUE, WarDebt.plus(Long.MAX_VALUE, 1))
        val debtor = Country("Debtor")
        assertEquals(0L, WarDebt.owe(debtor, "c", 0))
        assertTrue(debtor.warDebts.isEmpty())
    }
}
