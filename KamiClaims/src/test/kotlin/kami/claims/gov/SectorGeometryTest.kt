package kami.claims.gov

import kami.claims.Country
import kami.claims.war.ChunkRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SectorGeometryTest : GovTest() {
    private val dim = "minecraft:overworld"

    private fun ref(x: Int, z: Int) = ChunkRef(dim, x, z)

    private fun sector(c: Country, vararg cells: Pair<Int, Int>, manager: String? = null): Sector {
        val s = Sector("s1", "North", 0, cells.map { ref(it.first, it.second) }.toMutableSet(), manager)
        c.sectors[s.id] = s
        return s
    }

    @Test
    fun edgeNeighboursAreConnectedAndDiagonalsAreNot() {
        assertTrue(SectorGeometry.connected(listOf(ref(0, 0), ref(1, 0), ref(1, 1))))
        assertFalse(SectorGeometry.connected(listOf(ref(0, 0), ref(1, 1))))
        assertFalse(SectorGeometry.connected(listOf(ref(0, 0), ref(2, 0))))
        assertTrue(SectorGeometry.connected(emptyList()))
    }

    @Test
    fun chunksOfAnotherDimensionNeverConnect() {
        assertFalse(SectorGeometry.connected(listOf(ref(0, 0), ChunkRef("minecraft:the_nether", 1, 0))))
    }

    @Test
    fun sizeLimitsAreTwoToSixteen() {
        assertEquals(2, SectorRules.MIN_CHUNKS)
        assertEquals(16, SectorRules.MAX_CHUNKS)
        assertEquals(setOf("mining", "farming", "forestry", "factory"), SectorRules.workTypes)
    }

    @Test
    fun losingAMiddleChunkMarksTheSectorFragmentedAndUnusable() {
        val c = standard()
        val s = sector(c, 0 to 0, 1 to 0, 2 to 0)
        assertTrue(Sectors.usable(s))
        Sectors.chunkLeft(c, ref(1, 0))
        assertEquals(setOf(ref(0, 0), ref(2, 0)), s.chunks)
        assertTrue(s.fragmented)
        assertFalse(Sectors.usable(s))
    }

    @Test
    fun fragmentedSectorHealsWhenTheGapIsNotTheIssueAnymore() {
        val c = standard()
        val s = sector(c, 0 to 0, 1 to 0, 2 to 0, 3 to 0)
        Sectors.chunkLeft(c, ref(1, 0))
        assertTrue(s.fragmented)
        Sectors.chunkLeft(c, ref(0, 0))
        assertEquals(setOf(ref(2, 0), ref(3, 0)), s.chunks)
        assertFalse(s.fragmented)
    }

    @Test
    fun droppingBelowTwoChunksDissolvesTheSectorAndDeactivatesItsJobs() {
        val c = standard()
        val s = sector(c, 0 to 0, 1 to 0)
        c.jobDefs["scoped"] = JobSpec("scoped", "Scoped", scope = JobScope.SECTOR, scopeRef = s.id)
        c.jobDefs["typed"] = JobSpec("typed", "Typed", scope = JobScope.TYPE, scopeRef = "mining")
        Sectors.chunkLeft(c, ref(0, 0))
        assertTrue(c.sectors.isEmpty())
        assertFalse(c.jobDefs.getValue("scoped").active)
        assertTrue(c.jobDefs.getValue("typed").active)
    }

    @Test
    fun leavingChunkLeavesOtherSectorsUntouched() {
        val c = standard()
        val s = sector(c, 0 to 0, 1 to 0, 2 to 0)
        val other = Sector("s2", "South", 0, mutableSetOf(ref(0, 5), ref(1, 5)))
        c.sectors[other.id] = other
        Sectors.chunkLeft(c, ref(2, 0))
        assertFalse(s.fragmented)
        assertEquals(2, other.chunks.size)
        assertTrue(c.sectors.keys == setOf("s1", "s2"))
    }
}
