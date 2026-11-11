package kami.claims.gov

import kami.claims.Claim
import kami.claims.Country
import kami.claims.Member
import kami.claims.Realm
import kami.claims.research.Research
import kami.claims.research.ResearchDefs
import kami.claims.research.ResearchSettings
import kami.claims.research.LevelsConfig
import kami.claims.war.ChunkRef
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SectorsSaveTest : GovTest() {
    private val dim = "minecraft:overworld"
    private lateinit var c: Country

    @BeforeTest
    fun setup() {
        Research.defs = ResearchDefs(ResearchSettings(), LevelsConfig(), emptyMap(), emptyMap())
        c = country(mapOf("pres" to PRESIDENT, "cit" to CITIZEN))
        c.level = 12
        (0..17).forEach { Realm.add(Claim(c.id, dim, it, 0, "mining")) }
        Realm.add(Claim(c.id, dim, 0, 5, "residential"))
    }

    @AfterTest
    fun restore() {
        Research.defs = ResearchDefs.EMPTY
    }

    private fun refs(vararg cells: Pair<Int, Int>) = cells.joinToString(",") { ChunkRef(dim, it.first, it.second).wire() }

    private fun row(count: Int) = refs(*(0 until count).map { it to 0 }.toTypedArray())

    private fun save(text: String, name: String = "North", manager: String = "", existing: Sector? = null) = Sectors.save(c, existing, name, 0, text, manager)

    @Test
    fun sizeBelowTheMinimumOrAboveTheMaximumIsRejected() {
        assertTrue(failure { save(row(1)) }.contains("sector_size"))
        assertTrue(failure { save(row(SectorRules.MAX_CHUNKS + 1)) }.contains("sector_size"))
        assertEquals(SectorRules.MAX_CHUNKS, save(row(SectorRules.MAX_CHUNKS)).chunks.size)
    }

    @Test
    fun nonWorkTypeChunksAreRejected() {
        assertTrue(failure { save(refs(0 to 0, 0 to 5)) }.contains("sector_type"))
    }

    @Test
    fun disconnectedChunksAreRejected() {
        assertTrue(failure { save(refs(0 to 0, 2 to 0)) }.contains("sector_connected"))
    }

    @Test
    fun managerMustBeAMember() {
        assertTrue(failure { save(row(2), manager = UUID.randomUUID().toString()) }.contains("not_member"))
        val member = UUID.randomUUID().toString()
        c.members[member] = Member(CITIZEN)
        assertEquals(member, save(row(2), manager = member).manager)
    }

    @Test
    fun theSameChunkMayBelongToTwoSectors() {
        val first = save(refs(0 to 0, 1 to 0))
        val second = save(refs(1 to 0, 2 to 0), name = "South")
        assertEquals(setOf(first.id, second.id), c.sectors.keys)
        assertTrue(ChunkRef(dim, 1, 0) in first.chunks && ChunkRef(dim, 1, 0) in second.chunks)
    }

    @Test
    fun sectorCountIsLimitedByCapacity() {
        save(row(2), name = "Alpha")
        save(row(2), name = "Bravo")
        assertTrue(failure { save(row(2), name = "Gamma") }.contains("sector_limit"))
    }
}
