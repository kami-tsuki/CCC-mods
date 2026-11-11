package kami.libs.ui.press

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WrapTest {
    private val emoji = "😀"

    private fun prefix(text: String, per: Int = 6) = IntArray(text.length + 1) { it * per }

    private fun spans(segs: List<Seg>) = segs.map { it.start to it.end }

    @Test
    fun emptyAndTrailingNewline() {
        assertEquals(listOf(0 to 0), spans(Wrap.lines("", 100, prefix(""))))
        assertEquals(listOf(0 to 1, 2 to 2), spans(Wrap.lines("a\n", 100, prefix("a\n"))))
        assertEquals(listOf(0 to 0, 1 to 1), spans(Wrap.lines("\n", 100, prefix("\n"))))
    }

    @Test
    fun zeroAndNegativeWidthStillProgress() {
        for (w in listOf(0, -1, -100)) {
            assertEquals(listOf(0 to 1, 1 to 2, 2 to 3), spans(Wrap.lines("abc", w, prefix("abc"))))
            assertEquals(listOf(0 to 2), spans(Wrap.lines(emoji, w, prefix(emoji))))
            val token = "{flag:x}"
            assertEquals(listOf(0 to 8), spans(Wrap.lines(token, w, prefix(token), Atoms.find(token))))
        }
    }

    @Test
    fun longWordsAreChoppedAndSpacesStayAtLineEnds() {
        val text = "aaaa bbbbbbbbbbbb"
        assertEquals(listOf(0 to 5, 5 to 10, 10 to 15, 15 to 17), spans(Wrap.lines(text, 30, prefix(text))))
        val t2 = "aaa bbb"
        assertEquals(listOf(0 to 4, 4 to 7), spans(Wrap.lines(t2, 18, prefix(t2))))
    }

    @Test
    fun softFlagMarksWrappedSegments() {
        val text = "aaa bbb\nccc"
        val segs = Wrap.lines(text, 18, prefix(text))
        assertEquals(listOf(0 to 4, 4 to 7, 8 to 11), spans(segs))
        assertEquals(listOf(true, false, false), segs.map { it.soft })
    }

    @Test
    fun segmentsAlwaysCoverTheTextWithoutSplittingPairsOrTokens() {
        val texts = listOf(
            "", "a", "\n\n", "hello world foo bar", "  leading and   multiple   spaces  ", "x{flag:de}y {price:minecraft:gold_ingot} z",
            "$emoji$emoji$emoji a $emoji b", "line one\n$emoji{item:a}\n\nlast line is longer than the rest", "{flag:a}{flag:b}{flag:c}", "abcdefghijklmnopqrstuvwxyz"
        )
        for (text in texts) for (w in listOf(-5, 0, 1, 5, 6, 7, 12, 30, 61, 100, 1000)) {
            val atoms = Atoms.find(text)
            val segs = Wrap.lines(text, w, prefix(text), atoms)
            assertEquals(0, segs.first().start, "$text@$w")
            assertEquals(text.length, segs.last().end, "$text@$w")
            segs.zipWithNext { a, b ->
                assertTrue(a.start <= a.end, "$text@$w")
                if (a.soft && b.start == a.end) return@zipWithNext
                assertEquals(a.end + 1, b.start, "$text@$w")
                assertEquals('\n', text[a.end], "$text@$w")
            }
            for (s in segs) {
                if (s.soft) assertTrue(s.end > s.start, "$text@$w")
                assertTrue(atoms.none { it.start < s.end && s.end < it.end }, "atom split $text@$w")
                if (s.end in 1 until text.length) assertTrue(!(text[s.end - 1].isHighSurrogate() && text[s.end].isLowSurrogate()), "pair split $text@$w")
            }
        }
    }

    @Test
    fun lineOfBoundaries() {
        val text = "aaa bbb\n\nccc"
        val segs = Wrap.lines(text, 18, prefix(text))
        assertEquals(0, Wrap.lineOf(segs, -3))
        assertEquals(0, Wrap.lineOf(segs, 0))
        assertEquals(0, Wrap.lineOf(segs, 3))
        assertEquals(1, Wrap.lineOf(segs, 4))
        assertEquals(1, Wrap.lineOf(segs, 7))
        assertEquals(2, Wrap.lineOf(segs, 8))
        assertEquals(3, Wrap.lineOf(segs, 9))
        assertEquals(3, Wrap.lineOf(segs, 12))
        assertEquals(3, Wrap.lineOf(segs, 99))
        assertEquals(0, Wrap.lineOf(Wrap.lines("", 10, prefix("")), 0))
    }

    @Test
    fun xOfClampsToTheSegment() {
        val p = prefix("0123456789")
        val seg = Seg(2, 5, false)
        assertEquals(12, Wrap.xOf(p, seg, 4))
        assertEquals(0, Wrap.xOf(p, seg, 0))
        assertEquals(18, Wrap.xOf(p, seg, 99))
        assertEquals(0, Wrap.xOf(p, Seg(3, 3, false), 3))
    }

    @Test
    fun lastPosDiffersForSoftAndHardSegments() {
        assertEquals(5, Wrap.lastPos(Seg(2, 5, false)))
        assertEquals(4, Wrap.lastPos(Seg(2, 5, true)))
        assertEquals(3, Wrap.lastPos(Seg(3, 3, true)))
        assertEquals(3, Wrap.lastPos(Seg(3, 3, false)))
    }

    @Test
    fun posAtPicksTheNearestCaret() {
        val p = prefix("01234567890123456789")
        val hard = Seg(0, 3, false)
        assertEquals(0, Wrap.posAt(p, hard, -10))
        assertEquals(0, Wrap.posAt(p, hard, 2))
        assertEquals(1, Wrap.posAt(p, hard, 9))
        assertEquals(1, Wrap.posAt(p, hard, 6))
        assertEquals(2, Wrap.posAt(p, hard, 11))
        assertEquals(3, Wrap.posAt(p, hard, 1000))
        assertEquals(2, Wrap.posAt(p, Seg(0, 3, true), 1000))
        assertEquals(5, Wrap.posAt(p, Seg(5, 5, false), 40))
        assertEquals(5, Wrap.posAt(p, Seg(5, 5, true), 0))
        assertEquals(7, Wrap.posAt(p, Seg(5, 9, false), 13))
    }

    @Test
    fun verticalMovement() {
        val text = "abc\n\ndef"
        val p = prefix(text)
        val segs = Wrap.lines(text, 100, p)
        assertEquals(listOf(0 to 3, 4 to 4, 5 to 8), spans(segs))
        assertEquals(4, Wrap.vertical(segs, p, 2, 1, 12))
        assertEquals(7, Wrap.vertical(segs, p, 4, 1, 12))
        assertEquals(4, Wrap.vertical(segs, p, 7, -1, 12))
        assertEquals(2, Wrap.vertical(segs, p, 7, -2, 12))
        assertEquals(0, Wrap.vertical(segs, p, 1, -1, 5))
        assertEquals(8, Wrap.vertical(segs, p, 6, 1, 5))
        assertEquals(1, Wrap.vertical(segs, p, 2, 0, 6))
    }

    @Test
    fun verticalOntoWrappedSegmentsStaysBeforeTheBreak() {
        val text = "aaa bbb ccc"
        val p = prefix(text)
        val segs = Wrap.lines(text, 30, p)
        assertEquals(listOf(0 to 4, 4 to 8, 8 to 11), spans(segs))
        assertEquals(7, Wrap.vertical(segs, p, 1, 1, 500))
        assertEquals(3, Wrap.vertical(segs, p, 5, -1, 500))
    }
}
