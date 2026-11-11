package kami.claims.net

import kami.claims.gov.GovTest
import kami.claims.gov.PermCodec
import kami.claims.gov.Sector
import kami.claims.research.Tokens
import kami.claims.war.ChunkRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GovSyncTest : GovTest() {
    @Test
    fun rankLinesCountMembersAndRoundTripPerms() {
        val c = standard()
        val lines = GovSync.ranks(c).associateBy { it.id }
        assertEquals(1, lines.getValue("officer").members)
        assertEquals(1, lines.getValue("chancellor").members)
        val officer = c.ranks.first { it.id == "officer" }
        assertEquals(officer.perms, PermCodec.parse(lines.getValue("officer").perms.joinToString(",")))
        assertEquals(c.ranks.indices.toList(), GovSync.ranks(c).map { it.position })
    }

    @Test
    fun heirIsHiddenFromViewersWithoutAccess() {
        val c = standard()
        c.government.heir = "cit"
        assertEquals("", GovSync.government(c, "off", showHeir = false).heir)
        assertEquals("cit", GovSync.government(c, "off").heir)
    }

    @Test
    fun hasTokenNeedsASettingsToken() {
        val c = standard()
        assertFalse(GovSync.government(c).hasToken)
        c.tokens[Tokens.RENAME] = 1
        assertFalse(GovSync.government(c).hasToken)
        c.tokens[Tokens.SETTINGS] = 1
        assertTrue(GovSync.government(c).hasToken)
    }

    @Test
    fun sectorChunksRoundTripThroughParse() {
        val c = standard()
        val chunks = mutableSetOf(ChunkRef("minecraft:overworld", -3, 4), ChunkRef("mod:deep:dim", 0, -7))
        c.sectors["s1"] = Sector("s1", "North", 0, chunks)
        val line = GovSync.sectors(c).single()
        assertEquals(chunks, line.chunks.map { ChunkRef.parse(it) }.toSet())
        assertTrue(GovSync.sectors(c, withChunks = false).single().chunks.isEmpty())
    }
}
