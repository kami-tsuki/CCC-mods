package kami.libs.ui.press

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditBufferTest {
    private val emoji = "😀"

    private fun prefix(text: String) = IntArray(text.length + 1) { it * 6 }

    private fun typed(b: EditBuffer, s: String) = s.forEach { b.insert(it.toString()) }

    @Test
    fun typingCoalescesUntilWhitespace() {
        val b = EditBuffer()
        typed(b, "ab cd")
        assertEquals("ab cd", b.text)
        assertTrue(b.undo())
        assertEquals("ab ", b.text)
        assertTrue(b.undo())
        assertEquals("", b.text)
        assertFalse(b.undo())
        assertTrue(b.redo())
        assertEquals("ab ", b.text)
    }

    @Test
    fun newEditClearsRedo() {
        val b = EditBuffer()
        typed(b, "abc")
        b.undo()
        b.insert("x")
        assertFalse(b.canRedo)
    }

    @Test
    fun limitAndSelectionReplace() {
        val b = EditBuffer("abcdefgh", 5)
        assertEquals("abcde", b.text)
        b.selectAll()
        b.insert("z")
        assertEquals("z", b.text)
        b.insert("xyxyxy")
        assertEquals("zxyxy", b.text)
    }

    @Test
    fun pasteNormalisesLineBreaksAndDropsControls() {
        val b = EditBuffer()
        b.insert("a\r\nb\tc\u0007d", paste = true)
        assertEquals("a\nbcd", b.text)
    }

    @Test
    fun deleteWordAndSelection() {
        val b = EditBuffer("hello big world")
        b.delete(false, true)
        assertEquals("hello big ", b.text)
        b.delete(false, true)
        assertEquals("hello ", b.text)
        b.selectWord(0)
        b.delete(false, false)
        assertEquals(" ", b.text)
    }

    @Test
    fun tokensAreAtomic() {
        val b = EditBuffer("a {price:minecraft:iron_ingot} b")
        b.move(5, false, true)
        assertEquals(30, b.cursor)
        b.move(5, false, false)
        assertEquals(2, b.cursor)
        b.move(30, false)
        b.delete(false, false)
        assertEquals("a  b", b.text)
        b.set("{flag:x}")
        b.selectWord(2)
        assertEquals("{flag:x}", b.selected())
    }

    @Test
    fun wrapToggles() {
        val b = EditBuffer("word")
        b.selectAll()
        assertTrue(b.toggleWrap("**", "**"))
        assertEquals("**word**", b.text)
        assertTrue(b.toggleWrap("**", "**"))
        assertEquals("word", b.text)
    }

    @Test
    fun linePrefixTogglesAndReplaces() {
        val b = EditBuffer("one\ntwo")
        b.selectAll()
        b.toggleLinePrefix("- ")
        assertEquals("- one\n- two", b.text)
        b.toggleLinePrefix("> ")
        assertEquals("> one\n> two", b.text)
        b.toggleLinePrefix("> ")
        assertEquals("one\ntwo", b.text)
    }

    @Test
    fun insertBlockStartsOnOwnLine() {
        val b = EditBuffer("abc")
        assertTrue(b.insertBlock("---"))
        assertEquals("abc\n---\n", b.text)
    }

    @Test
    fun wrapBreaksAtSpacesAndKeepsNewlines() {
        val text = "aaa bbb ccc\n\nddd"
        val segs = Wrap.lines(text, 42, prefix(text))
        assertEquals(listOf(0 to 8, 8 to 11, 12 to 12, 13 to 16), segs.map { it.start to it.end })
        assertEquals(listOf(true, false, false, false), segs.map { it.soft })
        assertEquals(1, Wrap.lineOf(segs, 8))
        assertEquals(1, Wrap.lineOf(segs, 11))
        assertEquals(3, Wrap.lineOf(segs, 16))
    }

    @Test
    fun wrapSplitsLongWordsAndKeepsAtomsWhole() {
        val long = "abcdefghij"
        assertEquals(listOf(0 to 4, 4 to 8, 8 to 10), Wrap.lines(long, 24, prefix(long)).map { it.start to it.end })
        val text = "ab {flag:x} cd"
        val atoms = Atoms.find(text)
        val segs = Wrap.lines(text, 36, prefix(text), atoms)
        assertEquals(3 to 11, segs[1].start to segs[1].end)
        assertTrue(segs.none { s -> atoms.any { it.start < s.end && s.end < it.end } })
    }

    @Test
    fun wrapKeepsSurrogatePairsWhole() {
        val text = emoji + emoji
        val tight = IntArray(text.length + 1) { (it + 1) / 2 * 10 }
        assertEquals(listOf(0 to 2, 2 to 4), Wrap.lines(text, 5, tight).map { it.start to it.end })
        assertEquals(listOf(0 to 2, 2 to 4), Wrap.lines(text, 10, tight).map { it.start to it.end })
    }

    @Test
    fun caretPositionsAndVerticalMovement() {
        val text = "aaa bbb ccc"
        val segs = Wrap.lines(text, 42, prefix(text))
        assertEquals(2, Wrap.posAt(prefix(text), segs[0], 13))
        assertEquals(7, Wrap.posAt(prefix(text), segs[0], 500))
        assertEquals(10, Wrap.vertical(segs, prefix(text), 2, 1, 14))
        assertEquals(11, Wrap.vertical(segs, prefix(text), 2, 5, 14))
        assertEquals(0, Wrap.vertical(segs, prefix(text), 2, -1, 14))
    }

    @Test
    fun stylerMarksTokens() {
        val runs = MarkupStyler.runs("{price:minecraft:iron_ingot}")
        assertTrue(runs.any { it.token })
    }

    @Test
    fun surrogatePairsStayWhole() {
        assertEquals("a", EditBuffer("a$emoji", 2).text)
        val b = EditBuffer("a${emoji}b", 10)
        b.move(1, false)
        b.step(1, false, false)
        assertEquals(3, b.cursor)
        b.step(-1, false, false)
        assertEquals(1, b.cursor)
        b.move(2, false, true)
        assertEquals(3, b.cursor)
        assertTrue(b.delete(false, false))
        assertEquals("ab", b.text)
        b.set(emoji + emoji)
        b.move(0, false)
        assertTrue(b.delete(true, false))
        assertEquals(emoji, b.text)
        val small = EditBuffer("", 3)
        assertTrue(small.insert("ab$emoji"))
        assertEquals("ab", small.text)
    }
}
