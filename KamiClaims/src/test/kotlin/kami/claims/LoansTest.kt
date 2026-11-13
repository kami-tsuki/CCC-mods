package kami.claims

import kami.claims.gov.PRESIDENT
import kami.claims.research.*
import kami.claims.service.Fail
import kami.claims.war.War
import kami.claims.war.WarKind
import kami.claims.war.WarPhase
import kami.claims.war.WarSide
import kami.libs.config.Configs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoansTest : WithDb() {
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
        c.mixed()
        Realm.join(c, "p1", PRESIDENT)
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

    @Test
    fun missedDaysBlockNewLoansAfterPayoff() {
        val c = country(0, "loan_small", "loan_medium")
        Loans.take(c, "small", "p1")
        c.treasury = 0
        Loans.collect(c, 1)
        Loans.collect(c, 2)
        assertEquals(2, c.loans.single().missed)
        today = 2
        c.treasury = 5_000
        Loans.repay(c, "small", "p1")
        assertEquals(4L, c.loanBlockDay)
        c.treasury = 0
        today = 3
        assertFailsWith<Fail> { Loans.take(c, "medium", "p1") }
        assertEquals(1, Loans.view(c).blockDays)
        today = 4
        Loans.take(c, "medium", "p1")
        assertEquals(1, c.loans.size)
    }

    @Test
    fun guardsDistinguishResearchFromCountryLoans() {
        val (l, b) = pair()
        val id = offer(l)
        borrow(b, id)
        Loans.requireNoResearchLoans(b, "k")
        assertFailsWith<Fail> { Loans.requireNoLoans(b, "k") }
        assertFailsWith<Fail> { Loans.requireNoLoans(l, "k") }
        Loans.repay(b.also { it.treasury = 2_000 }, id, "p2")
        Loans.requireNoLoans(b, "k")
        Loans.requireNoLoans(l, "k")
    }

    @Test
    fun creatingReservesCoinsAndUsesASlot() {
        val (l, _) = pair()
        offer(l)
        assertEquals(4_000L, l.treasury)
        assertEquals(LendState.ACTIVE, l.lendOffers.single().state)
        assertEquals(1, Loans.used(l))
        assertFailsWith<Fail> { offer(l) }
        assertTrue(l.ledger.any { it.kind == LedgerKind.LOAN_RESERVE })
    }

    @Test
    fun borrowingPaysTheLenderWithTariffInterest() {
        val (l, b) = pair()
        l.tradePolicy[b.id] = TradePolicy(tariffPct = 20)
        val id = offer(l, tariff = true)
        borrow(b, id, 1_120)
        assertEquals(listOf(1_000L, 1_120L, 112L), listOf(b.treasury, b.loans.single().total, b.loans.single().perDay))
        assertEquals(LendState.LENT, l.lendOffers.single().state)
        Loans.collect(b, 1)
        assertEquals(listOf(4_112L, 888L), listOf(l.treasury, b.treasury))
        assertEquals(1, Loans.view(l).lent.size)
    }

    @Test
    fun embargoAndReputationBlockBorrowing() {
        val (l, b) = pair()
        val id = offer(l, minRep = 2_000)
        b.reputation = 100.0
        assertEquals("kami_claims.loans.blocked.rep_min", Loans.view(b).market.single().blocked)
        assertFailsWith<Fail> { borrow(b, id) }
        b.reputation = 2_000.0
        assertEquals("", Loans.view(b).market.single().blocked)
        l.tradePolicy[b.id] = TradePolicy(embargo = true)
        assertEquals("kami_claims.loans.blocked.embargo", Loans.view(b).market.single().blocked)
        assertFailsWith<Fail> { borrow(b, id) }
        assertFailsWith<Fail> { borrow(l, id) }
    }

    @Test
    fun payoffRelistsOnlyWhenChosen() {
        val (l, b) = pair()
        val id = offer(l, relist = true)
        borrow(b, id)
        b.treasury = 1_500
        Loans.repay(b, id, "p2")
        assertEquals(listOf(LendState.ACTIVE, 4_100L), listOf(l.lendOffers.single().state, l.treasury))
        Loans.deleteOffer(l, id)
        val next = offer(l)
        borrow(b, next)
        b.treasury = 1_500
        Loans.repay(b, next, "p2")
        assertEquals(LendState.DISABLED, l.lendOffers.single().state)
        assertEquals(5_200L, l.treasury)
    }

    @Test
    fun lenderTakingALoanDisablesItsOffers() {
        val c = country(5_000, "loan_small")
        Loans.createOffer(c, 1_000, 10, 10, false, -1, -1, false, "p1")
        Loans.take(c, "small", "p1")
        assertEquals(LendState.DISABLED, c.lendOffers.single().state)
        assertEquals(6_000L, c.treasury)
        assertFailsWith<Fail> { Loans.toggleOffer(c, c.lendOffers.single().id, true, "p1") }
    }

    @Test
    fun deletingRefundsAndLentOffersCannotBeToggled() {
        val (l, b) = pair()
        val id = offer(l)
        Loans.deleteOffer(l, id)
        assertEquals(5_000L, l.treasury)
        assertTrue(l.lendOffers.isEmpty())
        val lent = offer(l)
        borrow(b, lent)
        assertFailsWith<Fail> { Loans.toggleOffer(l, lent, false, "p1") }
    }

    @Test
    fun dissolvingForgivesLoansAndResetsOffers() {
        val (l, b) = pair()
        borrow(b, offer(l))
        Loans.dissolve(b)
        assertEquals(listOf(LendState.DISABLED, null), listOf(l.lendOffers.single().state, l.lendOffers.single().borrower))
        Loans.toggleOffer(l, l.lendOffers.single().id, true, "p1")
        assertEquals(3_000L, l.treasury)
        Loans.dissolve(l)
        assertEquals(4_000L, l.treasury)
        assertTrue(l.lendOffers.isEmpty())
        val (l2, b2) = pair()
        borrow(b2, offer(l2))
        Loans.dissolve(l2)
        assertTrue(b2.loans.isEmpty())
    }

    @Test
    fun togglingToTheCurrentStateIsANoOp() {
        val (l, _) = pair()
        val id = offer(l)
        val before = l.ledger.size
        Loans.toggleOffer(l, id, true, "p1")
        assertEquals(before, l.ledger.size)
        Loans.toggleOffer(l, id, false, "p1")
        val released = l.ledger.size
        Loans.toggleOffer(l, id, false, "p1")
        assertEquals(released, l.ledger.size)
        assertEquals(5_000L, l.treasury)
    }

    @Test
    fun orphanedLenderIsForgivenInCollect() {
        val (_, b) = pair()
        b.loans += Loan("ghost", 1_000, 1_100, 0, 110, 0, lender = "gone")
        Loans.collect(b, 1)
        assertTrue(b.loans.isEmpty())
    }

    @Test
    fun warBlocksBorrowing() {
        val (l, b) = pair()
        val id = offer(l)
        Realm.data.wars += War("w1", WarKind.REGULAR, WarSide(l.id), WarSide(b.id), 0, phase = WarPhase.WAR)
        assertEquals("kami_claims.loans.blocked.war", Loans.view(b).market.single().blocked)
        assertFailsWith<Fail> { borrow(b, id) }
    }

    @Test
    fun lenderAtTreasuryCapReceivesFullRepayment() {
        val (l, b) = pair()
        borrow(b, offer(l))
        l.treasury = 10_000
        Loans.collect(b, 1)
        assertEquals(10_110L, l.treasury)
    }

    @Test
    fun deletingALentOfferForgivesTheLoan() {
        val (l, b) = pair()
        val id = offer(l)
        borrow(b, id)
        Loans.deleteOffer(l, id)
        assertTrue(b.loans.isEmpty())
        assertTrue(l.lendOffers.isEmpty())
        assertEquals(4_000L, l.treasury)
    }

    @Test
    fun changedTermsAreRejected() {
        val (l, b) = pair()
        val id = offer(l)
        assertFailsWith<Fail> { borrow(b, id, 1_000) }
        assertTrue(b.loans.isEmpty())
        borrow(b, id)
        assertEquals(1, b.loans.size)
    }

    @Test
    fun ownOffersAreCapped() {
        val (l, _) = pair()
        l.treasury = 1_000_000
        repeat(Limits.MAX_LINKS) { l.lendOffers += LendOffer("x$it", 100, 5, 5, false, -1, -1, false, LendState.DISABLED) }
        assertFailsWith<Fail> { offer(l) }
        assertEquals(Limits.MAX_LINKS, Loans.view(l).own.size)
    }

    private fun borrow(c: Country, id: String, total: Long = 1_100) = Loans.borrow(c, id, total, "p2")

    private fun pair(): Pair<Country, Country> {
        val l = country(5_000, "loan_slot_1")
        val b = Country("borrower")
        Realm.data.countries[b.id] = b
        b.research.done["t:loan_slot_1"] = 0
        return l to b
    }

    private fun offer(c: Country, tariff: Boolean = false, minRep: Int = -1, relist: Boolean = false): String {
        Loans.createOffer(c, 1_000, 10, 10, tariff, minRep, -1, relist, "p1")
        return c.lendOffers.last().id
    }

    @Test
    fun defaultDefersLevelMoneyRewardsAndBlocksClaims() {
        val c = country(0, "loan_small", "loan_slot_1")
        Loans.take(c, "small", "p1")
        Loans.collect(c, 0)
        Loans.collect(c, 1)
        c.treasury = 0
        Loans.collect(c, 2)
        assertTrue(Loans.inDefault(c))
        val levels = LevelsConfig(rules = emptyMap(), capacities = mapOf(Capacity.TREASURY to 10_000), rewards = mapOf(2 to listOf(MoneyReward(100))))
        Research.defs = Validator.build(ResearchSettings(baseline = emptyList()), levels, emptyMap(), emptyMap()).defs
        c.level = 2
        Levels.settleRewards(c)
        assertEquals(0L, c.treasury)
        assertEquals(1, c.rewardedLevel)
        assertTrue(kami.claims.service.Planner.blockReason(c, Key("minecraft:overworld", 0, 0), "mining", emptySet(), 0, 0) != null)
        c.treasury = 1_000
        Loans.collect(c, 3)
        assertFalse(Loans.inDefault(c))
        Levels.settleRewards(c)
        assertEquals(2, c.rewardedLevel)
        assertTrue(c.ledger.any { it.kind == LedgerKind.LEVEL_REWARD })
    }
}
