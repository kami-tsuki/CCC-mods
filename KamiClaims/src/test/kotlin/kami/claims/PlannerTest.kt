package kami.claims

import kami.claims.service.Outcome
import kami.claims.service.Planner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerTest {
    private val dim = "minecraft:overworld"

    private fun country(owned: Int, treasury: Long): Country {
        Realm.reset(Data())
        val c = Country("planland", treasury = treasury)
        Realm.data.countries[c.id] = c
        Realm.join(c, "p1", Rank.PRESIDENT)
        (0 until owned).forEach { i -> Realm.add(Claim(c.id, dim, i, 0, "civic", at = i.toLong(), since = 0, capital = i == 0)) }
        Realm.refreshFree(c)
        return c
    }

    private fun row(from: Int, to: Int, z: Int = 0) = (from..to).map { Key(dim, it, z) }

    @Test
    fun freeChunksComeFirstThenPaidOnes() {
        val c = country(Config.s.freeChunks - 2, 100)
        val plan = Planner.claim(c, "civic", row(Config.s.freeChunks - 2, Config.s.freeChunks + 1))
        assertEquals(listOf(Outcome.FREE, Outcome.FREE, Outcome.CLAIM, Outcome.CLAIM), plan.cells.map { it.outcome })
        assertEquals(2L * Config.s.types.getValue("civic").price, plan.costNow)
    }

    @Test
    fun disconnectedCellsAreBlockedWithReason() {
        val c = country(2, 100)
        val plan = Planner.claim(c, "civic", listOf(Key(dim, 40, 40)))
        assertEquals(Outcome.BLOCKED, plan.cells.single().outcome)
        assertTrue(plan.cells.single().reason!!.contains("connected"))
    }

    @Test
    fun chainsConnectThroughPlannedCells() {
        val c = country(1, 1000)
        val plan = Planner.claim(c, "civic", listOf(Key(dim, 3, 0), Key(dim, 2, 0), Key(dim, 1, 0)))
        assertTrue(plan.cells.all { it.outcome == Outcome.FREE || it.outcome == Outcome.CLAIM })
    }

    @Test
    fun treasuryLimitsHowManyPaidChunksFit() {
        val c = country(Config.s.freeChunks, Config.s.types.getValue("civic").price.toLong())
        val plan = Planner.claim(c, "civic", row(Config.s.freeChunks, Config.s.freeChunks + 2))
        assertEquals(1, plan.ready.size)
        assertEquals(2, plan.blocked.size)
    }

    @Test
    fun unclaimKeepsCapitalAndConnectivity() {
        val c = country(5, 0)
        Realm.claims(c.id).forEach { it.upkeepCycles = 1 }
        val plan = Planner.unclaim(c, listOf(Key(dim, 0, 0), Key(dim, 2, 0)))
        val byX = plan.cells.associateBy { it.key.x }
        assertEquals(Outcome.BLOCKED, byX.getValue(0).outcome)
        assertEquals(Outcome.BLOCKED, byX.getValue(2).outcome)
        val edge = Planner.unclaim(c, listOf(Key(dim, 4, 0), Key(dim, 3, 0)))
        assertEquals(2, edge.ready.size)
    }

    @Test
    fun retypeShowsUpkeepDelta() {
        val c = country(Config.s.freeChunks + 1, 0)
        val paid = Realm.claims(c.id).first { !it.free }
        val plan = Planner.retype(c, "residential", listOf(paid.key))
        val expected = Config.s.types.getValue("residential").price - Config.s.types.getValue("civic").price
        assertEquals(expected.toDouble(), plan.upkeepPerDay)
    }
}
