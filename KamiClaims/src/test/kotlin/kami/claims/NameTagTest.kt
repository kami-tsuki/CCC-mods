package kami.claims

import kami.claims.net.RowKind
import kami.claims.net.TagRows
import kami.claims.net.TagView
import kami.claims.war.NameTagSync
import kami.libs.skills.SkillClass
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameTagTest {
    private fun tag(country: String? = "Rome", rank: String = "", cls: Int = -1, level: Int = 0, color: Int = 0xFF0000) =
        TagView(UUID.randomUUID(), country, color, rank, 0x5B9BD5, cls, level)

    @Test
    fun privacy() {
        assertFalse(NameTagSync.shown("a", "b", targetWarring = true, hide = true, veiled = false))
        assertTrue(NameTagSync.shown("a", "a", targetWarring = true, hide = true, veiled = false))
        assertTrue(NameTagSync.shown("a", "b", targetWarring = true, hide = false, veiled = false))
        assertTrue(NameTagSync.shown("a", "b", targetWarring = false, hide = true, veiled = false))
        assertFalse(NameTagSync.shown("a", "a", targetWarring = false, hide = false, veiled = true))
    }

    @Test
    fun rows() {
        assertEquals(listOf(RowKind.NOMAD), TagRows.rows(tag(country = null)).map { it.kind })
        assertEquals(listOf(RowKind.COUNTRY, RowKind.LEVEL), TagRows.rows(tag(level = 3)).map { it.kind })
        val r = TagRows.rows(tag(rank = "officer", cls = SkillClass.MINER.ordinal, level = 7))
        assertEquals(listOf(RowKind.RANK, RowKind.CLASS), r.map { it.kind })
        assertEquals(listOf("officer", "miner"), r.map { it.text })
        assertEquals(7, r[1].level)
        assertEquals(1, TagRows.rows(tag(cls = 99)).size)
    }

    @Test
    fun readable() {
        assertEquals(0xFF0000, TagRows.readable(0xFF0000))
        assertEquals(0x7F7F7F, TagRows.readable(0x000000))
        assertEquals(0xFF0000, TagRows.readable(0x80FF0000.toInt()))
    }
}
