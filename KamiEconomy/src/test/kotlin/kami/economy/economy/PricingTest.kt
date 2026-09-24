package kami.economy.economy

import kami.economy.Book
import kami.economy.Config
import kami.economy.EndlessItem
import kami.economy.Fill
import kami.economy.Market
import kami.economy.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class PricingTest {
    private fun reset() {
        Market.data = kami.economy.Data()
        Config.s = Settings()
    }

    @Test
    fun midPriceMovesTowardWeightedFillsButIsBounded() {
        reset()
        Config.s = Settings(maxPriceMovePct = 0.10)
        val book = Market.book("test:item")
        book.midPrice = 100.0
        book.recentFills += Fill(200, 10, 0)
        Pricing.tick()
        assertEquals(110.0, book.midPrice, 0.001)
        assertTrue(book.recentFills.isEmpty())
    }

    @Test
    fun midPriceUnchangedWithNoFillsThisWindow() {
        reset()
        val book = Market.book("test:item")
        book.midPrice = 42.0
        Pricing.tick()
        assertEquals(42.0, book.midPrice, 0.001)
    }

    @Test
    fun synthPriceStaysAboveRealMidPrice() {
        reset()
        Config.s = Settings(endlessSupply = listOf(EndlessItem("test:wood", 6, 1.25)))
        val book = Market.book("test:wood")
        book.sells += kami.economy.Order(1, "", 6, Int.MAX_VALUE, synthetic = true)
        book.midPrice = 20.0
        Pricing.tick()
        val synth = book.sells.first { it.synthetic }
        assertEquals(25, synth.price)
    }

    @Test
    fun noCandleRecordedWhenNothingTradedThisTick() {
        reset()
        val item = "test:idle-${System.nanoTime()}"
        Market.book(item).midPrice = 50.0
        Pricing.tick()
        assertTrue(History.of(item, Resolution.RAW, 10).isEmpty())
    }

    @Test
    fun candleRecordedOnlyWhenTradesHappened() {
        reset()
        val item = "test:active-${System.nanoTime()}"
        val book = Market.book(item)
        book.midPrice = 50.0
        book.recentFills += Fill(55, 3, 0)
        Pricing.tick()
        val history = History.of(item, Resolution.RAW, 10)
        assertEquals(1, history.size)
        assertEquals(3L, history.first().volume)
        assertFalse(book.recentFills.isNotEmpty())
    }

    @Test
    fun synthPriceNeverBelowFloor() {
        reset()
        Config.s = Settings(endlessSupply = listOf(EndlessItem("test:wood", 6, 1.25)))
        val book = Market.book("test:wood")
        book.sells += kami.economy.Order(1, "", 6, Int.MAX_VALUE, synthetic = true)
        Pricing.tick()
        val synth = book.sells.first { it.synthetic }
        assertTrue(synth.price >= 6)
    }
}
