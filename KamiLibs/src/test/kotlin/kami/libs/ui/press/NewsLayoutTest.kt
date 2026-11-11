package kami.libs.ui.press

import kami.libs.press.Block
import kami.libs.press.Doc
import kami.libs.press.Ref
import kami.libs.press.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewsLayoutTest {
    private val m = TextMeasure { text, _ -> text.length * 6 }
    private val words = List(60) { "word$it" }.joinToString(" ")

    private fun para(text: String = words, centered: Boolean = false) = Block.Para(listOf(Span(text)), centered)

    private fun layout(width: Int, vararg blocks: Block, title: String = "") = NewsLayout.layout(Article(title, doc = Doc(blocks.toList())), width, m)

    @Test
    fun emptyDocIsJustPadding() {
        assertEquals(NewsLayout.PAD * 2, layout(300).height)
    }

    @Test
    fun columnsFollowWidth() {
        assertEquals(1, layout(200, para()).columns)
        assertEquals(2, layout(300, para()).columns)
        assertEquals(3, layout(500, para()).columns)
    }

    @Test
    fun narrowerIsNeverShorter() {
        val heights = listOf(500, 400, 300, 200, 120).map { layout(it, para(), para()).height }
        assertEquals(heights.sorted(), heights)
    }

    @Test
    fun columnsAreBalanced() {
        val one = layout(200, para(), para(), para())
        val three = layout(500, para(), para(), para())
        assertTrue(three.height < one.height)
        val xs = three.rows.filter { it.kind == RowKind.TEXT }.map { it.x }.distinct()
        assertEquals(3, xs.size)
        assertEquals(2, three.rows.count { it.kind == RowKind.VRULE })
    }

    @Test
    fun heightEqualsLastRowBottomPlusPad() {
        val l = layout(200, para(), Block.Rule, para("short"))
        val bottom = l.rows.filter { it.kind != RowKind.VRULE }.maxOf { it.y + it.h }
        assertEquals(bottom + NewsLayout.PAD, l.height)
    }

    @Test
    fun dropCapOnlyOnLongFirstParagraph() {
        assertEquals(1, layout(200, para()).rows.count { it.kind == RowKind.CAP })
        assertEquals(0, layout(200, para("short text")).rows.count { it.kind == RowKind.CAP })
        assertEquals(0, layout(200, Block.Rule, para()).rows.count { it.kind == RowKind.CAP })
        assertEquals(0, layout(200, para(centered = true)).rows.count { it.kind == RowKind.CAP })
    }

    @Test
    fun capIndentsFirstThreeLines() {
        val rows = layout(200, para()).rows
        val text = rows.filter { it.kind == RowKind.TEXT }
        assertTrue(text[0].pieces.first().x > 0)
        assertTrue(text[3].pieces.first().x == 0)
    }

    @Test
    fun titleWrapsAtDoubleScale() {
        val l = layout(200, title = "A rather long headline for the paper")
        val title = l.rows.filter { it.kind == RowKind.TEXT }
        assertTrue(title.size > 1)
        assertTrue(title.all { it.h == NewsLayout.LINE * NewsLayout.TITLE_SCALE && it.pieces.all { p -> p.scale == 2 } })
        assertEquals(1, l.rows.count { it.kind == RowKind.DOUBLE })
    }

    @Test
    fun ruleBlockIsADoubleRule() {
        val without = layout(200, para("a"))
        val with = layout(200, para("a"), Block.Rule)
        assertEquals(1, with.rows.count { it.kind == RowKind.DOUBLE })
        assertEquals(without.height + 4 + 5, with.height)
    }

    @Test
    fun centeredParagraphIsShifted() {
        val l = layout(200, para("hello", centered = true))
        assertTrue(l.rows.first { it.kind == RowKind.TEXT }.pieces.first().x > 0)
    }

    @Test
    fun quoteAndBulletsIndent() {
        val l = layout(200, Block.Quote(listOf(Span("quoted"))), Block.Bullets(listOf(listOf(Span("one")), listOf(Span("two")))))
        assertEquals(1, l.rows.count { it.kind == RowKind.BAR })
        val quote = l.rows.first { it.kind == RowKind.TEXT }
        assertEquals(NewsLayout.QUOTE_INDENT, quote.pieces.first().x)
        val bullet = l.rows.filter { it.kind == RowKind.TEXT }.drop(1)
        assertEquals("•", bullet[0].pieces[0].text)
        assertEquals(NewsLayout.BULLET_INDENT, bullet[0].pieces[1].x)
    }

    @Test
    fun refsTakeTheirWidthAndStayWhole() {
        val data = NewsData(price = { PriceStamp("12.5", "Nov 11") })
        val spans = listOf(Span("buy "), Span("", ref = Ref.Price("minecraft:iron_ingot")), Span(" now"))
        val l = NewsLayout.layout(Article(doc = Doc(listOf(Block.Para(spans))), data = data), 200, m)
        val piece = l.rows.first { it.kind == RowKind.TEXT }.pieces.first { it.ref != null }
        assertEquals(NewsLayout.refWidth(Ref.Price("minecraft:iron_ingot"), data, m) + 2, piece.w)
    }

    @Test
    fun longWordsAreBrokenToFit() {
        val l = layout(100, para("x".repeat(80)))
        assertTrue(l.rows.filter { it.kind == RowKind.TEXT }.all { r -> r.pieces.all { it.x + it.w <= 100 } })
    }

    @Test
    fun signatureJoinsParts() {
        assertEquals("Nov 11, Ashland, Ann, Bob", Newsprint.signature("Nov 11", "Ashland", listOf("Ann", "Bob")))
        assertEquals("Ashland", Newsprint.signature("", "Ashland", emptyList()))
    }

    @Test
    fun dropCapFitsShortParagraphs() {
        val short = layout(1000, para("Alpha ".repeat(12).trim()))
        assertTrue(short.rows.none { it.kind == RowKind.CAP })
        val long = layout(1000, para("Alpha ".repeat(60).trim()))
        assertTrue(long.rows.any { it.kind == RowKind.CAP })
        assertTrue(long.rows.all { it.y + it.h <= long.height - NewsLayout.PAD })
    }

    @Test
    fun wideRefsShrinkToTheColumn() {
        val m = TextMeasure { text, _ -> text.length * 6 }
        val ref = Ref.Price("minecraft:" + "x".repeat(80))
        val l = NewsLayout.layout(Article(doc = Doc(listOf(Block.Para(listOf(Span("", ref = ref)))))), 200, m)
        assertTrue(l.rows.filter { it.kind == RowKind.TEXT }.flatMap { it.pieces }.all { it.x + it.w <= 200 - NewsLayout.PAD * 2 })
    }
}
