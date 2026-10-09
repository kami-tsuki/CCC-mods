package kami.claims.client.hud

import kami.claims.net.HomeLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaxTimerTest {
    private fun home(rent: Int = 10, debt: Long = 0, credit: Long = 0, nextBill: Long = 0) =
        HomeLine("c", "overworld", 0, 0, "ok", rent, debt, 0, "house", credit, nextBill)

    private fun inputs(homes: List<HomeLine>, now: Long = 1000, treasury: Long = 0, net: Long = 0, country: Boolean = false) =
        TaxInputs(homes, 0, now, treasury, net, country)

    private val all = TaxConditions(true, true, 3, true, 12, true, 3)
    private val none = TaxConditions(false, false, 3, false, 12, false, 3)

    @Test
    fun sumsDebtAndPrepaid() {
        val r = TaxTimer.rows(inputs(listOf(home(debt = 5, credit = 20), home(debt = 7, credit = 40))))
        assertEquals(12, r.debt)
        assertEquals(60, r.prepaid)
        assertEquals(3, r.prepaidDays)
    }

    @Test
    fun rentDueIsRelativeToReceiveTimeAndClamped() {
        val homes = listOf(home(nextBill = 5000), home(nextBill = 3000), home(nextBill = 0))
        assertEquals(2000, TaxTimer.rows(inputs(homes, now = 1000)).rentDueIn)
        assertEquals(0, TaxTimer.rows(inputs(homes, now = 9000)).rentDueIn)
        assertNull(TaxTimer.rows(inputs(listOf(home()))).rentDueIn)
    }

    @Test
    fun claimsDaysNeedsNegativeNet() {
        assertNull(TaxTimer.rows(inputs(emptyList(), treasury = 100, net = 5, country = true)).claimsDays)
        assertEquals(4, TaxTimer.rows(inputs(emptyList(), treasury = 100, net = -25, country = true)).claimsDays)
        assertNull(TaxTimer.rows(inputs(emptyList(), treasury = 100, net = -25, country = false)).claimsDays)
    }

    @Test
    fun emptyHomesWithoutCountryHaveNoRows() {
        assertTrue(TaxTimer.rows(inputs(emptyList())).empty)
        assertFalse(TaxTimer.rows(inputs(emptyList(), country = true)).empty)
    }

    @Test
    fun zeroRentHasNoPrepaidDays() {
        val r = TaxTimer.rows(inputs(listOf(home(rent = 0, credit = 50))))
        assertNull(r.prepaidDays)
        assertFalse(TaxTimer.important(r, all))
    }

    @Test
    fun eachConditionTriggersAlone() {
        val debt = TaxTimer.rows(inputs(listOf(home(debt = 1, credit = 1000))))
        assertTrue(TaxTimer.important(debt, TaxConditions(true, false, 3, false, 12, false, 3)))
        assertFalse(TaxTimer.important(debt, TaxConditions(false, true, 3, true, 12, true, 3)))
        val mixed = TaxTimer.rows(inputs(listOf(home(credit = 0), home(credit = 1000))))
        assertTrue(TaxTimer.important(mixed, TaxConditions(false, true, 3, false, 12, false, 3)))
        val low = TaxTimer.rows(inputs(listOf(home(credit = 20))))
        assertTrue(TaxTimer.important(low, TaxConditions(false, true, 3, false, 12, false, 3)))
        assertFalse(TaxTimer.important(low, TaxConditions(false, true, 1, false, 12, false, 3)))
        val due = TaxTimer.rows(inputs(listOf(home(credit = 1000, nextBill = 3_600_000)), now = 0))
        assertTrue(TaxTimer.important(due, TaxConditions(false, false, 3, true, 12, false, 3)))
        assertFalse(TaxTimer.important(due, TaxConditions(false, false, 3, true, 0, false, 3)))
        val lapse = TaxTimer.rows(inputs(emptyList(), treasury = 60, net = -30, country = true))
        assertTrue(TaxTimer.important(lapse, TaxConditions(false, false, 3, false, 12, true, 3)))
        assertFalse(TaxTimer.important(lapse, TaxConditions(false, false, 3, false, 12, true, 1)))
    }

    @Test
    fun allConditionsOffNeverImportant() {
        val r = TaxTimer.rows(inputs(listOf(home(debt = 9, credit = 0, nextBill = 1)), treasury = 0, net = -1, country = true))
        assertTrue(TaxTimer.important(r, all))
        assertFalse(TaxTimer.important(r, none))
    }
}
