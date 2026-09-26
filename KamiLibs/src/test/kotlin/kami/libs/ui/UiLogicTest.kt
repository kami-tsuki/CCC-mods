package kami.libs.ui

import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.style.Format
import kami.libs.ui.widget.TextState
import kotlin.test.Test
import kotlin.test.assertEquals

class UiLogicTest {
    @Test
    fun columnsFillTheWholeWidth() {
        val cols = Rect(0, 0, 101, 10).columns(3, 4)
        assertEquals(3, cols.size)
        assertEquals(0, cols.first().x)
        assertEquals(101, cols.last().right)
    }

    @Test
    fun fixedColumnsShareTheRest() {
        val (a, b, c) = Rect(0, 0, 200, 10).columnsFixed(50, Rect.FILL, 30, gap = 10)
        assertEquals(50, a.w)
        assertEquals(30, c.w)
        assertEquals(200 - 50 - 30 - 20, b.w)
    }

    @Test
    fun flowStacksWithGaps() {
        val f = Flow(Rect(0, 0, 10, 100), 5)
        assertEquals(0, f.take(20).y)
        assertEquals(25, f.take(10).y)
        assertEquals(40, f.rest.y)
    }

    @Test
    fun amountsParseShortForms() {
        assertEquals(1500L, Format.parseAmount("1.5k"))
        assertEquals(2_000_000L, Format.parseAmount("2m"))
        assertEquals(1234L, Format.parseAmount("1,234"))
        assertEquals(null, Format.parseAmount("abc"))
    }

    @Test
    fun compactNumbersKeepSmallValuesExact() {
        assertEquals("99,999", Format.compact(99_999))
        assertEquals("1.2M", Format.compact(1_234_567))
        assertEquals("-150K", Format.compact(-150_000))
    }

    @Test
    fun durationsReadNaturally() {
        assertEquals("3d 4h", Format.duration((3 * 24 + 4) * 3_600_000L))
        assertEquals("2h 5m", Format.duration(125 * 60_000L))
        assertEquals("0m", Format.duration(-5))
    }

    @Test
    fun textEditingHandlesSelectionAndWords() {
        val t = TextState("hello world")
        t.move(5, false)
        t.insert(",", 64) { true }
        assertEquals("hello, world", t.text)
        t.move(t.text.length, false)
        t.delete(forward = false, word = true)
        assertEquals("hello, ", t.text)
        t.anchor = 0
        t.cursor = 5
        t.insert("bye", 64) { true }
        assertEquals("bye, ", t.text)
    }

    @Test
    fun textRespectsLimit() {
        val t = TextState("abc")
        assertEquals(false, t.insert("defg", 5) { true })
        assertEquals("abc", t.text)
    }
}
