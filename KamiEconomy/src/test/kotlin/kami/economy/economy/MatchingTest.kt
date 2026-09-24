package kami.economy.economy

import kami.economy.Market
import kami.economy.Order
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
    fun cancelOnlyRemovesOwnedNonSyntheticOrder() {
        reset()
        Matching.insertSell("test:item", Order(1, "a", 5, 4))
        Matching.insertSell("test:item", Order(2, "b", 5, 4, synthetic = true))
        assertNull(Matching.cancelOrder("test:item", 2, "b"))
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

    @Test
    fun infiniteOnlyWhenNoRealSupply() {
        reset()
        Matching.insertSell("test:item", Order(1, "", 6, Int.MAX_VALUE, synthetic = true))
        assertTrue(Matching.isInfinite("test:item"))
        assertEquals(0, Matching.available("test:item"))

        Matching.insertSell("test:item", Order(2, "a", 5, 10))
        assertFalse(Matching.isInfinite("test:item"))
        assertEquals(10, Matching.available("test:item"))
    }
}
