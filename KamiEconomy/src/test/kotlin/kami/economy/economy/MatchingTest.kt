package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.libs.claims.ClaimsApi
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchingTest {
    private fun reset() {
        Market.data = kami.economy.Data()
    }

    @Test
    fun buysCheapestFirst() {
        reset()
        Matching.insertSell("test:item", Order(1, "a", 5, 10))
        Matching.insertSell("test:item", Order(2, "b", 3, 10))
        Matching.insertSell("test:item", Order(3, "c", 4, 10))
        val plan = Matching.plan("test:item", 15)
        assertEquals(15, plan.filled)
        assertEquals(3 * 10 + 4 * 5, plan.totalSpurs)
        assertEquals(listOf(2L, 3L), plan.fills.map { it.orderId })
    }

    @Test
    fun partialFillWhenBookExhausted() {
        reset()
        Matching.insertSell("test:item", Order(1, "a", 5, 4))
        val plan = Matching.plan("test:item", 10)
        assertEquals(4, plan.filled)
        assertEquals(20, plan.totalSpurs)
    }

    @Test
    fun applyFillsRemovesExhaustedOrders() {
        reset()
        Matching.insertSell("test:item", Order(1, "a", 5, 4))
        val plan = Matching.plan("test:item", 4)
        Matching.applyFills("test:item", plan.fills)
        assertEquals(0, Matching.bookFor("test:item").sells.size)
    }

    @Test
    fun cancelOnlyRemovesOwnedOrder() {
        reset()
        Matching.insertSell("test:item", Order(1, "a", 5, 4))
        Matching.insertSell("test:item", Order(2, "b", 5, 4))
        assertNull(Matching.cancelOrder("test:item", 1, "someoneelse"))
        val cancelled = Matching.cancelOrder("test:item", 1, "a")
        assertEquals(1L, cancelled?.id)
        assertEquals(1, Matching.bookFor("test:item").sells.size)
    }

    @Test
    fun maxAffordableStopsBeforeCrossingIntoAPricierLevel() {
        reset()
        Matching.insertSell("test:item", Order(1, "a", 1000, 30))
        Matching.insertSell("test:item", Order(2, "b", 1500, 20))
        assertEquals(30, Matching.maxAffordable("test:item", 30_000))
        assertEquals(30, Matching.maxAffordable("test:item", 31_499))
        assertEquals(31, Matching.maxAffordable("test:item", 31_500))
        assertEquals(50, Matching.maxAffordable("test:item", 1_000_000))
        assertEquals(0, Matching.maxAffordable("test:item", 0))
    }

}

class MatchingAllInTest {
    private val buyer = UUID.randomUUID()
    private val neutral = UUID.randomUUID()
    private val taxed = UUID.randomUUID()
    private val item = "test:item"

    private fun setup(configure: Fakes.Claims.() -> Unit = {}) {
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        ClaimsApi.register(Fakes.claims().apply {
            home += mapOf(buyer to "aurelia", neutral to "nordmark", taxed to "ostland")
            configure()
        })
    }

    @AfterTest
    fun clear() {
        ClaimsApi.register(null)
        TestDb.close()
    }

    @Test
    fun sellNowRanksBidsByNetPerUnitAfterTaxAndTariff() {
        setup { tariffs["aurelia" to "ostland"] = 15 }
        Matching.insertBid(item, Order(1, buyer.toString(), 100, 20))
        Matching.insertBid(item, Order(2, neutral.toString(), 90, 20))
        val plan = Matching.sellPlan(item, 40, taxed.toString())
        assertEquals(listOf(2L, 1L), plan.fills.map { it.orderId })
        assertEquals(40, plan.filled)
    }

    @Test
    fun sellNowSkipsABidWithNoSellerNetAndStillFillsTheNext() {
        setup {
            tariffs["aurelia" to "ostland"] = 90
            tariffs["nordmark" to "ostland"] = 95
        }
        Matching.insertBid(item, Order(1, buyer.toString(), 1, 20))
        Matching.insertBid(item, Order(2, neutral.toString(), 1, 1))
        val plan = Matching.sellPlan(item, 21, taxed.toString())
        assertEquals(listOf(2L), plan.fills.map { it.orderId })
        assertEquals(1, plan.filled)
        assertTrue(plan.net > 0)
    }

    @Test
    fun buyRanksAsksByPriceWithTariffAndBreaksTiesByPlacementTime() {
        setup { tariffs["aurelia" to "ostland"] = 15 }
        Matching.insertSell(item, Order(1, taxed.toString(), 90, 20, placedAt = 1))
        Matching.insertSell(item, Order(2, neutral.toString(), 100, 20, placedAt = 5))
        Matching.insertSell(item, Order(3, neutral.toString(), 100, 20, placedAt = 2))
        val plan = Matching.plan(item, 60, buyer.toString())
        assertEquals(listOf(3L, 2L, 1L), plan.fills.map { it.orderId })
    }

    @Test
    fun buyLimitExcludesAsksBeforeSortingAndLeavesNothingElseFilled() {
        setup()
        Matching.insertSell(item, Order(1, neutral.toString(), 100, 20))
        Matching.insertSell(item, Order(2, taxed.toString(), 90, 20))
        val plan = Matching.plan(item, 40, buyer.toString(), limit = 95)
        assertEquals(listOf(2L), plan.fills.map { it.orderId })
        assertEquals(20, plan.filled)
    }
}
