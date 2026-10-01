package kami.claims

import kami.claims.client.compat.HiddenDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HiddenDiffTest {
    @Test
    fun `new ids are hidden and dropped ids are unhidden`() {
        val diff = HiddenDiff.of(setOf("a", "b"), setOf("b", "c"))
        assertEquals(setOf("c"), diff.hide)
        assertEquals(setOf("a"), diff.unhide)
    }

    @Test
    fun `identical sets are empty`() {
        assertTrue(HiddenDiff.of(setOf("a"), setOf("a")).empty)
    }

    @Test
    fun `logout unhides everything`() {
        assertEquals(setOf("a", "b"), HiddenDiff.of(setOf("a", "b"), emptySet()).unhide)
    }
}
