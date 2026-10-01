package kami.claims

import kami.claims.research.*
import kami.claims.service.Fail
import kami.libs.config.Configs
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

    private val json = Configs.json()

    private fun node(id: String, unlock: String) = """{"id":"$id","category":"c","unlocks":[$unlock]}"""

    private fun loan(id: String, amount: Int, pct: Int, days: Int, slots: Int = 0) =
        node("loan_$id", """{"type":"loan","id":"$id","amount":$amount,"interestPct":$pct,"termDays":$days}""" + if (slots > 0) """,{"type":"loan_slots","add":$slots}""" else "")

    private fun country(treasury: Long, vararg done: String): Country {
        val nodes = listOf(loan("small", 1_000, 30, 7, slots = 1), loan("medium", 10_000, 10, 10), loan("large", 50_000, 10, 30), node("loan_slot_1", """{"type":"loan_slots","add":1}"""))
        val tree = json.decodeFromString(TreeFile.serializer(), """{"categories":[{"id":"c"}],"nodes":[${nodes.joinToString(",")}]}""")
        val levels = LevelsConfig(rules = emptyMap(), capacities = mapOf(Capacity.TREASURY to 10_000))
        Research.defs = Validator.build(ResearchSettings(baseline = emptyList()), levels, emptyMap(), mapOf("t" to tree)).defs
        Realm.reset(Data())
        Levels.day = { today }
        today = 0
        val c = Country("lender", treasury = treasury)
        Realm.data.countries[c.id] = c
        Realm.join(c, "p1", Rank.PRESIDENT)
        done.forEach { c.research.done["t:$it"] = 0 }
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

    @Test
    fun takeIsAllowedExactlyAtTheRoomLeft() {
        val c = country(9_001, "loan_small", "loan_slot_1")
        assertFailsWith<Fail> { Loans.take(c, "small", "p1") }
        c.treasury = 9_000
        Loans.take(c, "small", "p1")
        assertEquals(10_000L, c.treasury)
    }

    @Test
    fun insolventCollectChargesTheFeeOnTheMissedInstallmentOnly() {
        val c = country(0, "loan_small")
        c.loans += Loan("small", 1_000, 1_300, 0, 186, 0)
        Loans.collect(c, 1)
        val loan = c.loans.single()
        val fee = (186 * 10 + 99) / 100
        assertEquals(0L, loan.paid)
        assertEquals(1_300L + fee, loan.total)
        assertEquals(186L + fee, loan.overdue)
        assertTrue(Loans.inDefault(c))
        Loans.collect(c, 2)
        assertEquals(1_300L + 2 * fee, loan.total)
        assertEquals(186L + fee + 186 + fee, loan.overdue)
    }

    @Test
    fun provincesCannotTakeLoans() {
        val c = country(5_000, "loan_small")
        c.parent = "capital"
        assertFailsWith<Fail> { Loans.take(c, "small", "p1") }
        assertTrue(c.loans.isEmpty())
    }
}
