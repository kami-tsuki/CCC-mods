package kami.libs.economy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyTest {
    @Test
    fun totalsOutsideIntRangeAreRejected() {
        assertNull(Money.spurs(2 * 1_100_000_000L))
        assertNull(Money.spurs(0))
        assertNull(Money.spurs(-5))
        assertEquals(Int.MAX_VALUE, Money.spurs(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun negativeDebitAndCreditAreRejected() {
        assertFalse(Money.canDebit(100, -1))
        assertFalse(Money.canDebit(100, 0))
        assertFalse(Money.canDebit(100, 101))
        assertTrue(Money.canDebit(100, 100))
        assertFalse(Money.canCredit(100, -1))
        assertFalse(Money.canCredit(Int.MAX_VALUE - 5L, 6))
        assertTrue(Money.canCredit(Int.MAX_VALUE - 5L, 5))
    }
}
