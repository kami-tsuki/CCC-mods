package kami.claims

import kami.claims.research.*
import kami.claims.service.Fail
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoansTest {
    private var today = 0L

    @AfterTest
    fun reset() {
        Research.defs = ResearchDefs.EMPTY
        Levels.day = { kami.claims.today() }
    }

    private fun country(treasury: Long, vararg done: String): Country {
        val built = Validator.build(ResearchSettings(), LevelsConfig(), Defaults.groups, Defaults.trees)
        assertEquals(emptyList(), built.problems.filter { it.contains("buffs") })
        Research.defs = built.defs
        Realm.reset(Data())
        Levels.day = { today }
        today = 0
        val c = Country("lender", treasury = treasury)
        Realm.data.countries[c.id] = c
        Realm.join(c, "p1", Rank.PRESIDENT)
        done.forEach { c.research.done["buffs:$it"] = 0 }
        return c
    }

    @Test
    fun takingChecksResearchSlotsAndTreasuryCap() {
        val c = country(9_500, "loan_small", "loan_slot_1", "loan_medium")
        assertFailsWith<Fail> { Loans.take(c, "large", "p1") }
        assertFailsWith<Fail> { Loans.take(c, "small", "p1") }
        c.treasury = 5_000
        Loans.take(c, "small", "p1")
        assertEquals(6_000L, c.treasury)
        assertEquals(listOf(186L), c.loans.map { it.perDay })
        assertEquals(1_300L, c.loans.single().total)
        assertFailsWith<Fail> { Loans.take(c, "small", "p1") }
        assertFailsWith<Fail> { Loans.take(c, "medium", "p1") }
        c.treasury = 0
        Loans.take(c, "medium", "p1")
        assertEquals(10_000L, c.treasury)
        assertEquals(2, Loans.slots(c))
        assertTrue(c.ledger.any { it.kind == LedgerKind.LOAN_IN })
    }

    @Test
    fun installmentsRollOverWithLateFeeAndDefaultBlocksNewLoans() {
        val c = country(0, "loan_small", "loan_slot_1", "loan_medium")
        Loans.take(c, "small", "p1")
        Loans.collect(c, 0)
        assertEquals(1_000L, c.treasury)
        Loans.collect(c, 1)
        assertEquals(814L, c.treasury)
        c.treasury = 100
        Loans.collect(c, 2)
        val loan = c.loans.single()
        assertEquals(0L, c.treasury)
        assertEquals(95L, loan.overdue)
        assertEquals(1_309L, loan.total)
        assertTrue(Loans.inDefault(c))
        assertFailsWith<Fail> { Loans.take(c, "medium", "p1") }
        c.treasury = 1_000
        Loans.collect(c, 3)
        assertFalse(Loans.inDefault(c))
        assertEquals(719L, c.treasury)
        assertTrue(c.ledger.any { it.kind == LedgerKind.LOAN_PAYMENT })
    }

    @Test
    fun withdrawalsAreBlockedWhileALoanIsActive() {
        val c = country(5_000, "loan_small")
        Loans.requireNoLoans(c)
        Loans.take(c, "small", "p1")
        assertFailsWith<Fail> { Loans.requireNoLoans(c) }
        Loans.repay(c, "small", "p1")
        Loans.requireNoLoans(c)
    }

    @Test
    fun earlyRepaymentPaysTheRestAndStartsTheCooldown() {
        val c = country(5_000, "loan_small")
        Loans.take(c, "small", "p1")
        Loans.collect(c, 1)
        c.treasury = 1_000
        assertFailsWith<Fail> { Loans.repay(c, "small", "p1") }
        c.treasury = 2_000
        Loans.repay(c, "small", "p1")
        assertEquals(2_000L - 1_114L, c.treasury)
        assertTrue(c.loans.isEmpty())
        today = 3
        assertFailsWith<Fail> { Loans.take(c, "small", "p1") }
        assertEquals(4, Loans.view(c).offers.single { it.id == "small" }.cooldownDays)
        today = 7
        Loans.take(c, "small", "p1")
        assertEquals(1, c.loans.size)
    }
}
