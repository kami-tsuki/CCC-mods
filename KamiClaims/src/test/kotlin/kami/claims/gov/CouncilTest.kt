package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.WithdrawalRequest
import kami.claims.economy.Treasury
import kami.claims.now
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CouncilTest : GovTest() {
    private fun small(name: String, treasury: Long, levers: Levers) = country(mapOf("a" to PRESIDENT, "b" to CITIZEN), name = name).also {
        it.treasury = treasury
        it.government.levers = levers
    }

    private fun big(name: String, treasury: Long, levers: Levers) = country(mapOf("a" to PRESIDENT, "b" to CITIZEN, "c" to CITIZEN), name = name).also {
        it.treasury = treasury
        it.government.levers = levers
    }

    private fun withdrew(c: Country, amount: Long, at: Long = now()) {
        c.treasury -= amount
        c.withdrawn.add(at / Config.s.dayMillis, amount)
    }

    private val council = Presets.levers(PresetId.MIXED)
    private val ruler = Presets.levers(PresetId.CAPITALISM)

    @Test
    fun approvalIsNeededAboveTheLargerOfFloorAndTenthOfTreasury() {
        val rich = small("CouncilRich", 100_000, council)
        assertFalse(Council.requireApproval(rich, 10_000))
        assertTrue(Council.requireApproval(rich, 10_001))
        val poor = small("CouncilPoor", 5_000, council)
        assertFalse(Council.requireApproval(poor, 1_000))
        assertTrue(Council.requireApproval(poor, 1_001))
    }

    @Test
    fun rulerTreasuriesNeverNeedApproval() {
        assertFalse(Council.requireApproval(small("RulerRich", 100_000, ruler), 99_999))
        assertFalse(Council.requireApproval(small("RulerBudget", 100_000, Presets.levers(PresetId.COMMUNISM)), 99_999))
    }

    @Test
    fun gateRaisesARequestOnlyWhenApprovalIsRequired() {
        val c = small("GateCouncil", 100_000, council)
        assertNull(Council.gate(c, "a", 5_000))
        assertNotNull(Council.gate(c, "a", 50_000))
    }

    @Test
    fun smallCountriesMayTakeOnlyAFifthOfTheTreasuryPerDay() {
        val c = small("DailySmall", 10_000, ruler)
        assertTrue(failure { Council.gate(c, "a", 2_001) }.contains("withdraw_cap"))
        assertNull(Council.gate(c, "a", 2_000))
        withdrew(c, 1_000)
        assertTrue(failure { Council.gate(c, "a", 1_001) }.contains("withdraw_cap"))
        assertNull(Council.gate(c, "a", 1_000))
    }

    @Test
    fun onlyTodaysWithdrawalsCountTowardsTheAllowance() {
        val c = small("DailyDays", 10_000, ruler)
        withdrew(c, 1_000, at = now() - 2 * Config.s.dayMillis)
        assertNull(Council.gate(c, "a", 1_800))
    }

    @Test
    fun requestsAreStoredOnTheCountryAndClearedWithIt() {
        val c = small("StoredCouncil", 100_000, council)
        Council.gate(c, "a", 50_000)
        assertEquals(listOf("a"), c.withdrawals.map { it.requester })
        Council.clear(c)
        assertTrue(c.withdrawals.isEmpty())
    }

    @Test
    fun requestsExpireAfterADay() {
        val c = small("ExpiryCouncil", 100_000, council)
        c.withdrawals += WithdrawalRequest("old", "a", 5_000, now() - 25 * 60 * 60 * 1000L)
        c.withdrawals += WithdrawalRequest("new", "b", 5_000, now() - 23 * 60 * 60 * 1000L)
        assertEquals(listOf("new"), Council.pending(c).map { it.id })
    }

    @Test
    fun rejectedWithdrawalsDoNotUseUpTheDailyAllowance() {
        val c = small("DailyReject", 10_000, ruler)
        assertTrue(failure { Council.gate(c, "a", 9_000) }.contains("withdraw_cap"))
        assertNull(Council.gate(c, "a", 2_000))
    }

    @Test
    fun countriesWithThreeMembersHaveNoDailyCap() {
        val c = big("DailyBig", 10_000, ruler)
        assertNull(Council.gate(c, "a", 9_000))
        assertNull(Council.gate(c, "a", 900))
    }

    @Test
    fun dailyAllowanceIsTrackedPerCountry() {
        val first = small("DailyOne", 10_000, ruler)
        val second = small("DailyTwo", 10_000, ruler)
        assertNull(Council.gate(first, "a", 2_000))
        assertNull(Council.gate(second, "a", 2_000))
        assertEquals(10_000, second.treasury)
    }
}
