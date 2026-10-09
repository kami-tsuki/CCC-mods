package kami.libs.ui

import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.text.LangAudit
import kami.libs.ui.style.Format
import kami.libs.ui.text.Translations
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.gradientAt
import kami.libs.ui.map.Viewport
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class UiLogicTest {
    init {
        val english = LangAudit.load(Path.of("src/main/resources/assets/kami_libs/lang/en_us.json"))
        Translations.lookup = english::get
    }

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

    private fun <T> us(block: () -> T): T {
        val saved = Format.locale
        Format.locale = java.util.Locale.US
        try { return block() } finally { Format.locale = saved }
    }

    @Test
    fun amountsParseShortForms() = us {
        assertEquals(1500L, Format.parseAmount("1.5k"))
        assertEquals(2_000_000L, Format.parseAmount("2m"))
        assertEquals(1234L, Format.parseAmount("1,234"))
        assertEquals(null, Format.parseAmount("abc"))
    }

    @Test
    fun amountsRejectNegativeAndNonFiniteUnlessAllowed() = us {
        assertEquals(null, Format.parseAmount("-5"))
        assertEquals(-5L, Format.parseAmount("-5", allowNegative = true))
        assertEquals(null, Format.parseAmount("infinity"))
        assertEquals(null, Format.parseAmount("NaN"))
        assertEquals(null, Format.parseAmount("1e30"))
    }

    @Test
    fun compactNumbersKeepSmallValuesExact() = us {
        assertEquals("99,999", Format.number(99_999))
        assertEquals("1.2M", Format.number(1_234_567))
        assertEquals("-150k", Format.number(-150_000))
    }

    @Test
    fun durationsReadNaturally() = us {
        assertEquals("3d 4h", Format.duration((3 * 24 + 4) * 3_600_000L))
        assertEquals("2h 5m", Format.duration(125 * 60_000L))
        assertEquals("0s", Format.duration(-5))
        assertEquals("0s", Format.duration(0))
        assertEquals("±0", Format.signed(0))
    }

    @Test
    fun compactNumbersPromoteAtTheRoundingEdge() = us {
        assertEquals("999.9k", Format.number(999_949))
        assertEquals("1M", Format.number(999_950))
        assertEquals("1T", Format.number(1_000_000_000_000))
    }

    @Test
    fun amountsAcceptSuffixesAndRejectEmpty() = us {
        assertEquals(1000L, Format.parseAmount("1k"))
        assertEquals(1500L, Format.parseAmount(" 1_500 "))
        assertEquals(null, Format.parseAmount(""))
        assertEquals(null, Format.parseAmount("k"))
    }

    @Test
    fun germanAmountsTreatCommaAsDecimalAndDotAsGrouping() {
        val saved = Format.locale
        Format.locale = java.util.Locale.GERMANY
        try {
            assertEquals(1500L, Format.parseAmount("1,5k"))
            assertEquals(1500L, Format.parseAmount("1.5k"))
            assertEquals(1234L, Format.parseAmount("1.234"))
            assertEquals(2_500_000L, Format.parseAmount("2,5m"))
        } finally { Format.locale = saved }
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

    @Test
    fun viewportMapsWorldAndScreenBothWays() {
        val v = Viewport(2.0).apply { view = Rect(100, 50, 200, 100); cx = 10.0; cz = -5.0 }
        assertEquals(10.0, v.worldX(200.0))
        assertEquals(-5.0, v.worldZ(100.0))
        assertEquals(250.0, v.screenX(v.worldX(250.0)))
        assertEquals(60.0, v.screenY(v.worldZ(60.0)))
    }

    @Test
    fun viewportZoomKeepsCursorPointAndClamps() {
        val v = Viewport(1.0, 0.5, 4.0).apply { view = Rect(0, 0, 100, 100) }
        val before = v.worldX(80.0) to v.worldZ(20.0)
        v.zoomAt(1.5, 80.0, 20.0)
        assertEquals(before.first, v.worldX(80.0), 1e-9)
        assertEquals(before.second, v.worldZ(20.0), 1e-9)
        v.zoomAt(100.0)
        assertEquals(4.0, v.unitsPerPx)
        v.locked = true
        assertEquals(false, v.zoomAt(0.5) || v.pan(5.0, 5.0))
    }

    @Test
    fun gradientInterpolatesStops() {
        assertEquals(0xFF000000.toInt(), gradientAt(listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), -1.0))
        assertEquals(0xFFFFFFFF.toInt(), gradientAt(listOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt()), 1.0))
        assertEquals(0xFF808080.toInt(), gradientAt(listOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt()), 0.5))
    }

    @Test
    fun resetAfterLocaleSwitchRecomputesNumbersAndDropsCompacts() {
        val saved = Format.locale
        try {
            Format.locale = java.util.Locale.US
            Format.reset()
            assertEquals("1,234", Format.number(1234))
            assertEquals("1.2M", Format.number(1_234_567))
            Format.locale = java.util.Locale.GERMANY
            Format.reset()
            assertEquals("1.234", Format.number(1234))
            assertEquals("1,2M", Format.number(1_234_567))
            Format.reset()
            assertEquals(false, Format.hasCompacts)
            assertEquals(null, Format.expand("1,2M"))
        } finally {
            Format.locale = saved
            Format.reset()
        }
    }
}
