package kami.libs.ui.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextStateTest {
    @Test
    fun setEmptyClearsTextAndCursor() {
        val s = TextState()
        s.insert("hello", 32) { true }
        s.scroll = 3
        s.set("")
        assertEquals("", s.text)
        assertEquals(0, s.cursor)
        assertEquals(0, s.anchor)
        assertEquals(0, s.scroll)
        assertFalse(s.hasSelection)
    }

    @Test
    fun insertThenDelete() {
        val s = TextState()
        assertTrue(s.insert("abc", 32) { true })
        assertEquals("abc", s.text)
        assertEquals(3, s.cursor)
        assertTrue(s.delete(forward = false, word = false))
        assertEquals("ab", s.text)
        assertFalse(s.delete(forward = true, word = false))
    }

    @Test
    fun insertRespectsLimitAndFilter() {
        val s = TextState("ab")
        assertFalse(s.insert("cde", 4) { true })
        assertTrue(s.insert("1x2", 8) { it.isDigit() })
        assertEquals("ab12", s.text)
    }

    @Test
    fun setThenTypeStartsFresh() {
        val s = TextState("old")
        s.set("")
        s.insert("new", 32) { true }
        assertEquals("new", s.text)
    }
}
