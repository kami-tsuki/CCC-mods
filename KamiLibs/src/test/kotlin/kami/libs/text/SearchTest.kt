package kami.libs.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchTest {
    private fun m(q: String, vararg f: String) = SearchQuery.parse(q).matches(*f)

    @Test
    fun words() {
        assertTrue(m("iron ore", "Deepslate Iron Ore"))
        assertTrue(m("iron", "Crushed Raw Iron"))
        assertFalse(m("-iron", "Raw Iron"))
        assertTrue(m("gold|iron", "Raw Iron"))
        assertTrue(m("\"raw iron\"", "Raw Iron"))
        assertFalse(m("\"raw iron\"", "Iron Raw"))
    }

    @Test
    fun tokens() {
        val q = SearchQuery.parse("-@x #c:ores/iron tacz:ammo#foo - # \$tip plain")
        val terms = q.clauses.map { it.single() }
        assertEquals(listOf('@', '#', null, null, '$'), terms.map { it.prefix })
        assertTrue(terms[0].negate)
        assertEquals("tacz:ammo#foo", terms[2].text)
        assertEquals('$', terms.last().prefix)
    }

    @Test
    fun numbers() {
        val t = SearchQuery.parse("-120 -5|x").clauses
        assertFalse(t[0].single().negate)
        assertEquals("-120", t[0].single().text)
        assertFalse(t[1][0].negate)
        assertTrue(t[1][1].let { !it.negate })
        assertTrue(m("-5", "at -5 here"))
    }
}
