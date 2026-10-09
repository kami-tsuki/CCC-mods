package kami.claims

import kami.claims.research.MinAge
import kami.claims.research.Not
import kami.claims.research.NodeDone
import kami.claims.research.AllOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConditionsTest {
    @Test
    fun notKeepsSoftRefsButNoHardRefs() {
        val c = Not(NodeDone("a"))
        assertEquals(listOf("a"), c.refs())
        assertTrue(c.hardRefs().isEmpty())
    }

    @Test
    fun qualifyingPrefixesOnlyUnqualifiedIds() {
        val c = AllOf(listOf(NodeDone("x"), NodeDone("u:x"))).qualified("t")
        assertEquals(listOf("t:x", "u:x"), c.refs())
    }

    @Test
    fun minAgeIsInclusiveAtExactlyTheRequiredDays() {
        Config.s = Settings(dayMillis = 86_400_000L)
        val day = Config.s.dayMillis
        assertTrue(MinAge(3).met(Country("Old", created = now() - 3 * day)))
        assertFalse(MinAge(3).met(Country("Young", created = now() - 3 * day + 60_000)))
    }
}
