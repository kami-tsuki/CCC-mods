package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Sale
import kami.economy.Settings
import kami.economy.StarterGood
import kami.economy.now
import kami.libs.economy.ServerBank
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StocksTest {
    private val item = "test:oak_log"
    private val player = UUID.randomUUID().toString()
    private val hour = 60 * 60 * 1000L

    @AfterTest
    fun tearDown() = TestDb.close()

    private fun setup(supply: Int = 100, demand: Int = 100, sellCap: Int = 0, resell: Boolean = true) {
        Config.s = Settings(starterGoods = listOf(StarterGood(item, 20, 64, 32, supply, demand, sellCap = sellCap, resell = resell)))
        Market.data = Data()
        Stocks.index { emptyList() }
    }

    private fun stock() = Stocks.stock(item)!!

    @Test
    fun quotesKeepASpreadAndHoldTheFloor() {
        setup()
        assertEquals(21, Stocks.ask(item))
        assertEquals(19, Stocks.bid(item, player))
        listOf(1.0, 10.0, 10.4, 20.0, 33.3, 1000.0).forEach { assertTrue(Stocks.askAt(item, it) >= Stocks.bidAt(item, it) + 1, "course $it") }
        assertEquals(10, Stocks.bidAt(item, 1.0))
        assertEquals(10.0, Stocks.afterSell(item, 10.0, 50), 1e-9)
        assertTrue(Stocks.afterBuy(item, 20.0, 1) > 20.0)
        assertTrue(Stocks.afterSell(item, 20.0, 1) < 20.0)
    }

    @Test
    fun serverBuysRaiseAndServerSalesLowerTheCourse() {
        setup()
        TestDb.open()
        ServerBank.load()
        Ledger.wallet = Till(1_000_000, true)
        assertTrue(Ledger.buy(player, item, 64 * 4) is BuyResult.Ok)
        val high = Stocks.course(item)
        assertTrue(high > 20.0)
        assertTrue(Ledger.sellNow(player, item, 64 * 8) is SellResult.Sold)
        assertTrue(Stocks.course(item) < high)
    }

    @Test
    fun inventoryIsSymmetric() {
        setup(supply = 5, demand = 5)
        Stocks.take(item, 2)
        assertEquals(3, stock().supply)
        assertEquals(7, stock().demand)
        Stocks.sold(player, item, 3)
        assertEquals(6, stock().supply)
        assertEquals(4, stock().demand)
    }

    @Test
    fun infiniteSidesNeverChangeAndNonResellGoodsKeepSupply() {
        setup(supply = -1, demand = -1)
        Stocks.take(item, 5)
        Stocks.sold(player, item, 5)
        assertEquals(-1, stock().supply)
        assertEquals(-1, stock().demand)
        assertEquals(Int.MAX_VALUE, Stocks.supplyLeft(item))
        assertTrue(Stocks.infiniteDemand(item))
        setup(supply = 5, demand = 5, resell = false)
        Stocks.sold(player, item, 3)
        assertEquals(5, stock().supply)
        assertEquals(2, stock().demand)
    }

    @Test
    fun emptySidesQuoteNothing() {
        setup(supply = 0, demand = 0)
        assertNull(Stocks.ask(item))
        assertNull(Stocks.bid(item, player))
        assertEquals(0, Matching.plan(item, 64).filled)
        assertEquals(0, Matching.sellPlan(item, 64, player).filled)
    }

    @Test
    fun sellCapRollsOverA24HourWindow() {
        setup(sellCap = 3)
        assertEquals(3, Stocks.capLeft(player, item))
        Stocks.sold(player, item, 2)
        assertEquals(1, Stocks.capLeft(player, item))
        assertEquals(1, Stocks.demandLeft(item, player))
        Market.data.sold[player] = mutableMapOf(item to mutableListOf(Sale(now() - 25 * hour, 3), Sale(now() - 23 * hour, 1)))
        assertEquals(2, Stocks.capLeft(player, item))
        Stocks.prune()
        assertEquals(1, Market.data.sold.getValue(player).getValue(item).size)
    }

    @Test
    fun uncappedGoodsHaveUnlimitedCapacity() {
        setup(sellCap = 0)
        assertEquals(Int.MAX_VALUE, Stocks.capLeft(player, item))
        assertEquals(100, Stocks.demandLeft(item, player))
    }

    @Test
    fun buyPlanMixesACheaperPlayerOrderWithServerLotsAndTheLimitStopsIt() {
        setup(supply = -1)
        Matching.insertSell(item, Order(1, "p1", 20, 64, lot = 64))
        val plan = Matching.plan(item, 128)
        assertEquals(listOf(1L, Stocks.ORDER_ID), plan.fills.map { it.orderId })
        assertEquals(128, plan.filled)
        assertEquals(64, plan.fills[1].qty)
        val limited = Matching.plan(item, 128, limit = 20)
        assertEquals(64, limited.filled)
        assertEquals(1L, limited.fills.single().orderId)
    }

    @Test
    fun sellPlanMixesAHigherPlayerBidWithServerLotsAndTheLimitStopsIt() {
        setup(demand = -1)
        Matching.insertBid(item, Order(2, "p2", 25, 64, lot = 64))
        val plan = Matching.sellPlan(item, 128, "s")
        assertEquals(listOf(2L, Stocks.ORDER_ID), plan.fills.map { it.orderId })
        assertEquals(128, plan.filled)
        val limited = Matching.sellPlan(item, 128, "s", limit = 24)
        assertEquals(64, limited.filled)
        assertEquals(2L, limited.fills.single().orderId)
    }
}
