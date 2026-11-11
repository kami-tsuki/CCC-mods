package kami.libs.ui.press

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditBufferMoreTest {
    private val emoji = "😀"

    private fun at(text: String, cursor: Int, limit: Int = 5000) = EditBuffer(text, limit).also { it.move(cursor, false) }

    private fun wellFormed(s: String): Boolean {
        for (i in s.indices) {
            val c = s[i]
            if (c.isHighSurrogate() && !(i + 1 < s.length && s[i + 1].isLowSurrogate())) return false
            if (c.isLowSurrogate() && !(i > 0 && s[i - 1].isHighSurrogate())) return false
        }
        return true
    }

    @Test
    fun insertAtCursorAndLimit() {
        val b = at("ac", 1)
        assertTrue(b.insert("b"))
        assertEquals("abc", b.text)
        assertEquals(2, b.cursor)
        val full = EditBuffer("abc", 3)
        val v = full.version
        assertFalse(full.insert("x"))
        assertEquals("abc", full.text)
        assertFalse(full.canUndo)
        assertEquals(v, full.version)
    }

    @Test
    fun pasteTruncatesToRoom() {
        val b = EditBuffer("ab", 5)
        assertTrue(b.insert("cdefgh", paste = true))
        assertEquals("abcde", b.text)
        assertEquals(5, b.cursor)
    }

    @Test
    fun selectionReplaceUsesFreedRoom() {
        val b = EditBuffer("abcde", 5)
        b.move(1, false)
        b.move(4, true)
        assertTrue(b.insert("WXYZ"))
        assertEquals("aWXYe", b.text)
        assertEquals(4, b.cursor)
        assertFalse(b.hasSelection)
    }

    @Test
    fun controlsAreFilteredAndLineBreaksNormalised() {
        val b = EditBuffer()
        assertFalse(b.insert("\t\u0001\u007f"))
        assertTrue(b.insert("a\u007fb"))
        assertEquals("ab", b.text)
        b.set("")
        b.insert("a\r\nb\rc\n", paste = true)
        assertEquals("a\nb\nc\n", b.text)
    }

    @Test
    fun emojiAtLimit() {
        val b = EditBuffer("", 2)
        assertTrue(b.insert(emoji + emoji))
        assertEquals(emoji, b.text)
        val one = EditBuffer("", 1)
        assertFalse(one.insert(emoji))
        assertEquals("", one.text)
        val c = EditBuffer(emoji + "a", 1)
        assertEquals("", c.text)
        assertEquals(0, c.cursor)
        b.set(emoji.repeat(5))
        assertEquals(emoji, b.text)
    }

    @Test
    fun setClearsHistoryAndMovesCursorToEnd() {
        val b = EditBuffer()
        b.insert("abc")
        b.set("xyz")
        assertFalse(b.canUndo)
        assertFalse(b.canRedo)
        assertEquals(3, b.cursor)
        assertFalse(b.hasSelection)
        b.set("x".repeat(6000))
        assertEquals(5000, b.text.length)
    }

    @Test
    fun deleteCharacterEdges() {
        val b = at("abc", 0)
        assertFalse(b.delete(false, false))
        assertFalse(b.delete(false, true))
        b.move(3, false)
        assertFalse(b.delete(true, false))
        assertFalse(b.delete(true, true))
        b.move(1, false)
        assertTrue(b.delete(true, false))
        assertEquals("ac", b.text)
        assertEquals(1, b.cursor)
        assertTrue(b.delete(false, false))
        assertEquals("c", b.text)
        assertEquals(0, b.cursor)
    }

    @Test
    fun deleteWordForwardAndBackward() {
        val f = at("hello big world", 0)
        f.delete(true, true)
        assertEquals(" big world", f.text)
        f.delete(true, true)
        assertEquals(" world", f.text)
        val b = EditBuffer("a b  ")
        b.delete(false, true)
        assertEquals("a ", b.text)
        assertEquals(2, b.cursor)
    }

    @Test
    fun deleteWithSelectionOnlyRemovesSelection() {
        val b = EditBuffer("hello world")
        b.move(0, false)
        b.move(5, true)
        assertTrue(b.delete(true, true))
        assertEquals(" world", b.text)
        assertEquals(0, b.cursor)
        assertFalse(b.hasSelection)
    }

    @Test
    fun deleteOverEmoji() {
        val b = EditBuffer("a$emoji")
        assertTrue(b.delete(false, false))
        assertEquals("a", b.text)
        val w = EditBuffer("x $emoji$emoji")
        w.delete(false, true)
        assertEquals("x ", w.text)
        val f = at(emoji + emoji + " x", 0)
        f.delete(true, true)
        assertEquals(" x", f.text)
        val g = at(emoji + "b", 0)
        g.delete(true, false)
        assertEquals("b", g.text)
    }

    @Test
    fun stepsWithSelection() {
        val b = at("hello", 1)
        b.move(3, true)
        assertEquals(1, b.selStart)
        assertEquals(3, b.selEnd)
        b.step(-1, false, false)
        assertEquals(1, b.cursor)
        assertFalse(b.hasSelection)
        b.move(3, true)
        b.step(1, false, false)
        assertEquals(3, b.cursor)
        assertFalse(b.hasSelection)
        b.move(1, false)
        b.move(3, true)
        b.step(-1, true, false)
        assertEquals(2, b.cursor)
        assertEquals(1, b.anchor)
    }

    @Test
    fun wordSteps() {
        val b = at("foo bar baz", 0)
        b.step(1, false, true)
        assertEquals(3, b.cursor)
        b.step(1, false, true)
        assertEquals(7, b.cursor)
        b.step(-1, false, true)
        assertEquals(4, b.cursor)
        b.step(1, true, true)
        assertEquals(7, b.cursor)
        assertEquals(4, b.anchor)
        b.step(-1, false, true)
        assertEquals(4, b.cursor)
        assertFalse(b.hasSelection)
    }

    @Test
    fun stepsStopAtBounds() {
        val b = at("ab", 0)
        b.step(-1, false, false)
        assertEquals(0, b.cursor)
        b.move(99, false)
        assertEquals(2, b.cursor)
        b.step(1, false, false)
        assertEquals(2, b.cursor)
        b.move(-5, false)
        assertEquals(0, b.cursor)
    }

    @Test
    fun stepsOverEmoji() {
        val b = at("a${emoji}b", 0)
        b.step(1, true, false)
        b.step(1, true, false)
        assertEquals(3, b.cursor)
        assertEquals("a$emoji", b.selected())
        b.move(3, false)
        b.step(-1, false, false)
        assertEquals(1, b.cursor)
        b.move(2, false, false)
        assertEquals(1, b.cursor)
        b.move(2, true, true)
        assertEquals(3, b.cursor)
        assertEquals(1, b.anchor)
    }

    @Test
    fun selectAllWordLine() {
        val b = EditBuffer("ab cd")
        b.selectAll()
        assertEquals("ab cd", b.selected())
        b.selectWord(1)
        assertEquals("ab", b.selected())
        b.selectWord(5)
        assertEquals("cd", b.selected())
        b.selectWord(3)
        assertEquals("cd", b.selected())
        val l = EditBuffer("one\ntwo\nthree")
        l.selectLine(5)
        assertEquals("two", l.selected())
        l.selectLine(0)
        assertEquals("one", l.selected())
        l.selectLine(3)
        assertEquals("one", l.selected())
        l.selectLine(13)
        assertEquals("three", l.selected())
        val e = EditBuffer("a\n\nb")
        e.selectLine(2)
        assertEquals("", e.selected())
        assertFalse(e.hasSelection)
    }

    @Test
    fun selectWordOverEmoji() {
        val b = EditBuffer("x $emoji$emoji y")
        b.selectWord(3)
        assertEquals(emoji + emoji, b.selected())
        assertEquals(2, b.selStart)
        assertEquals(6, b.selEnd)
    }

    @Test
    fun undoCoalescesBackspaceAndForwardDelete() {
        val b = EditBuffer("abcd")
        repeat(3) { b.delete(false, false) }
        assertEquals("a", b.text)
        assertTrue(b.undo())
        assertEquals("abcd", b.text)
        assertEquals(4, b.cursor)
        assertFalse(b.canUndo)
        val f = at("abcd", 0)
        repeat(3) { f.delete(true, false) }
        assertEquals("d", f.text)
        f.undo()
        assertEquals("abcd", f.text)
        assertFalse(f.canUndo)
    }

    @Test
    fun typingThenDeletingAreSeparateSteps() {
        val b = EditBuffer()
        "ab".forEach { b.insert(it.toString()) }
        b.delete(false, false)
        assertEquals("a", b.text)
        b.undo()
        assertEquals("ab", b.text)
        b.undo()
        assertEquals("", b.text)
    }

    @Test
    fun everyWhitespaceStartsNewStep() {
        val b = EditBuffer()
        "a  b".forEach { b.insert(it.toString()) }
        b.undo()
        assertEquals("a  ", b.text)
        b.undo()
        assertEquals("a ", b.text)
        b.undo()
        assertEquals("", b.text)
        assertFalse(b.canUndo)
    }

    @Test
    fun movingBreaksCoalescing() {
        val b = EditBuffer()
        "ab".forEach { b.insert(it.toString()) }
        b.move(0, false)
        b.move(2, false)
        b.insert("c")
        b.undo()
        assertEquals("ab", b.text)
        assertEquals(2, b.cursor)
    }

    @Test
    fun pasteAndNewlineAreOwnSteps() {
        val b = EditBuffer()
        b.insert("a")
        b.insert("b", paste = true)
        b.insert("c")
        assertEquals("abc", b.text)
        b.undo()
        assertEquals("ab", b.text)
        b.undo()
        assertEquals("a", b.text)
        b.undo()
        assertEquals("", b.text)
        b.insert("x")
        b.insert("\n")
        b.insert("y")
        b.undo()
        assertEquals("x\n", b.text)
        b.undo()
        assertEquals("x", b.text)
    }

    @Test
    fun undoRestoresSelectionAndRedoRestoresResult() {
        val b = EditBuffer("abc")
        b.selectAll()
        b.insert("x")
        assertEquals("x", b.text)
        assertTrue(b.undo())
        assertEquals("abc", b.text)
        assertEquals(0, b.selStart)
        assertEquals(3, b.selEnd)
        assertTrue(b.redo())
        assertEquals("x", b.text)
        assertEquals(1, b.cursor)
        assertFalse(b.redo())
    }

    @Test
    fun undoHistoryIsBoundedAndVersionAdvances() {
        val b = EditBuffer("", 1000)
        repeat(250) { b.insert("x", paste = true) }
        val v = b.version
        var n = 0
        while (b.undo()) n++
        assertEquals(200, n)
        assertEquals(50, b.text.length)
        assertTrue(b.version > v)
        val v2 = b.version
        assertFalse(b.undo())
        assertEquals(v2, b.version)
    }

    @Test
    fun atomFinding() {
        val text = "a {price:minecraft:iron_ingot} {flag:de} {c:red}"
        val atoms = Atoms.find(text)
        assertEquals(listOf(2 to 30, 31 to 40), atoms.map { it.start to it.end })
        assertTrue(Atoms.find("{price:} {c:red} {flag:a b} {unknown:x} {flag:").isEmpty())
        assertEquals(listOf(0 to 71), Atoms.find("{flag:" + "x".repeat(64) + "}").map { it.start to it.end })
        assertTrue(Atoms.find("{flag:" + "x".repeat(65) + "}").isEmpty())
        for (k in listOf("price", "flag", "head", "item", "icon")) assertEquals(1, Atoms.find("{$k:abc_1.2:x/y-z}").size)
    }

    @Test
    fun atomsRefreshWithText() {
        val b = EditBuffer("{flag:x}")
        assertEquals(1, b.atoms.size)
        b.set("plain")
        assertTrue(b.atoms.isEmpty())
        b.set("{item:y}")
        assertEquals(8, b.atoms[0].end)
        b.atomic = false
        assertTrue(b.atoms.isEmpty())
    }

    @Test
    fun nonAtomicBufferEditsInsideTokens() {
        val b = EditBuffer("{flag:x}")
        b.atomic = false
        b.move(3, false)
        assertEquals(3, b.cursor)
        b.move(8, false)
        b.delete(false, false)
        assertEquals("{flag:x", b.text)
    }

    @Test
    fun tokensActAsOneUnitForStepsDeleteAndSelection() {
        val b = at("a{flag:x}b", 0)
        b.step(1, false, false)
        assertEquals(1, b.cursor)
        b.step(1, true, false)
        assertEquals(9, b.cursor)
        assertEquals("{flag:x}", b.selected())
        b.step(-1, false, false)
        assertEquals(1, b.cursor)
        b.move(9, false)
        b.step(-1, false, false)
        assertEquals(1, b.cursor)
        b.move(1, false)
        b.delete(true, false)
        assertEquals("ab", b.text)
        val w = EditBuffer("x {flag:y}")
        w.delete(false, true)
        assertEquals("x ", w.text)
        val s = at("x {flag:y}", 0)
        s.move(4, true)
        assertEquals(10, s.cursor)
        s.delete(true, false)
        assertEquals("", s.text)
        val r = EditBuffer("x {flag:y}")
        r.move(4, true, false)
        assertEquals(2, r.cursor)
        assertEquals(10, r.anchor)
    }

    @Test
    fun selectWordOnToken() {
        val b = EditBuffer("a {flag:x} b")
        b.selectWord(5)
        assertEquals("{flag:x}", b.selected())
        b.selectWord(2)
        assertEquals("{flag:x}", b.selected())
    }

    @Test
    fun wrapToggleVariants() {
        val b = EditBuffer("ab")
        assertTrue(b.toggleWrap("**", "**"))
        assertEquals("ab****", b.text)
        assertEquals(4, b.cursor)
        assertTrue(b.toggleWrap("**", "**"))
        assertEquals("ab", b.text)
        assertEquals(2, b.cursor)
        val w = EditBuffer("word")
        w.move(1, false)
        w.move(3, true)
        w.toggleWrap("**", "**")
        assertEquals("w**or**d", w.text)
        assertEquals("or", w.selected())
        w.toggleWrap("**", "**")
        assertEquals("word", w.text)
        assertEquals("or", w.selected())
        val c = EditBuffer("hi")
        c.selectAll()
        c.toggleWrap("{c:red}", "{/c}")
        assertEquals("{c:red}hi{/c}", c.text)
        assertEquals("hi", c.selected())
        assertTrue(c.atoms.isEmpty())
        val e = EditBuffer(emoji)
        e.selectAll()
        e.toggleWrap("_", "_")
        assertEquals("_${emoji}_", e.text)
        assertEquals(emoji, e.selected())
    }

    @Test
    fun wrapRespectsLimit() {
        val b = EditBuffer("abcd", 5)
        b.selectAll()
        val v = b.version
        assertFalse(b.toggleWrap("**", "**"))
        assertEquals("abcd", b.text)
        assertEquals(v, b.version)
        val full = EditBuffer("**ab**", 6)
        full.selectAll()
        assertTrue(full.toggleWrap("**", "**"))
        assertEquals("ab", full.text)
    }

    @Test
    fun linePrefixVariants() {
        val b = EditBuffer("one\ntwo")
        b.move(5, false)
        assertTrue(b.toggleLinePrefix("# "))
        assertEquals("one\n# two", b.text)
        assertEquals(7, b.cursor)
        val h = EditBuffer("## x")
        h.toggleLinePrefix("# ")
        assertEquals("# x", h.text)
        val r = EditBuffer("- one")
        r.toggleLinePrefix("- ")
        assertEquals("one", r.text)
        assertEquals(3, r.cursor)
        val mixed = EditBuffer("- a\nb")
        mixed.selectAll()
        mixed.toggleLinePrefix("- ")
        assertEquals("- a\n- b", mixed.text)
        val empty = EditBuffer("")
        empty.toggleLinePrefix("- ")
        assertEquals("- ", empty.text)
    }

    @Test
    fun linePrefixSelectionScope() {
        val b = EditBuffer("a\nb\nc")
        b.move(2, false)
        b.toggleLinePrefix("# ")
        assertEquals("a\n# b\nc", b.text)
        assertEquals(4, b.cursor)
        val p = EditBuffer("a\nb\nc")
        p.move(2, false)
        p.move(5, true)
        p.toggleLinePrefix("- ")
        assertEquals("a\n- b\n- c", p.text)
        val t = EditBuffer("a\nb\n")
        t.selectAll()
        t.toggleLinePrefix("- ")
        assertEquals("- a\n- b\n", t.text)
        val s = EditBuffer("a\nb")
        s.selectAll()
        s.toggleLinePrefix("> ")
        assertEquals("> a\n> b", s.text)
        assertEquals(0, s.selStart)
        assertEquals(s.text.length, s.selEnd)
    }

    @Test
    fun linePrefixRespectsLimit() {
        val b = EditBuffer("abcd", 5)
        assertFalse(b.toggleLinePrefix("- "))
        assertEquals("abcd", b.text)
    }

    @Test
    fun insertBlockVariants() {
        val e = EditBuffer("")
        assertTrue(e.insertBlock("---"))
        assertEquals("---\n", e.text)
        val n = EditBuffer("abc\n")
        n.insertBlock("---")
        assertEquals("abc\n---\n", n.text)
        assertEquals(n.text.length, n.cursor)
        val s = EditBuffer("abcdef")
        s.move(3, false)
        s.move(6, true)
        s.insertBlock("---")
        assertEquals("abc\n---\n", s.text)
        s.undo()
        assertEquals("abcdef", s.text)
        val full = EditBuffer("abcd", 6)
        assertFalse(full.insertBlock("---"))
        assertEquals("abcd", full.text)
    }

    @Test
    fun randomOperationsKeepInvariants() {
        val rnd = Random(7)
        val pieces = listOf("a", "b", " ", "\n", emoji, "word", "{flag:x}", "{price:minecraft:gold_ingot}", "**", "\r\n")
        val prefixes = EditBuffer.BLOCK_PREFIXES
        repeat(30) {
            val b = EditBuffer("", 40)
            repeat(300) {
                when (rnd.nextInt(14)) {
                    0, 1, 2 -> b.insert(pieces.random(rnd), rnd.nextBoolean())
                    3 -> b.delete(rnd.nextBoolean(), rnd.nextBoolean())
                    4 -> b.move(rnd.nextInt(-2, b.text.length + 3), rnd.nextBoolean(), rnd.nextBoolean())
                    5 -> b.step(if (rnd.nextBoolean()) 1 else -1, rnd.nextBoolean(), rnd.nextBoolean())
                    6 -> b.undo()
                    7 -> b.redo()
                    8 -> b.selectWord(rnd.nextInt(-2, b.text.length + 3))
                    9 -> b.selectLine(rnd.nextInt(0, b.text.length + 1))
                    10 -> b.toggleWrap("**", "**")
                    11 -> b.toggleLinePrefix(prefixes.random(rnd))
                    12 -> b.insertBlock("---")
                    else -> b.selectAll()
                }
                assertTrue(b.text.length <= 40)
                assertTrue(b.cursor in 0..b.text.length && b.anchor in 0..b.text.length)
                assertTrue(wellFormed(b.text), "lone surrogate in ${b.text}")
            }
        }
    }
}
