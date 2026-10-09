package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Fill
import kami.economy.Market
import kami.economy.Settings
import kami.economy.StarterGood
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PricingTest {
    private val item = "test:oak_log"

    private fun reset() {
        Market.data = Data()
        Config.s = Settings(starterGoods = listOf(StarterGood(item, 20, 64, 32, 100, 100)))
        Stocks.index { emptyList() }
    }

    @Test
    fun serverFillsMoveTheCourseInClosedForm() {
        reset()
        val step = 0.05 / 33
        Pricing.observe(item, 21.0, 2, server = true, buy = true)
        assertEquals(20.0 * (1 + step).pow(2), Stocks.course(item), 1e-9)
        val up = Stocks.course(item)
        Pricing.observe(item, 19.0, 3, server = true, buy = false)
        assertEquals(up * (1 - step).pow(3), Stocks.course(item), 1e-9)
    }

    @Test
    fun playerFillsAreClippedToTheCourseBand() {
        reset()
        Pricing.observe(item, 1000.0, 32, server = false, buy = true)
        assertEquals(20.5, Stocks.course(item), 1e-9)
        reset()
        Pricing.observe(item, 1.0, 32, server = false, buy = false)
        assertEquals(19.5, Stocks.course(item), 1e-9)
    }

    @Test
    fun zeroLotsDoNotMoveTheCourse() {
        reset()
        Pricing.observe(item, 50.0, 0, server = false, buy = true)
        assertEquals(20.0, Stocks.course(item), 1e-9)
    }

    @Test
    fun noCandleRecordedWhenNothingTradedThisTick() {
        reset()
        val idle = "test:idle"
        Market.book(idle).course = 50.0
        Pricing.tick()
        assertTrue(History.series(idle, Range.DAY, System.currentTimeMillis(), 0.0).buckets.isEmpty())
    }

    @Test
    fun candleRecordedOnlyWhenTradesHappened() {
        reset()
        val active = "test:active"
        val book = Market.book(active)
        book.course = 50.0
        book.recentFills += Fill(55, 3, 0)
        Pricing.tick()
        val history = History.series(active, Range.DAY, System.currentTimeMillis(), 0.0).buckets
        assertEquals(1, history.size)
        assertEquals(3L, history.first().volume)
        assertFalse(book.recentFills.isNotEmpty())
    }

    @Test
    fun valueUsesTheBasePrice() {
        Market.data = Data()
        Config.s = Settings(starterGoods = listOf(StarterGood("test:iron_ingot", 40)))
        Stocks.index { emptyList() }
        assertEquals(40L, EconomyProvider.valueOf("test:iron_ingot", 64))
        assertEquals(20L, EconomyProvider.valueOf("test:iron_ingot", 32))
        assertEquals(0L, EconomyProvider.valueOf("test:unknown", 8))
    }
}
