package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.economy.StarterGood
import kami.libs.claims.ClaimsApi
import kami.libs.claims.Relation
import java.nio.file.Files
import java.util.UUID
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StocksTest {
    private val item = "test:oak_log"
    private val player = UUID.randomUUID()
    private val deposits = mutableListOf<Int>()

    @AfterTest
    fun clearClaims() = ClaimsApi.register(null)

    private fun setup(dailyCap: Int? = null, infinite: Boolean = false) {
        Config.s = Settings(starterGoods = listOf(StarterGood(if (infinite) item else "#test:logs", 20, 64, 64, 32, dailyCap, infinite)))
        Market.data = Data()
        Stocks.index { if (it == "test:logs") listOf(item) else emptyList() }
        deposits.clear()
        Ledger.wallet = object : Wallet {
            override fun balance(id: UUID) = 1_000_000L
            override fun deduct(id: UUID, amount: Int) = amount
            override fun deposit(id: UUID, amount: Int): Boolean { deposits += amount; return true }
        }
        Ledger.attach(Files.createTempFile("kami_stock", ".wal"))
    }

    private fun stock() = Stocks.stock(item)!!

    private fun spurs(x: Double) = floor(x + 1e-9).toLong()

    @Test
    fun curveCostEqualsSplitTrades() {
        setup()
        assertEquals(Stocks.value(item, 0, 200), (0 until 200).sumOf { Stocks.value(item, it, it + 1) }, 1e-6)
        val whole = Stocks.plan(item, 8 * 64)!!.gross
        var split = 0L
        repeat(8) { split += Stocks.plan(item, 64)!!.gross; Stocks.take(item, 1) }
        assertTrue(split >= whole && split - whole < 8, "whole $whole split $split")
    }

    @Test
    fun pricesStayInsideTheBand() {
        setup()
        stock().lots = 0
        assertEquals(40.0, Stocks.price(item), 1e-9)
        assertTrue(Stocks.value(item, 0, 1) <= 40.0 + 1e-9)
        stock().lots = 100_000
        assertEquals(10.0, Stocks.price(item), 1e-9)
        assertEquals(10.0, Stocks.value(item, 100_000, 100_001), 1e-9)
        stock().lots = 64
        assertEquals(20.0, Stocks.price(item), 1e-9)
    }

    @Test
    fun cappedSalesPayBaseAndLaterSalesPayTheCurve() {
        setup()
        stock().lots = 80
        assertTrue(Ledger.sellToStock(player.toString(), item, 3 * 64) is SellResult.Ok)
        assertEquals(listOf(54), deposits)
        assertEquals(83, stock().lots)
        assertEquals(0, Stocks.capLeft(player.toString(), item))
        stock().lots = 64
        val expected = spurs(Stocks.value(item, 64, 67))
        val sale = Stocks.sale(player.toString(), item, 3)!!
        assertEquals(0, sale.guaranteed)
        assertEquals(expected, sale.gross)
        assertTrue(sale.gross < 60)
        assertTrue(Ledger.sellToStock(player.toString(), item, 100) is SellResult.NotClean)
    }

    @Test
    fun capResetsAndStockRecovers() {
        setup()
        val t = 1_700_000_000_000L
        Stocks.rollover(t)
        stock().lots = 500
        Stocks.sold(player.toString(), item, 3, 3, 20)
        assertEquals(0, Stocks.capLeft(player.toString(), item))
        Stocks.rollover(t + 60_000)
        assertEquals(0, Stocks.capLeft(player.toString(), item))
        Stocks.rollover(t + 86_400_000)
        assertEquals(3, Stocks.capLeft(player.toString(), item))
        assertEquals(503 - 22, stock().lots)
    }

    @Test
    fun buyTakesCheaperSourceFirstAndPaysMarketTax() {
        setup()
        Matching.insertSell(item, Order(1, UUID.randomUUID().toString(), 20, 64, lot = 64))
        val plan = Matching.plan(item, 3 * 64)
        assertEquals(1L, plan.fills.first().orderId)
        val market = plan.fills.single { it.market }
        assertEquals(2 * 64, market.qty)
        assertEquals(20 + market.gross + market.tax, plan.totalSpurs)
        assertEquals((market.gross + 9) / 10, market.tax)
        assertTrue(Ledger.buy(player.toString(), item, 3 * 64) is BuyResult.Ok)
        assertEquals(62, stock().lots)
        assertEquals(Config.s.lotOf(item), 64)
        assertTrue(Market.book(item).recentFills.all { it.price in 15..25 })
    }

    @Test
    fun marketIsFullOnceTheFloorIsReached() {
        setup()
        assertEquals(87, Stocks.limit(item))
        assertEquals(10.0, Stocks.price(item, 87), 1e-9)
        stock().lots = 85
        assertEquals(2, Stocks.room(item))
        assertTrue(Ledger.sellToStock(player.toString(), item, 2 * 64) is SellResult.Ok)
        assertEquals(87, stock().lots)
        assertEquals(0, Stocks.room(item))
        assertTrue(Stocks.sale(player.toString(), item, 1)!!.full)
        assertEquals(SellResult.Full, Ledger.sellToStock(player.toString(), item, 64))
        assertEquals(87, stock().lots)
        assertEquals(1, deposits.size)
    }

    @Test
    fun infiniteGoodSellsIntoTradableBidNotStock() {
        setup(dailyCap = 3, infinite = true)
        val me = player.toString()
        val other = UUID.randomUUID()
        assertEquals(SellResult.Sold(64, 18), Ledger.sellNow(me, item, 64))
        assertEquals(2, Stocks.capLeft(me, item))
        Matching.insertBid(item, Order(1, other.toString(), 30, 64, lot = 64))
        assertEquals(SellResult.Sold(64, 27), Ledger.sellNow(me, item, 64))
        assertEquals(2, Stocks.capLeft(me, item))
        ClaimsApi.register(Fakes.claims().also {
            it.home[player] = "a"
            it.home[other] = "b"
            it.relations["a" to "b"] = Relation.EMBARGO
        })
        Matching.insertBid(item, Order(2, other.toString(), 30, 64, lot = 64))
        assertEquals(SellResult.Sold(64, 18), Ledger.sellNow(me, item, 64))
        assertEquals(1, Stocks.capLeft(me, item))
    }

    @Test
    fun smallCurveSaleRoundsToWholeSpurs() {
        setup(dailyCap = 0)
        val raw = Stocks.value(item, 64, 65)
        assertTrue(raw in 19.5..19.99, "raw $raw")
        val sale = Stocks.sale(player.toString(), item, 1)!!
        assertEquals(0, sale.guaranteed)
        assertEquals(19L, sale.gross)
        assertEquals(2L, sale.tax)
        assertEquals(17L, sale.net)
    }
}
