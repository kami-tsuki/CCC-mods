package kami.libs.ui.press

import kami.libs.press.Block
import kami.libs.press.Doc
import kami.libs.press.Dye
import kami.libs.press.Ref
import kami.libs.press.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewsLayoutMoreTest {
    private val m = TextMeasure { text, _ -> text.length * 6 }
    private val words = List(60) { "word$it" }.joinToString(" ")

    private fun para(text: String = words, centered: Boolean = false) = Block.Para(listOf(Span(text)), centered)

    private fun layout(width: Int, vararg blocks: Block) = NewsLayout.layout(Article(doc = Doc(blocks.toList())), width, m)

    private fun texts(l: Layout) = l.rows.filter { it.kind == RowKind.TEXT }

    private fun check(l: Layout, label: String) {
        val bottom = l.height - NewsLayout.PAD
        for (r in l.rows) {
            assertTrue(r.y >= NewsLayout.PAD, "$label top ${r.kind}")
            assertTrue(r.h >= 0 && r.y + r.h <= bottom, "$label bottom ${r.kind} y=${r.y} h=${r.h} height=${l.height}")
        }
        val flow = l.rows.filter { it.kind == RowKind.TEXT || it.kind == RowKind.RULE || it.kind == RowKind.DOUBLE }
        for ((x, rows) in flow.groupBy { it.x }) rows.sortedBy { it.y }.zipWithNext { a, b -> assertTrue(b.y >= a.y + a.h, "$label overlap in column x=$x at y=${b.y}") }
        assertEquals(l.columns - 1, l.rows.count { it.kind == RowKind.VRULE }, label)
        for (r in texts(l)) for (p in r.pieces) assertTrue(p.x >= 0 && p.x + p.w <= r.w, "$label piece out of row: x=${p.x} w=${p.w} row.w=${r.w}")
    }

    private val everything = Doc(
        listOf(
            Block.Heading(1, listOf(Span("Big headline that is rather long"))),
            para(),
            Block.Heading(2, listOf(Span("Sub"))),
            Block.Quote(listOf(Span("a quoted line that goes on and on and on for a while"))),
            Block.Bullets(List(4) { listOf(Span("item number $it with some more words to wrap")) }),
            Block.Rule,
            para("centered text that is longer than a single line probably", centered = true),
            Block.Para(List(25) { Span("", ref = Ref.Item("minecraft:stone")) } + Span(" tail", bold = true)),
            Block.Para(listOf(Span("Mixed "), Span("bold", bold = true), Span(" and "), Span("red", color = Dye.RED), Span(" end"))),
            para("x".repeat(90))
        )
    )

    @Test
    fun invariantsHoldForEveryWidth() {
        val article = Article("A headline for the front page", "A subtitle", everything, "Nov 11, Ashland, Ann")
        for (w in listOf(-10, 0, 56, 100, 200, 266, 300, 435, 436, 500, 700, 1000)) {
            val l = NewsLayout.layout(article, w, m)
            assertEquals(w, l.width)
            check(l, "w=$w")
            check(NewsLayout.layout(Article(doc = everything), w, m), "doc only w=$w")
            check(NewsLayout.layout(Article(doc = Doc(listOf(para()) + everything.blocks)), w, m), "cap first w=$w")
        }
    }

    @Test
    fun columnThresholds() {
        assertEquals(1, NewsLayout.columnsFor(0))
        assertEquals(1, NewsLayout.columnsFor(249))
        assertEquals(2, NewsLayout.columnsFor(250))
        assertEquals(2, NewsLayout.columnsFor(419))
        assertEquals(3, NewsLayout.columnsFor(420))
        assertEquals(2, layout(435, para()).columns)
        assertEquals(3, layout(436, para()).columns)
        assertEquals(1, layout(-50, para()).columns)
    }

    @Test
    fun contentFlowsLeftToRight() {
        val l = layout(500, para(), para(), para(), para())
        val xs = texts(l).map { it.x }
        assertEquals(xs.sorted(), xs)
        assertEquals(3, xs.distinct().size)
        val colW = (500 - NewsLayout.PAD * 2 - NewsLayout.GAP * 2) / 3
        assertEquals(listOf(NewsLayout.PAD, NewsLayout.PAD + colW + NewsLayout.GAP, NewsLayout.PAD + 2 * (colW + NewsLayout.GAP)), xs.distinct())
    }

    @Test
    fun columnRulesSitInTheGutters() {
        val l = layout(500, para(), para(), para())
        val colW = (500 - NewsLayout.PAD * 2 - NewsLayout.GAP * 2) / 3
        val rules = l.rows.filter { it.kind == RowKind.VRULE }
        assertEquals(listOf(NewsLayout.PAD + colW + NewsLayout.GAP / 2, NewsLayout.PAD + 2 * (colW + NewsLayout.GAP) - NewsLayout.GAP / 2), rules.map { it.x })
        assertTrue(rules.all { it.w == 1 && it.y == NewsLayout.PAD && it.h == l.height - 2 * NewsLayout.PAD })
    }

    @Test
    fun headerStack() {
        val l = NewsLayout.layout(Article("T", "S", Doc(listOf(para("a"))), "G"), 200, m)
        val rows = l.rows
        assertEquals(listOf(8, 30, 42), texts(l).take(3).map { it.y })
        assertEquals(listOf(20, 10, 10), texts(l).take(3).map { it.h })
        val double = rows.first { it.kind == RowKind.DOUBLE }
        assertEquals(56, double.y)
        assertEquals(5, double.h)
        assertEquals(184, double.w)
        assertEquals(65, texts(l)[3].y)
        assertEquals(83, l.height)
        val t = texts(l)
        assertTrue(t[0].pieces.single().bold && t[0].pieces.single().scale == 2)
        assertTrue(t[1].pieces.single().italic && !t[1].pieces.single().muted)
        assertTrue(t[2].pieces.single().italic && t[2].pieces.single().muted)
    }

    @Test
    fun blankHeaderPartsAreIgnored() {
        val l = NewsLayout.layout(Article("  ", "\n", Doc(emptyList()), " "), 300, m)
        assertEquals(NewsLayout.PAD * 2, l.height)
        assertTrue(l.rows.all { it.kind == RowKind.VRULE })
        val sub = NewsLayout.layout(Article(subtitle = "S", doc = Doc(emptyList())), 300, m)
        assertEquals(10, texts(sub).single().y)
        val title = NewsLayout.layout(Article(title = "T", doc = Doc(emptyList())), 300, m)
        assertEquals(8, texts(title).single().y)
    }

    @Test
    fun headingLevels() {
        val h1 = layout(200, Block.Heading(1, listOf(Span("Hi"))))
        assertEquals(RowKind.TEXT, h1.rows[0].kind)
        assertTrue(h1.rows[0].pieces.all { it.bold })
        val rule = h1.rows.first { it.kind == RowKind.RULE }
        assertEquals(19, rule.y)
        assertEquals(1, rule.h)
        assertEquals(184, rule.w)
        assertEquals(28, h1.height)
        val h2 = layout(200, Block.Heading(2, listOf(Span("Hi"))))
        assertTrue(h2.rows.none { it.kind == RowKind.RULE })
        assertEquals(26, h2.height)
        val after = layout(200, para("a"), Block.Heading(2, listOf(Span("Hi"))))
        assertEquals(listOf(8, 24), texts(after).map { it.y })
    }

    @Test
    fun blockSpacing() {
        assertEquals(listOf(8, 22), texts(layout(200, para("a"), para("b"))).map { it.y })
        val bullets = layout(200, Block.Bullets(listOf(listOf(Span("one")), listOf(Span("two")))))
        assertEquals(listOf(8, 20), texts(bullets).map { it.y })
        val rule = layout(200, para("a"), Block.Rule).rows.first { it.kind == RowKind.DOUBLE }
        assertEquals(22, rule.y)
        assertEquals(5, rule.h)
    }

    @Test
    fun bulletsMarkOnlyTheFirstRowOfEachItem() {
        val item = listOf(Span("word ".repeat(30).trim()))
        val l = layout(200, Block.Bullets(listOf(item, item)))
        val rows = texts(l)
        val marked = rows.filter { r -> r.pieces.any { it.text == "•" } }
        assertEquals(2, marked.size)
        assertTrue(rows.size > 4)
        for (r in rows) {
            val body = r.pieces.filter { it.text != "•" }
            assertEquals(NewsLayout.BULLET_INDENT, body.first().x)
        }
        assertEquals(0, marked[0].pieces.first().x)
    }

    @Test
    fun quoteBarsMatchEveryTextRow() {
        val l = layout(200, Block.Quote(listOf(Span("quoted ".repeat(25).trim()))))
        val rows = texts(l)
        val bars = l.rows.filter { it.kind == RowKind.BAR }
        assertTrue(rows.size > 2)
        assertEquals(rows.size, bars.size)
        rows.zip(bars).forEach { (r, b) ->
            assertEquals(r.y, b.y)
            assertEquals(r.h, b.h)
            assertEquals(r.x, b.x)
            assertEquals(2, b.w)
        }
        assertTrue(rows.all { r -> r.pieces.all { it.italic && it.x >= NewsLayout.QUOTE_INDENT } })
    }

    @Test
    fun centeredShiftIsHalfTheSlack() {
        val l = layout(200, para("hi", centered = true))
        assertEquals((184 - 12) / 2, texts(l).single().pieces.single().x)
        val full = layout(200, para("x".repeat(30), centered = true))
        assertEquals(2, texts(full).single().pieces.single().x)
    }

    @Test
    fun dropCapGeometry() {
        val l = layout(200, Block.Para(listOf(Span(words, color = Dye.RED))))
        val cap = l.rows.single { it.kind == RowKind.CAP }
        val first = texts(l).first()
        assertEquals(first.y, cap.y)
        assertEquals(NewsLayout.PAD, cap.x)
        assertEquals(18, cap.w)
        assertEquals(24, cap.h)
        val piece = cap.pieces.single()
        assertEquals("w", piece.text)
        assertEquals(NewsLayout.CAP_SCALE, piece.scale)
        assertTrue(piece.bold)
        assertEquals(Dye.RED, piece.color)
        assertTrue(first.pieces.first().text.startsWith("ord0"))
        assertEquals(20, first.pieces.first().x)
        assertEquals(listOf(20, 20, 20, 0), texts(l).take(4).map { it.pieces.first().x })
        assertTrue(cap.y + cap.h <= texts(l)[2].y + texts(l)[2].h)
    }

    @Test
    fun dropCapNeedsLengthLettersAndNoRefFirst() {
        assertEquals(0, layout(200, para("ab ".repeat(19) + "ab")).rows.count { it.kind == RowKind.CAP })
        assertEquals(1, layout(200, para("ab ".repeat(20))).rows.count { it.kind == RowKind.CAP })
        assertEquals(0, layout(200, para("1" + words)).rows.count { it.kind == RowKind.CAP })
        val spans = listOf(Span("", ref = Ref.Item("minecraft:stone")), Span(words))
        assertEquals(0, layout(200, Block.Para(spans)).rows.count { it.kind == RowKind.CAP })
        assertEquals(0, layout(200, Block.Quote(listOf(Span(words)))).rows.count { it.kind == RowKind.CAP })
        assertEquals(0, layout(200, Block.Heading(2, listOf(Span(words)))).rows.count { it.kind == RowKind.CAP })
    }

    @Test
    fun refWidths() {
        val none = NewsData()
        assertEquals(12 + 2 + 12, NewsLayout.refWidth(Ref.Flag("de"), none, m))
        assertEquals(8 + 2 + 30, NewsLayout.refWidth(Ref.Head("Steve"), none, m))
        assertEquals(10 + 2 + 30, NewsLayout.refWidth(Ref.Item("minecraft:stone"), none, m))
        assertEquals(12, NewsLayout.refWidth(Ref.Icon("star"), none, m))
        assertEquals(10 + 2 + 60 + 3 + 18, NewsLayout.refWidth(Ref.Price("minecraft:iron_ingot"), none, m))
        val stamped = NewsData(price = { PriceStamp("12.5", "Nov 11") })
        assertEquals(10 + 2 + 60 + 3 + 36 + 3 + 27, NewsLayout.refWidth(Ref.Price("minecraft:iron_ingot"), stamped, m))
        val undated = NewsData(price = { PriceStamp("12.5", "") })
        assertEquals(10 + 2 + 60 + 3 + 36, NewsLayout.refWidth(Ref.Price("minecraft:iron_ingot"), undated, m))
        val named = NewsData(item = { "Iron Ingot" }, country = { "Ashland" })
        assertEquals(10 + 2 + 60, NewsLayout.refWidth(Ref.Item("minecraft:iron_ingot"), named, m))
        assertEquals(12 + 2 + 42, NewsLayout.refWidth(Ref.Flag("de"), named, m))
    }

    @Test
    fun refsSitBetweenTextPieces() {
        val spans = listOf(Span("buy "), Span("", ref = Ref.Flag("de")), Span(" now"))
        val l = NewsLayout.layout(Article(doc = Doc(listOf(Block.Para(spans)))), 200, m)
        val p = texts(l).single().pieces
        assertEquals(3, p.size)
        assertEquals("buy ", p[0].text)
        assertEquals(0, p[0].x)
        assertEquals(24, p[0].w)
        assertEquals(Ref.Flag("de"), p[1].ref)
        assertEquals(24, p[1].x)
        assertEquals(28, p[1].w)
        assertEquals(" now", p[2].text)
        assertEquals(52, p[2].x)
    }

    @Test
    fun manyRefsWrapWholeAndNeverOverflow() {
        val refs = List(30) { Span("", ref = Ref.Item("minecraft:stone")) }
        val l = layout(200, Block.Para(refs))
        val rows = texts(l)
        assertTrue(rows.size >= 3)
        assertEquals(30, rows.sumOf { it.pieces.size })
        for (r in rows) {
            assertTrue(r.pieces.size <= 13)
            assertTrue(r.pieces.all { it.ref != null && it.w == 44 && it.x + it.w <= 184 })
        }
        check(l, "refs")
    }

    @Test
    fun spanStylesSplitPieces() {
        val l = layout(200, Block.Para(listOf(Span("a "), Span("b", bold = true), Span(" c", color = Dye.RED))))
        val p = texts(l).single().pieces
        assertEquals(listOf("a ", "b", " c"), p.map { it.text })
        assertEquals(listOf(false, true, false), p.map { it.bold })
        assertEquals(listOf(null, null, Dye.RED), p.map { it.color })
        assertEquals(listOf(0, 12, 18), p.map { it.x })
    }

    @Test
    fun whitespaceAndEmptyParagraphs() {
        assertEquals(NewsLayout.PAD * 2, layout(200, Block.Para(emptyList())).height)
        val blank = layout(200, para("   "))
        assertEquals(1, texts(blank).size)
        assertEquals(26, blank.height)
        check(blank, "blank")
    }

    @Test
    fun longWordsKeepEveryCharacter() {
        val l = layout(100, para("x".repeat(80)))
        assertEquals(80, l.rows.filter { it.kind == RowKind.TEXT || it.kind == RowKind.CAP }.sumOf { r -> r.pieces.sumOf { it.text.length } })
        check(l, "long")
    }

    @Test
    fun emojiAreNotSplitAcrossRows() {
        val wide = TextMeasure { text, _ -> text.length * 12 }
        val l = NewsLayout.layout(Article(doc = Doc(listOf(para("😀".repeat(40))))), 100, wide)
        for (r in texts(l)) for (p in r.pieces) {
            assertFalse(p.text.last().isHighSurrogate(), "row ends inside a surrogate pair: ${p.text}")
            assertFalse(p.text.first().isLowSurrogate(), "row starts inside a surrogate pair: ${p.text}")
        }
    }

}
