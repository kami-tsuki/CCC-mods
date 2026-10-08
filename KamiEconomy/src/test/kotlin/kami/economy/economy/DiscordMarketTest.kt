package kami.economy.economy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordMarketTest {
    @Test
    fun crossedNeedsThresholdAndChange() {
        assertTrue(crossed(100, 110, 10.0))
        assertTrue(crossed(100, 90, 10.0))
        assertFalse(crossed(100, 109, 10.0))
        assertFalse(crossed(100, 100, 0.1))
        assertFalse(crossed(0, 50, 10.0))
    }

    @Test
    fun dueNeedsInterval() {
        assertFalse(due(0, 59 * 60_000L, 60))
        assertTrue(due(0, 60 * 60_000L, 60))
        assertFalse(due(0, Long.MAX_VALUE, 0))
    }

    @Test
    fun moversSplitAndLimit() {
        val rows = listOf("a" to 50, "b" to -30, "c" to 0, "d" to 120, "e" to -80, "f" to 10)
        val (gain, lose) = movers(rows, 2)
        assertEquals(listOf("d", "a"), gain.map { it.first })
        assertEquals(listOf("e", "b"), lose.map { it.first })
    }

    @Test
    fun langKeysAreNotNames() {
        assertEquals(null, realName("item.minecraft.stick", "item.minecraft.stick"))
        assertEquals(null, realName("block.x.y", "other"))
        assertEquals("Stick", realName("Stick", "item.minecraft.stick"))
    }

    @Test
    fun duplicateNamesGetModId() {
        val out = labels(listOf("a:ore", "b:ore", "a:gem")) { if (it.endsWith("ore")) "Ore" else "Gem" }
        assertEquals(listOf("Ore (a)", "Ore (b)", "Gem"), out.map { it.second })
    }

    @Test
    fun prettyIds() {
        assertEquals("Iron Ingot", DiscordMarket.pretty("minecraft:iron_ingot"))
    }
}
