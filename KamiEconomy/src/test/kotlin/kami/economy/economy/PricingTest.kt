package kami.economy.economy

import kami.economy.Book
import kami.economy.Config
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
    fun noCandleRecordedWhenNothingTradedThisTick() {
        reset()
        val item = "test:idle-${System.nanoTime()}"
        Market.book(item).midPrice = 50.0
        Pricing.tick()
        assertTrue(History.series(item, Range.DAY, System.currentTimeMillis(), 0.0).buckets.isEmpty())
    }

    @Test
    fun candleRecordedOnlyWhenTradesHappened() {
        reset()
        val item = "test:active-${System.nanoTime()}"
        val book = Market.book(item)
        book.midPrice = 50.0
        book.recentFills += Fill(55, 3, 0)
        Pricing.tick()
        val history = History.series(item, Range.DAY, System.currentTimeMillis(), 0.0).buckets
        assertEquals(1, history.size)
        assertEquals(3L, history.first().volume)
        assertFalse(book.recentFills.isNotEmpty())
    }

    @Test
    fun valueUsesTheBasePrice() {
        Market.data = kami.economy.Data()
        Config.s = Settings(starterGoods = listOf(kami.economy.StarterGood("test:iron_ingot", 40)))
        Stocks.index { emptyList() }
        assertEquals(40L, EconomyProvider.valueOf("test:iron_ingot", 64))
        assertEquals(20L, EconomyProvider.valueOf("test:iron_ingot", 32))
        assertEquals(0L, EconomyProvider.valueOf("test:unknown", 8))
    }
}
