package kami.libs.ui.press

import kami.libs.press.Block
import kami.libs.press.Doc
import kami.libs.press.Ref
import kami.libs.press.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorLogicTest {
    private val m = TextMeasure { text, _ -> text.length * 6 }

    @Test
    fun enterContinuesListsAndQuotes() {
        val b = EditBuffer("- one")
        assertTrue(b.enter())
        assertEquals("- one\n- ", b.text)
        b.insert("two")
        b.enter()
        assertEquals("- one\n- two\n- ", b.text)
        b.enter()
        assertEquals("- one\n- two\n", b.text)
        val q = EditBuffer("> said")
        q.enter()
        assertEquals("> said\n> ", q.text)
    }

    @Test
    fun enterElsewhereIsPlain() {
        val h = EditBuffer("# Title")
        h.enter()
        assertEquals("# Title\n", h.text)
        val b = EditBuffer("- item")
        b.move(1, false)
        b.enter()
        assertEquals("-\n item", b.text)
        val s = EditBuffer("- ab")
        s.selectAll()
        s.enter()
        assertEquals("\n", s.text)
    }

    @Test
    fun enterOnEmptyItemUndoes() {
        val b = EditBuffer("a\n- ")
        b.enter()
        assertEquals("a\n", b.text)
        assertEquals(2, b.cursor)
        assertTrue(b.undo())
        assertEquals("a\n- ", b.text)
    }

    @Test
    fun smartPasteConvertsMarkdownAndHtml() {
        assertEquals("**bold** and x", Paste.smart("__bold__ and <b>x</b>"))
        assertEquals("- a\n- b\n- c", Paste.smart("* a\n+ b\n1. c"))
        assertEquals("text and pic", Paste.smart("[text](http://x.y) and ![pic](http://i.png)"))
        assertEquals("a\n\nb", Paste.smart("a<br>\n\n\n\n<p>b</p>"))
        assertEquals("code", Paste.smart("```kotlin\n`code`\n```"))
        assertEquals("---", Paste.smart("***"))
        assertEquals("# Head\n> quote", Paste.smart("# Head\r\n> quote"))
        assertEquals("a b", Paste.smart("a\t​ b"))
        assertEquals("1 < 2 & 3", Paste.smart("1 &lt; 2 &amp; 3"))
    }

    @Test
    fun chipSpecsResolveFromData() {
        val data = NewsData(
            flag = { FlagLook(1, 2, 3, 4) }, price = { PriceStamp("12.5", "Nov 11") }, head = { "Steve" }, country = { "Ashland" },
            item = { "Iron Ingot" }
        )
        val price = Chips.spec(Ref.Price("minecraft:iron_ingot"), data)
        assertEquals("Iron Ingot", price.label)
        assertEquals("12.5 $SPUR", price.value)
        assertEquals("Nov 11", price.note)
        assertTrue(price.known && !price.valueMuted)
        val flag = Chips.spec(Ref.Flag("de"), data)
        assertEquals("Ashland", flag.label)
        assertEquals(3, flag.look?.emblem)
        assertEquals("Steve", Chips.spec(Ref.Head("abc"), data).label)
        assertEquals("Iron Ingot", Chips.spec(Ref.Item("minecraft:iron_ingot"), data).label)
        assertEquals("", Chips.spec(Ref.Icon("star"), data).label)
    }

    @Test
    fun unresolvedChipsAreMutedWithRawNames() {
        val price = Chips.spec(Ref.Price("minecraft:iron_ingot"), NoNewsData)
        assertEquals("iron ingot", price.label)
        assertEquals("n/a", price.value)
        assertTrue(!price.known && price.valueMuted)
        val head = Chips.spec(Ref.Head("0123456789abcdefXYZ"), NoNewsData)
        assertEquals("0123456789abcdef", head.label)
        assertFalse(head.known)
        assertFalse(Chips.spec(Ref.Flag("zz"), NoNewsData).known)
        assertEquals("zz", Chips.spec(Ref.Flag("zz"), NoNewsData).label)
        assertEquals("a".repeat(23) + "…", Chips.spec(Ref.Item("x:" + "a".repeat(40)), NoNewsData).label)
    }

    @Test
    fun chipSpecFromToken() {
        assertEquals("iron ingot", Chips.spec("{price:minecraft:iron_ingot}", NoNewsData)?.label)
        assertNull(Chips.spec("plain", NoNewsData))
    }

    @Test
    fun chipWidthMatchesParts() {
        val spec = Chips.spec(Ref.Price("minecraft:iron_ingot"), NewsData(price = { PriceStamp("12.5", "") }))
        assertEquals(6 + 10 + 2 + 60 + 3 + 36, Chips.width(spec, m, 3, false))
    }

    @Test
    fun trailingSpaceAndWideTokenKeepTheEndReachable() {
        val prefix = { t: String -> IntArray(t.length + 1) { it * 6 } }
        val spaced = "aaaa "
        assertEquals(listOf(false), Wrap.lines(spaced, 24, prefix(spaced)).map { it.soft })
        val wide = "{price:minecraft:iron_ingot}"
        val atoms = Atoms.find(wide)
        val segs = Wrap.lines(wide, 10, IntArray(wide.length + 1) { if (it == 0) 0 else 100 }, atoms)
        assertEquals(listOf(0 to wide.length), segs.map { it.start to it.end })
        assertEquals(wide.length, Wrap.lastPos(segs.single()))
    }

    @Test
    fun longWordSplitsOnCodePoints() {
        val word = "😀".repeat(8)
        val l = NewsLayout.layout(Article(doc = Doc(listOf(Block.Para(listOf(Span(word)))))), 56, TextMeasure { t, _ -> t.length * 3 })
        val rows = l.rows.filter { it.kind == RowKind.TEXT }
        assertTrue(rows.size > 1)
        for (row in rows) for (p in row.pieces) {
            assertFalse(p.text.isNotEmpty() && p.text.last().isHighSurrogate())
            assertFalse(p.text.isNotEmpty() && p.text.first().isLowSurrogate())
        }
        assertEquals(word, rows.joinToString("") { r -> r.pieces.joinToString("") { it.text } })
    }
}
