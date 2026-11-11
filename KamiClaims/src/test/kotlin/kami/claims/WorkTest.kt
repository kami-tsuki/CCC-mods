package kami.claims

import kami.claims.gov.CITIZEN
import kami.claims.gov.PRESIDENT
import kami.claims.research.Capacity
import kami.claims.research.Levels
import kami.claims.service.Work
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkTest : WithDb() {
    private val dim = "minecraft:overworld"
    private lateinit var c: Country

    @BeforeTest
    fun setup() {
        val data = Data()
        c = Country("Test", members = mutableMapOf("p" to Member(CITIZEN), "boss" to Member(PRESIDENT)))
        c.mixed()
        data.countries[c.id] = c
        listOf("mining", "mining", "factory").forEachIndexed { i, t -> data.claims += Claim(c.id, dim, i, 0, t) }
        Realm.reset(data)
    }

    @AfterTest
    fun clean() {
        Work.wage = { _, _, _ -> true }
        Realm.reset(Data())
    }

    private fun claim(x: Int) = Realm.at(dim, x, 0)!!

    @Test
    fun jobCoversOnlyChunksOfItsType() {
        Work.give(c, "p", "miner")
        assertTrue(Work.holds(c, "p", claim(0)))
        assertTrue(Work.holds(c, "p", claim(1)))
        assertFalse(Work.holds(c, "p", claim(2)))
        assertTrue(Work.covers(c, claim(0), "p", Action.BREAK))
        assertFalse(Work.covers(c, claim(0), "p", Action.PLACE))
    }

    @Test
    fun noCrossTypeAccess() {
        Work.give(c, "p", "miner")
        claim(2).workers += "p"
        assertFalse(Work.covers(c, claim(2), "p", Action.BREAK))
    }

    @Test
    fun cleanupOnJobRemovalKickAndRetype() {
        Work.give(c, "p", "miner")
        Work.give(c, "p", "worker")
        listOf(0, 1, 2).forEach { claim(it).workers += "p" }
        Work.drop(c, "p", "miner")
        assertEquals(setOf("p"), claim(2).workers)
        assertTrue(claim(0).workers.isEmpty() && claim(1).workers.isEmpty())
        Work.forget(c, "p", Leave.KICK)
        assertTrue(claim(2).workers.isEmpty())
        claim(1).workers += "p"
        Work.retyped(c, claim(1))
        assertTrue(claim(1).workers.isEmpty())
    }

    @Test
    fun twoJobsAddUpTheirWages() {
        Work.give(c, "p", "miner")
        Work.give(c, "p", "worker")
        assertEquals(9L, Work.payroll(c))
    }

    @Test
    fun failedWageIsRefundedEvenAboveCapacity() {
        Work.give(c, "p", "miner")
        c.members.getValue("p").jobs.getValue("miner").progress = Int.MAX_VALUE
        val full = Levels.capacity(c, Capacity.TREASURY) + 100L
        c.treasury = full
        Work.wage = { _, _, _ -> false }
        Work.pay(c, today() + 1000, 1_000_000)
        assertEquals(full, c.treasury)
    }
}
