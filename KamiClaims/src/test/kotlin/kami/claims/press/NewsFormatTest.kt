package kami.claims.press

import kami.libs.press.Markup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewsFormatTest {
    @Test
    fun splitsAtLineBoundaries() {
        val parts = NewsFormat.split("aaaa\nbbbb\ncccc", 9)
        assertEquals(listOf("aaaa\nbbbb", "cccc"), parts)
    }

    @Test
    fun hardCutsOversizedLine() {
        val parts = NewsFormat.split("x".repeat(25), 10)
        assertEquals(listOf(10, 10, 5), parts.map { it.length })
    }

    @Test
    fun hardCutKeepsSurrogatePairs() {
        val text = "a" + "😀".repeat(8)
        val parts = NewsFormat.split(text, 6)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }

    @Test
    fun hardCutKeepsBoldBalanced() {
        val parts = NewsFormat.split("**" + "w".repeat(30) + "**", 12)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { (it.split("**").size - 1) % 2 == 0 })
        assertTrue(parts.all { it.length <= 12 })
    }

    @Test
    fun plainTitleEscapesLinks() {
        val text = NewsFormat.plain("[x](https://evil.example)")
        assertTrue("\\[" in text && "://" !in text)
    }

    @Test
    fun titleFirstFooterLast() {
        val body = Markup.parse((1..6).joinToString("\n\n") { "p$it " + "w".repeat(40) })
        val list = NewsFormat.embeds("Title", "Sub", body, "Land · Ann", 1000, 100)
        assertTrue(list.size > 1)
        assertEquals("Title", list.first().title)
        assertNull(list.first().footer)
        assertEquals("Land · Ann", list.last().footer)
        assertTrue(list.last().timestamp != null)
        assertTrue(list.drop(1).all { it.title == null })
        assertTrue(list.dropLast(1).all { it.footer == null && it.timestamp == null })
        assertTrue(list.all { it.text.length <= 100 })
        assertTrue(list.first().text.startsWith("*Sub*"))
    }

    @Test
    fun refsBecomeReadableText() {
        val doc = Markup.parse("Now {price:minecraft:iron_ingot} and {flag:c1} by {head:u1}")
        val snaps = listOf(RefSnap("price", "minecraft:iron_ingot", 12), RefSnap("flag", "c1", name = "Land"), RefSnap("head", "u1", name = "Ann"))
        val text = NewsFormat.embeds("T", "", NewsFormat.resolve(doc, snaps), "f", 0).single().text
        assertTrue("Iron Ingot: 12 spur" in text)
        assertTrue("Land" in text && "Ann" in text)
    }

    @Test
    fun mentionsAreNeutralised() {
        val text = NewsFormat.room("T @everyone", "", Markup.parse("hi @here https://x.y"))
        assertTrue("@everyone" !in text && "@here" !in text && "https://" !in text)
    }

    @Test
    fun splitDropsBlankAndKeepsShortText() {
        assertEquals(emptyList(), NewsFormat.split("", 10))
        assertEquals(listOf("abc"), NewsFormat.split("abc", 10))
    }

    @Test
    fun splitPartsRespectLimitAndKeepContent() {
        val text = (1..40).joinToString("\n") { "line$it" }
        val parts = NewsFormat.split(text, 50)
        assertTrue(parts.all { it.length <= 50 })
        assertEquals(text, parts.joinToString("\n"))
    }

    @Test
    fun emptyBodyStillYieldsOneEmbed() {
        val list = NewsFormat.embeds("T", "", Markup.parse(""), "f", 5)
        assertEquals(1, list.size)
        assertEquals("T", list.single().title)
        assertEquals("f", list.single().footer)
        assertTrue(list.single().timestamp != null)
    }

    @Test
    fun singleEmbedCarriesTitleFooterAndTimestamp() {
        val list = NewsFormat.embeds("T", "", Markup.parse("short"), "f", 5)
        assertEquals(1, list.size)
        assertEquals(NewsFormat.COLOR, list.single().color)
        assertEquals(5L, list.single().timestamp!!.toEpochMilli())
        assertTrue(list.single().text.startsWith("short"))
    }

    @Test
    fun noSubtitleMeansNoItalicHead() {
        assertTrue(!NewsFormat.embeds("T", "", Markup.parse("body"), "f", 0).single().text.startsWith("*"))
    }

    @Test
    fun missingSnapshotsFallBackToNames() {
        val doc = Markup.parse("{price:minecraft:iron_ingot} {flag:c1} {item:minecraft:oak_log}")
        val text = NewsFormat.embeds("T", "", NewsFormat.resolve(doc, emptyList()), "f", 0).single().text
        assertTrue("Iron Ingot" in text && "spur" !in text)
        assertTrue("c1" in text)
        assertTrue("Oak Log" in text)
    }

    @Test
    fun roomIsBoundedAndStartsWithTitle() {
        val text = NewsFormat.room("Title", "Sub", Markup.parse("x".repeat(5000)))
        assertTrue(text.length <= 1000)
        assertTrue(text.startsWith("**Title**"))
        assertTrue("*Sub*" in text)
    }

    @Test
    fun roomSummaryIsSingleLineAndShort() {
        val text = NewsFormat.room("T", "", Markup.parse("a ".repeat(400) + "\n\nsecond paragraph"))
        assertTrue("second paragraph" !in text)
    }

    @Test
    fun embedsNeutraliseMentionsOnlyAsPlainTitle() {
        val text = NewsFormat.plain("@everyone <@123> https://x.y")
        assertTrue("@everyone" !in text && "https://" !in text)
    }
}
