package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.Job
import kami.claims.Leave
import kami.claims.service.Work
import kami.claims.today
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkPayTest : GovTest() {
    private val paid = mutableListOf<Pair<String, Int>>()
    private lateinit var c: Country
    private val day get() = today() + 10
    private val originalWage = Work.wage

    @BeforeTest
    fun setup() {
        Work.reset()
        paid.clear()
        Work.wage = { id, n, _ -> paid.add(id to n) }
        c = country(mapOf("a" to CITIZEN, "b" to CITIZEN), name = "Pay")
        c.jobDefs.clear()
        c.jobDefs["miner"] = JobSpec("miner", "Miner", pay = 100, quota = 10, period = 1)
        listOf("a", "b").forEach { Work.give(c, it, "miner"); c.members.getValue(it).jobs.getValue("miner").start = 0 }
    }

    @AfterTest
    fun restore() {
        Work.wage = originalWage
        Work.reset()
    }

    private fun job(id: String) = c.members.getValue(id).jobs.getValue("miner")

    @Test
    fun quotaLabourSharesOneRationByTheGroupsProgress() {
        c.government.levers = Presets.levers(PresetId.COMMUNISM)
        c.treasury = 10_000
        job("a").progress = 10
        job("b").progress = 0
        Work.pay(c, day, 1_000_000)
        assertEquals(listOf("a" to 50, "b" to 50), paid)
        assertEquals(9_900, c.treasury)
        assertEquals(0, job("a").progress)
    }

    @Test
    fun quotaLabourPaysNothingWhenNobodyDelivered() {
        c.government.levers = Presets.levers(PresetId.COMMUNISM)
        c.treasury = 10_000
        Work.pay(c, day, 1_000_000)
        assertTrue(paid.isEmpty())
        assertEquals(10_000, c.treasury)
        assertEquals(day, job("a").start)
    }

    private fun paysAt(preset: PresetId, progress: Int): List<Pair<String, Int>> {
        paid.clear()
        c.government = Government(preset, Presets.levers(preset))
        c.treasury = 10_000
        job("a").progress = progress
        job("a").start = 0
        Work.pay(c, day, 1_000_000)
        return paid.toList()
    }

    @Test
    fun halfQuotaEarnsTheFloorShareAndLessEarnsNothing() {
        assertEquals(listOf("a" to 50), paysAt(PresetId.SOCIALISM, 5))
        assertEquals(listOf("a" to 25), paysAt(PresetId.MIXED, 5))
        assertEquals(emptyList(), paysAt(PresetId.MIXED, 4))
    }

    @Test
    fun budgetStampedForALaterDayStillCapsEarlierCatchUpDays() {
        c.government = Government(PresetId.MIXED, Presets.levers(PresetId.MIXED))
        c.treasury = 10_000
        c.payBudget.day = day + 1
        c.payBudget.spent = Long.MAX_VALUE / 2
        job("a").progress = 10
        Work.pay(c, day, 1_000)
        assertTrue(paid.isEmpty())
    }

    @Test
    fun wageLabourWithheldWhenTreasuryIsShortKeepsProgress() {
        c.government.levers = Presets.levers(PresetId.MIXED)
        c.treasury = 50
        job("a").progress = 10
        Work.pay(c, day, 1_000_000)
        assertTrue(paid.isEmpty())
        assertEquals(50, c.treasury)
        assertEquals(10, job("a").progress)
        assertEquals(0, job("a").start)
        c.treasury = 1_000
        Work.pay(c, day, 1_000_000)
        assertEquals(listOf("a" to 100), paid)
        assertEquals(900, c.treasury)
        assertEquals(0, job("a").progress)
    }

    @Test
    fun wageLabourBelowHalfTheQuotaPaysNothingAndRestartsThePeriod() {
        c.treasury = 1_000
        job("a").progress = 4
        Work.pay(c, day, 1_000_000)
        assertTrue(paid.isEmpty())
        assertEquals(1_000, c.treasury)
        assertEquals(day, job("a").start)
    }

    @Test
    fun wageFloorPaysAShareOnceHalfTheQuotaIsDone() {
        c.treasury = 1_000
        job("a").progress = 5
        job("b").progress = 10
        Work.pay(c, day, 1_000_000)
        assertEquals(listOf("a" to 25, "b" to 100), paid)
        c.government.preset = PresetId.SOCIALISM
        job("a").progress = 6
        job("a").start = 0
        paid.clear()
        Work.pay(c, day + 1, 1_000_000)
        assertEquals(listOf("a" to 50), paid)
    }

    @Test
    fun noWageFloorOutsideWageLabourOrWithoutAFloorPreset() {
        c.treasury = 1_000
        c.government.preset = PresetId.CAPITALISM
        job("a").progress = 9
        Work.pay(c, day, 1_000_000)
        assertTrue(paid.isEmpty())
    }

    @Test
    fun jobShareOfDailyIncomeCapsWhatMayBePaid() {
        c.treasury = 10_000
        job("a").progress = 10
        Work.pay(c, day, 100)
        assertTrue(paid.isEmpty())
        assertEquals(10, job("a").progress)
    }

    @Test
    fun failedPayoutIsRefundedAndProgressKept() {
        c.treasury = 1_000
        job("a").progress = 10
        Work.wage = { _, _, _ -> false }
        Work.pay(c, day, 1_000_000)
        assertEquals(1_000, c.treasury)
        assertEquals(10, job("a").progress)
    }

    @Test
    fun acceptedContractOverridesTheJobTermsOnlyForItsHolder() {
        c.contracts += Contract("k1", "miner", "a", "boss", pay = 40, quota = 4, period = 2, until = day + 5, accepted = true)
        val spec = c.jobDefs.getValue("miner")
        assertEquals(40, Work.terms(c, "a", spec).pay)
        assertEquals(100, Work.terms(c, "b", spec).pay)
        assertEquals(20, Work.earned(Work.terms(c, "a", spec), 2))
        assertEquals(0, Work.earned(Work.terms(c, "a", JobSpec("none", "None")), 5))
    }

    @Test
    fun ownQuitForfeitsEarnings() {
        c.government.levers = Presets.levers(PresetId.COMMUNISM)
        c.treasury = 10_000
        job("a").progress = 10
        Work.release(c, "a", "miner", paid = false)
        assertTrue(paid.isEmpty())
        assertEquals(10_000, c.treasury)
        assertFalse("miner" in c.members.getValue("a").jobs)
    }

    @Test
    fun releasePaysEarnedProgressProRataEvenMidPeriod() {
        c.government.levers = Presets.levers(PresetId.COMMUNISM)
        c.treasury = 10_000
        job("a").progress = 5
        job("a").start = today()
        Work.release(c, "a", "miner")
        assertEquals(listOf("a" to 50), paid)
    }

    @Test
    fun exitPayoutsShareOneDailyBudget() {
        c.government.levers = Presets.levers(PresetId.COMMUNISM)
        c.treasury = 6_000
        job("a").progress = 10
        job("b").progress = 10
        Work.release(c, "a", "miner")
        Work.release(c, "b", "miner")
        assertEquals(listOf("a" to 100), paid)
    }

    @Test
    fun kickPaysEarnedProgressButLeavingForfeitsIt() {
        c.government.levers = Presets.levers(PresetId.COMMUNISM)
        c.treasury = 10_000
        job("a").progress = 10
        job("b").progress = 10
        Work.forget(c, "a", Leave.KICK)
        Work.forget(c, "b", Leave.LEAVE)
        assertEquals(listOf("a" to 100), paid)
    }

    @Test
    fun editingTheSpecKeepsTheTermsOfExistingHolders() {
        job("a").progress = 5
        c.jobDefs.getValue("miner").run { pay = 400; quota = 100; period = 9 }
        val terms = Work.terms(c, "a", c.jobDefs.getValue("miner"))
        assertEquals(Triple(100, 10, 1), Triple(terms.pay, terms.quota, terms.period))
        assertEquals(50, Work.earned(terms, job("a").progress))
    }

    @Test
    fun forecastCountsHoldersAtTheirOwnTerms() {
        c.jobDefs.getValue("miner").pay = 400
        assertEquals(200, Work.forecast(c, c.jobDefs.getValue("miner")))
        assertEquals(600, Work.forecast(c, c.jobDefs.getValue("miner"), hiring = 1))
    }

    @Test
    fun releaseForfeitsWhenTheDailyBudgetCannotCoverIt() {
        c.government.levers = Presets.levers(PresetId.MIXED)
        c.treasury = 10_000
        job("a").progress = 10
        Work.release(c, "a", "miner")
        assertTrue(paid.isEmpty())
    }

    @Test
    fun leavingStartsACooldownUntilThePeriodPasses() {
        val spec = c.jobDefs.getValue("miner")
        assertFalse(Work.coolingDown(c, "a", spec))
        Work.release(c, "a", "miner", paid = false)
        assertTrue(Work.coolingDown(c, "a", spec))
    }

    @Test
    fun forgetKeepsAcceptedContractsOfALeavingOfferer() {
        c.contracts += Contract("kept", "miner", "b", "a", 40, 4, 1, until = day + 5, accepted = true)
        c.contracts += Contract("dropped", "miner", "b", "a", 40, 4, 1, until = day + 5, accepted = false)
        Work.forget(c, "a", Leave.LEAVE)
        assertEquals(listOf("kept"), c.contracts.map { it.id })
    }

    @Test
    fun expiredContractPaysItsWorkerProRata() {
        c.government.levers = Presets.levers(PresetId.COMMUNISM)
        c.treasury = 10_000
        c.contracts += Contract("old", "miner", "a", "boss", 40, 4, 1, until = day, accepted = true)
        job("a").progress = 2
        Work.pay(c, day, 1_000_000)
        assertEquals(listOf("a" to 20), paid)
    }

    @Test
    fun dutyFinesSkipTheFirstPeriodAndFreshlyEditedJobs() {
        val spec = JobSpec("j", "J")
        assertTrue(Work.inGrace(c, Job(start = 5, took = 5), spec))
        assertFalse(Work.inGrace(c, Job(start = 7, took = 5), spec))
        spec.changedDay = 7
        assertTrue(Work.inGrace(c, Job(start = 7, took = 5), spec))
        assertFalse(Work.inGrace(c, Job(start = 8, took = 5), spec))
    }

    @Test
    fun governmentChangeGivesDutyJobsAGracePeriod() {
        val spec = JobSpec("j", "J")
        val job = Job(start = 7, took = 5)
        c.government.changedAt = 6 * Config.s.dayMillis
        assertFalse(Work.inGrace(c, job, spec))
        c.government.changedAt = 7 * Config.s.dayMillis
        assertTrue(Work.inGrace(c, job, spec))
    }

    @Test
    fun zeroPaySnapshotStaysZeroWhenTheSpecPayRises() {
        c.jobDefs.getValue("miner").pay = 0
        c.members.getValue("a").jobs.clear()
        Work.give(c, "a", "miner")
        c.jobDefs.getValue("miner").pay = 500
        assertEquals(0, Work.terms(c, "a", c.jobDefs.getValue("miner")).pay)
    }

    @Test
    fun exitPayoutsRefuseWhenTheDailyBudgetCannotCoverThem() {
        c.treasury = 10_000
        job("a").progress = 10
        assertFalse(Work.affordable(c, listOf("a" to "miner"), day, 100))
        assertTrue(Work.affordable(c, listOf("a" to "miner"), day, 1_000_000))
        assertTrue(Work.affordable(c, listOf("b" to "miner"), day, 100))
    }

    @Test
    fun expiredContractStaysUntilItsPayoutCanBeSettled() {
        c.treasury = 10_000
        c.contracts += Contract("old", "miner", "a", "boss", 40, 4, 1, until = day, accepted = true)
        job("a").progress = 4
        Work.pay(c, day, 10)
        assertEquals(listOf("old"), c.contracts.map { it.id })
        Work.pay(c, day + 7, 10)
        assertTrue(c.contracts.isEmpty())
    }

    @Test
    fun expiredContractIsRemovedAndReleasesItsAcceptedWorker() {
        c.contracts += Contract("old", "miner", "a", "boss", 40, 4, 1, until = day, accepted = true)
        c.contracts += Contract("offer", "miner", "b", "boss", 40, 4, 1, until = day, accepted = false)
        c.contracts += Contract("live", "miner", "b", "boss", 40, 4, 1, until = day + 5, accepted = false)
        Work.pay(c, day, 1_000_000)
        assertEquals(listOf("live"), c.contracts.map { it.id })
        assertFalse("miner" in c.members.getValue("a").jobs)
        assertTrue("miner" in c.members.getValue("b").jobs)
    }
}
