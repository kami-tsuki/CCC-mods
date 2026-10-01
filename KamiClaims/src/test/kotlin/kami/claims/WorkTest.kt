package kami.claims

import kami.claims.service.Work
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkTest {
    private val dim = "minecraft:overworld"
    private lateinit var c: Country

    @BeforeTest
    fun setup() {
        val data = Data()
        c = Country("Test", members = mutableMapOf("p" to Member(Rank.CITIZEN), "boss" to Member(Rank.OFFICER)))
        data.countries[c.id] = c
        listOf("mining", "mining", "factory").forEachIndexed { i, t -> data.claims += Claim(c.id, dim, i, 0, t) }
        Realm.reset(data)
    }

    @AfterTest
    fun clean() = Realm.reset(Data())

    private fun claim(x: Int) = Realm.at(dim, x, 0)!!

    @Test
    fun accessNeedsAnAssignment() {
        Work.give(c, "p", "miner")
        assertFalse(Work.fits(c, claim(0), "p"))
        claim(0).workers += "p"
        assertTrue(Work.fits(c, claim(0), "p"))
        assertFalse(Work.fits(c, claim(1), "p"))
        assertEquals(1, Work.matching(c, "p", claim(0)).size)
    }

    @Test
    fun noCrossTypeAccess() {
        Work.give(c, "p", "miner")
        claim(2).workers += "p"
        assertFalse(Work.fits(c, claim(2), "p"))
        assertTrue(Work.matching(c, "p", claim(2)).isEmpty())
    }

    @Test
    fun cleanupOnJobRemovalKickAndRetype() {
        Work.give(c, "p", "miner")
        Work.give(c, "p", "worker")
        listOf(0, 1, 2).forEach { claim(it).workers += "p" }
        Work.take(c, "p", "miner")
        assertEquals(setOf("p"), claim(2).workers)
        assertTrue(claim(0).workers.isEmpty() && claim(1).workers.isEmpty())
        Work.forget(c, "p")
        assertTrue(claim(2).workers.isEmpty())
        claim(1).workers += "p"
        Work.retyped(claim(1))
        assertTrue(claim(1).workers.isEmpty())
    }

    @Test
    fun twoJobsAddUpTheirWages() {
        Work.give(c, "p", "miner")
        Work.give(c, "p", "worker")
        assertEquals(9L, Work.payroll(c))
    }
}
