package kami.libs.press

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PressTest {
    private fun spans(text: String) = (Markup.parse(text).blocks.single() as Block.Para).spans

    @Test
    fun blockTokens() {
        val doc = Markup.parse("# One\n## Two\n### Three\n> said\n- a\n* b\n---\n^ mid\nplain")
        assertEquals(
            listOf(
                Block.Heading(1, listOf(Span("One"))), Block.Heading(2, listOf(Span("Two"))), Block.Heading(2, listOf(Span("Three"))),
                Block.Quote(listOf(Span("said"))), Block.Bullets(listOf(listOf(Span("a")), listOf(Span("b")))), Block.Rule,
                Block.Para(listOf(Span("mid")), true), Block.Para(listOf(Span("plain")))
            ),
            doc.blocks
        )
    }

    @Test
    fun inlineTokens() {
        assertEquals(listOf(Span("a "), Span("b", bold = true), Span(" "), Span("c", italic = true), Span(" "), Span("d", italic = true)), spans("a **b** *c* _d_"))
        assertEquals(listOf(Span("x", color = Dye.RED)), spans("{c:red}x{/c}"))
    }

    @Test
    fun colourNestingInnermostWins() {
        assertEquals(listOf(Span("a", color = Dye.RED), Span("b", color = Dye.BLUE), Span("c", color = Dye.RED)), spans("{c:red}a{c:blue}b{/c}c{/c}"))
        assertEquals(listOf(Span("a", color = Dye.RED), Span("b", color = Dye.RED)), spans("{c:red}a{c:nope}b{/c}"))
    }

    @Test
    fun refTokens() {
        val ids = "{price:minecraft:iron_ingot}{flag:abc-1}{head:123e4567-e89b-12d3-a456-426614174000}{item:minecraft:stone}{icon:coin}"
        assertEquals(
            listOf(Ref.Price("minecraft:iron_ingot"), Ref.Flag("abc-1"), Ref.Head("123e4567-e89b-12d3-a456-426614174000"), Ref.Item("minecraft:stone"), Ref.Icon("coin")),
            Markup.refs(Markup.parse(ids))
        )
        assertEquals(Ref.Price("a"), spans("{c:red}{price:a}{/c}").single().ref)
        assertEquals(Dye.RED, spans("{c:red}{price:a}{/c}").single().color)
    }

    @Test
    fun unknownStaysLiteral() {
        assertEquals("{foo:bar} {item:Bad Id} {price:x y}", (spans("{foo:bar} {item:Bad Id} {price:x y}")).single().text)
        assertTrue(Markup.refs(Markup.parse("{item:../../x?}")).isEmpty())
    }

    @Test
    fun unclosedTokensTolerated() {
        assertEquals("**a _b", spans("**a _b").single().text)
        assertEquals(listOf(Span("a", color = Dye.GREEN)), spans("{c:green}a"))
        assertEquals("a", spans("a{/c}").single().text)
    }

    @Test
    fun markdownImports() {
        assertEquals(listOf(Block.Heading(2, listOf(Span("deep")))), Markup.parse("###### deep").blocks)
        assertEquals("see docs and pic", spans("see [docs](http://x.y) and ![pic](http://i.png)").single().text)
        assertEquals("hi", spans("<b>hi</b>").single().text)
        assertEquals("| a | b |", spans("| a | b |").single().text)
    }

    @Test
    fun cleanStripsAndCaps() {
        assertEquals("ab\ncd", Markup.clean("  a\u0000b\r\nc​d  ", 100))
        assertEquals("abc", Markup.clean("abcdef", 3))
    }

    @Test
    fun parserIsTotal() {
        val rnd = Random(7)
        val pool = "{}[]()*_#>-^!\\<>/:\n \tcprice flag head item icon red blue /c abc01 `|~".toList()
        repeat(3000) {
            val text = buildString { repeat(rnd.nextInt(0, 120)) { append(if (rnd.nextInt(10) == 0) rnd.nextInt(0, 0x2FFF).toChar() else pool[rnd.nextInt(pool.size)]) } }
            val doc = Markup.parse(text)
            Markup.length(doc)
            Markup.refs(doc)
            doc.toDiscord()
            doc.toPlain()
            PressCost.of("t", "s", doc)
        }
    }

    @Test
    fun specNumbers() {
        val small = Markup.parse("Hello world")
        val base = PressCost.of("T", "", small)
        assertEquals(2, base.paper)
        assertEquals(3, base.ink)
        val sub = PressCost.of("T", "S", small)
        assertEquals(3, sub.paper)
        assertEquals(5, sub.ink)
        assertTrue(PressCost.of("T", "", Markup.parse("{c:black}Hello{/c} {c:white}world{/c}")).dyes.isEmpty())
        assertEquals(mapOf(Dye.RED to 1), PressCost.of("T", "", Markup.parse("{c:red}Hello{/c}")).dyes)
        assertEquals(mapOf(Dye.RED to 2), PressCost.of("T", "", Markup.parse("{c:red}${"x".repeat(151)}{/c}")).dyes)
        assertEquals(mapOf(Dye.RED to 1), PressCost.of("T", "", Markup.parse("{c:red}{item:a}{/c}")).dyes)
    }

    @Test
    fun costIsMonotonic() {
        val steps = listOf("text", " more text", "\n# head", "\n> q", "\n- b", "\n---", "\n**b**", "\n_i_", "\n{c:red}red{/c}", "\n{c:blue}b{/c}", "\n{price:a}", "\n" + "y".repeat(600))
        var text = ""
        var last = PressCost.of("t", "", Markup.parse(text))
        for (s in steps) {
            text += s
            val now = PressCost.of("t", "", Markup.parse(text))
            assertTrue(now.paper >= last.paper && now.ink >= last.ink, text)
            last.dyes.forEach { (d, n) -> assertTrue((now.dyes[d] ?: 0) >= n, text) }
            last = now
        }
        assertTrue(last.paper > 2)
    }

    @Test
    fun discordNeutralisesMentionsAndLinks() {
        val out = Markup.parse("@everyone @here <@123> <@&9> https://evil.example [x](http://y.z) **ok**").toDiscord()
        assertFalse("@everyone" in out)
        assertFalse("@here" in out)
        assertFalse("<@123>" in out)
        assertTrue("\\<@" in out)
        assertFalse("https://" in out)
        assertFalse("http://" in out)
        assertTrue("**ok**" in out)
    }

    @Test
    fun discordKeepsEmphasisAndEscapesLineStarts() {
        val doc = Markup.parse("# T\n**b** and _i_\n- x\n1. y")
        assertEquals("# T\n**b** and *i*\n- x\n1\\. y", doc.toDiscord())
        assertEquals("T\nb and i\n- x\n1. y", doc.toPlain())
    }

    @Test
    fun pathologicalLinesParseFast() {
        listOf("[".repeat(5000), "![".repeat(2500), "[a](".repeat(1250), "<a".repeat(2500), "*a".repeat(2500)).forEach { text ->
            Markup.parse(text)
            val start = System.nanoTime()
            Markup.parse(text)
            assertTrue(System.nanoTime() - start < 50_000_000L)
        }
    }

    @Test
    fun linksStillReduceToTheirLabel() {
        assertEquals("see docs and pic", spans("see [docs](http://x.y) and ![pic](a.png)").joinToString("") { it.text })
    }

    @Test
    fun lineCleaningCollapsesWhitespaceAndStripsSectionSigns() {
        assertEquals("A B cC", Markup.cleanLine("  A\n\r\t B\u0007  \u00A7cC ", 20))
        assertEquals("abc", Markup.cleanLine("abcdef", 3))
        assertEquals("a b", Markup.cleanLine("a b c", 3))
        assertEquals("credtext", Markup.clean("\u00A7credtext", 20))
    }
}
