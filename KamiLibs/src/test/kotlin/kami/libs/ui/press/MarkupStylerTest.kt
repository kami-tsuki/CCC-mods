package kami.libs.ui.press

import kami.libs.press.Dye
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkupStylerTest {
    private val dim = MarkupStyler.DIM
    private val head = MarkupStyler.BOLD or MarkupStyler.HEAD

    private fun shape(text: String) = MarkupStyler.runs(text).map { Triple(it.start, it.end, it.flags) }

    private fun runAt(text: String, i: Int) = MarkupStyler.runs(text).first { i >= it.start && i < it.end }

    @Test
    fun emptyTextHasNoRuns() {
        assertTrue(MarkupStyler.runs("").isEmpty())
    }

    @Test
    fun plainTextIsOneRun() {
        assertEquals(listOf(Triple(0, 5, 0)), shape("plain"))
        assertEquals(listOf(Triple(0, 5, 0)), shape("a\nb c"))
    }

    @Test
    fun runsAlwaysCoverTheTextAndNeighboursDiffer() {
        val samples = listOf(
            "# Title\n> quote *x*\n- item **b**\n---\n{c:red}x{/c} {flag:de}", "**a *b* c**", "_a_ snake_case_x *", "{c:red}{c:blue}a{/c}b{/c}\n\n",
            "\n\n", "#", "# ", "***", "{{flag:x}}", "😀 **😀** _😀_"
        )
        for (s in samples) {
            val runs = MarkupStyler.runs(s)
            assertEquals(0, runs.first().start, s)
            assertEquals(s.length, runs.last().end, s)
            runs.zipWithNext { a, b ->
                assertEquals(a.end, b.start, s)
                assertTrue(a.flags != b.flags || a.dye != b.dye, s)
            }
            assertTrue(runs.all { it.end > it.start }, s)
        }
    }

    @Test
    fun headings() {
        assertEquals(listOf(Triple(0, 2, dim), Triple(2, 7, head)), shape("# Title"))
        assertEquals(listOf(Triple(0, 7, dim), Triple(7, 8, head)), shape("###### x"))
        assertEquals(listOf(Triple(0, 3, dim), Triple(3, 4, head)), shape("#  x"))
        assertEquals(listOf(Triple(0, 8, 0)), shape("#nospace"))
        assertEquals(listOf(Triple(0, 9, 0)), shape("####### x"))
        assertEquals(listOf(Triple(0, 2, 0)), shape("# "))
        assertEquals(listOf(Triple(0, 2, dim), Triple(2, 3, head), Triple(3, 5, 0)), shape("# A\nb"))
    }

    @Test
    fun quoteListsAndRule() {
        val quote = MarkupStyler.ITALIC or MarkupStyler.QUOTE
        assertEquals(listOf(Triple(0, 2, dim), Triple(2, 4, quote)), shape("> hi"))
        for (mark in listOf("- ", "* ", "^ ")) assertEquals(listOf(Triple(0, 2, dim), Triple(2, 4, 0)), shape("${mark}hi"))
        assertEquals(listOf(Triple(0, 3, dim)), shape("---"))
        assertEquals(listOf(Triple(0, 5, 0)), shape("--- x"))
        assertEquals(listOf(Triple(0, 4, 0)), shape("----"))
        assertEquals(listOf(Triple(0, 3, 0)), shape(" - "))
    }

    @Test
    fun bold() {
        assertEquals(
            listOf(Triple(0, 2, 0), Triple(2, 4, dim), Triple(4, 5, MarkupStyler.BOLD), Triple(5, 7, dim), Triple(7, 9, 0)),
            shape("a **b** c")
        )
        assertEquals(listOf(Triple(0, 3, 0)), shape("**a"))
        assertTrue(runAt("x **b** y", 4).bold)
    }

    @Test
    fun italic() {
        assertEquals(listOf(Triple(0, 1, dim), Triple(1, 2, MarkupStyler.ITALIC), Triple(2, 3, dim)), shape("*a*"))
        assertEquals(listOf(Triple(0, 1, dim), Triple(1, 2, MarkupStyler.ITALIC), Triple(2, 3, dim)), shape("_a_"))
        assertEquals(listOf(Triple(0, 12, 0)), shape("snake_case_x"))
        assertEquals(listOf(Triple(0, 5, 0)), shape("a * b"))
        assertTrue(runAt("_a_", 1).italic)
    }

    @Test
    fun boldAndItalicNest() {
        val text = "**a *b* c**"
        assertEquals(MarkupStyler.BOLD or MarkupStyler.ITALIC, runAt(text, 5).flags)
        assertEquals(MarkupStyler.BOLD, runAt(text, 2).flags)
        assertEquals(MarkupStyler.BOLD, runAt(text, 8).flags)
        assertEquals(dim, runAt(text, 0).flags)
        assertEquals(dim, runAt(text, 9).flags)
        assertEquals(dim, runAt(text, 4).flags)
        assertEquals(dim, runAt(text, 6).flags)
    }

    @Test
    fun italicInsideQuoteKeepsQuoteFlags() {
        val text = "> *a*"
        assertEquals(dim, runAt(text, 2).flags)
        assertEquals(MarkupStyler.ITALIC or MarkupStyler.QUOTE, runAt(text, 3).flags)
        assertEquals(dim, runAt(text, 4).flags)
    }

    @Test
    fun boldInsideHeadingKeepsHeadingFlags() {
        val text = "# **x**"
        assertEquals(dim, runAt(text, 2).flags)
        assertEquals(head, runAt(text, 4).flags)
    }

    @Test
    fun colorTags() {
        val runs = MarkupStyler.runs("{c:red}hi{/c}")
        assertEquals(listOf(Triple(0, 7, dim), Triple(7, 9, 0), Triple(9, 13, dim)), runs.map { Triple(it.start, it.end, it.flags) })
        assertTrue(runs.all { it.dye == Dye.RED })
        val after = MarkupStyler.runs("{c:red}a{/c}x")
        assertNull(after.last().dye)
        assertEquals(0, after.last().flags)
        assertEquals(Dye.RED, MarkupStyler.runs("{c:RED}a")[1].dye)
    }

    @Test
    fun colorNestingUnknownAndStrayTags() {
        val text = "{c:red}{c:blue}a{/c}b{/c}"
        assertEquals(Dye.BLUE, runAt(text, 15).dye)
        assertEquals(Dye.RED, runAt(text, 20).dye)
        assertEquals(listOf(Triple(0, 7, dim), Triple(7, 8, 0)), shape("{c:zzz}a"))
        assertEquals(Dye.RED, runAt("{c:red}{c:zzz}a{/c}b", 14).dye)
        assertNull(runAt("{c:zzz}a", 7).dye)
        assertEquals(listOf(Triple(0, 4, dim)), shape("{/c}"))
        assertNull(runAt("{/c}", 0).dye)
    }

    @Test
    fun colorEndsAtTheLine() {
        val text = "{c:red}a\nb"
        assertEquals(Dye.RED, runAt(text, 7).dye)
        assertNull(runAt(text, 9).dye)
    }

    @Test
    fun everyTokenKindIsAChip() {
        for (kind in listOf("price", "flag", "head", "item", "icon")) {
            val token = "{$kind:minecraft:thing_1}"
            val text = "a $token b"
            assertEquals(listOf(Triple(0, 2, 0), Triple(2, 2 + token.length, MarkupStyler.TOKEN), Triple(2 + token.length, text.length, 0)), shape(text))
            assertTrue(runAt(text, 3).token)
            assertNull(runAt(text, 3).dye)
        }
    }

    @Test
    fun tokenKeepsSurroundingColorAndIgnoresBlockStyle() {
        val colored = MarkupStyler.runs("{c:red}{flag:x}{/c}")
        assertEquals(Dye.RED, colored[1].dye)
        assertTrue(colored[1].token)
        assertEquals(MarkupStyler.TOKEN, runAt("# {item:x}", 5).flags)
    }

    @Test
    fun invalidTokensStayPlain() {
        assertEquals(listOf(Triple(0, 11, 0)), shape("{price:a b}"))
        assertEquals(listOf(Triple(0, 8, 0)), shape("{price:}"))
        assertEquals(listOf(Triple(0, 11, 0)), shape("{unknown:x}"))
        assertEquals(listOf(Triple(0, 8, 0)), shape("{flag:x "))
    }

    @Test
    fun linesAreStyledIndependently() {
        val text = "# A\n> q\n- i\nplain"
        assertEquals(head, runAt(text, 2).flags)
        assertEquals(MarkupStyler.ITALIC or MarkupStyler.QUOTE, runAt(text, 6).flags)
        assertEquals(0, runAt(text, 10).flags)
        assertEquals(0, runAt(text, 13).flags)
        assertEquals(dim, runAt(text, 4).flags)
    }

    @Test
    fun runFlagAccessors() {
        val r = Run(0, 1, MarkupStyler.BOLD or MarkupStyler.ITALIC or MarkupStyler.DIM or MarkupStyler.TOKEN, null)
        assertTrue(r.bold && r.italic && r.dim && r.token)
        val n = Run(0, 1, 0, null)
        assertTrue(!n.bold && !n.italic && !n.dim && !n.token)
    }

    @Test
    fun activeStyleAtTheCaret() {
        val text = "# Head\n**bold** and _it_\n> quote\n- item\n^ mid\n{c:red}red{/c}"
        fun at(marker: String, shift: Int = 0) = MarkupStyler.active(text, text.indexOf(marker) + shift)
        assertEquals(1, at("Head").heading)
        assertTrue(!at("Head").bold)
        assertTrue(at("bold", 2).bold)
        assertTrue(!at("and").bold)
        assertTrue(at("it", 1).italic)
        assertTrue(at("quote").quote)
        assertTrue(at("item").list)
        assertTrue(at("mid").center)
        assertEquals(Dye.RED, at("}red", 3).dye)
        assertEquals(null, at("and").dye)
    }

    @Test
    fun colorTableCoversEveryDye() {
        for (d in Dye.entries) {
            assertEquals(0xFF000000.toInt() or PressColors.rgb(d), PressColors.argb(d))
            assertTrue(PressColors.rgb(d) in 0..0xFFFFFF)
        }
        assertEquals(0xF9FFFE, PressColors.rgb(Dye.WHITE))
        assertEquals(0x1D1D21, PressColors.rgb(Dye.BLACK))
    }
}
