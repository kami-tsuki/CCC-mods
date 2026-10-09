package kami.libs.economy

import kami.libs.LibConfig
import kami.libs.LibSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CoinsCompactTest {
    private val spur = Coins.glyph(1)
    private val cog = Coins.glyph(64)

    @AfterTest
    fun reset() {
        LibConfig.file.value = LibSettings()
    }

    private fun parts(spurs: Long) = Coins.compactParts(spurs).let { it.amount to it.glyph }

    @Test
    fun defaultDenominationsPickTheLargestFittingCoin() {
        LibConfig.file.value = LibSettings()
        assertEquals("5" to spur, parts(5))
        assertEquals("1.0" to cog, parts(64))
        assertEquals("-1.0" to cog, parts(-64))
    }

    @Test
    fun zeroDenominationsAreIgnored() {
        LibConfig.file.value = LibSettings(coins = linkedMapOf("x" to 0))
        assertEquals("7" to spur, parts(7))
    }
}
