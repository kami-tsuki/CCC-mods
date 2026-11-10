package kami.claims

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameRulesTest {
    @Test
    fun keyIgnoresCaseAccentsAndSeparators() {
        assertEquals("foobar", NameRules.key("Föo-Bar"))
        assertEquals(NameRules.key("foo_bar"), NameRules.key("FOOBAR"))
        assertTrue(NameRules.valid("Nord_Reich-2"))
        assertFalse(NameRules.valid("__-"))
        assertFalse(NameRules.valid("a b"))
    }
}
