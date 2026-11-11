package kami.libs.discord

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscordFormatTest {
    private fun numFull(n: Long) = String.format(Locale.ROOT, "%,d", n)

    @Test
    fun moneyIsCompactAndExactVariantKeepsDigits() {
        assertEquals("999 ¤", DiscordFormat.money(999))
        assertEquals("1.2k ¤", DiscordFormat.money(1_234))
        assertEquals("1,234 ¤", DiscordFormat.moneyFull(1_234))
        assertEquals("+1.2k ¤", DiscordFormat.signed(1_234))
        assertEquals("-300 ¤", DiscordFormat.signed(-300))
    }

    @Test
    fun eachScalesDecimals() {
        assertEquals("1.5 ¤", DiscordFormat.each(1.5))
        assertEquals("3 ¤", DiscordFormat.each(3.0))
        assertEquals("12.3 ¤", DiscordFormat.each(12.3))
        assertEquals("1,234 ¤", DiscordFormat.each(1_234.0))
        assertEquals("12.3k ¤", DiscordFormat.each(12_345.0))
        assertEquals("0.0012 ¤", DiscordFormat.each(0.0012))
    }

    @Test
    fun numbersAndPercent() {
        assertEquals("1.2M", DiscordFormat.num(1_234_567))
        assertEquals("1,234,567", numFull(1_234_567))
        assertEquals("12.3%", DiscordFormat.pct(12.3))
        assertEquals("5%", DiscordFormat.pct(5.0))
    }

    @Test
    fun durationUsesTwoUnits() {
        assertEquals("12m", DiscordFormat.duration(12 * 60_000L))
        assertEquals("3h 5m", DiscordFormat.duration(185 * 60_000L))
        assertEquals("2d 4h", DiscordFormat.duration((2 * 1_440 + 4 * 60 + 9) * 60_000L))
    }

    @Test
    fun playersAreCodedAndCapped() {
        assertEquals("`a' b`", DiscordFormat.player("a`\n b"))
        assertEquals("`a`, `b` +1", DiscordFormat.players(listOf("a", "b", "c"), 2))
    }

    @Test
    fun fieldsDropBlanksAndCompactCapsFour() {
        val fields = DiscordFormat.fields("a" to "1", null, "b" to " ", "c" to "3")
        assertEquals(listOf("a", "c"), fields.map { it.name })
        assertEquals(true, fields.all { it.inline })
        val many = (1..6).map { EmbedField("f$it", "v") }
        assertEquals(4, Card.compact("t", fields = many).fields.size)
    }
}
