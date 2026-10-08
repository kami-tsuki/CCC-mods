package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.economy.StarterGood
import kami.libs.db.Db
import kami.libs.db.SavePoint
import kami.libs.economy.ServerBank
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BankFlowTest {
    private class Purse(vararg start: Pair<UUID, Long>) : Wallet {
        val funds = mutableMapOf(*start)
        override fun balance(id: UUID) = funds.getOrDefault(id, 0L)
        override fun deduct(id: UUID, amount: Int): Int = minOf(amount.toLong(), balance(id)).toInt().also { funds[id] = balance(id) - it }
        override fun deposit(id: UUID, amount: Int): Boolean { funds[id] = balance(id) + amount; return true }
    }

    private val item = "test:log"
    private val seller = UUID.randomUUID()
    private val buyer = UUID.randomUUID()
    private val purse = Purse(seller to 1_000L, buyer to 10_000L)

    private fun total() = purse.funds.values.sum() + ServerBank.balance

    @BeforeTest
    fun setUp() {
        Config.s = Settings(starterGoods = listOf(StarterGood(item, 20, supply = 1000, demand = 1000)))
        Market.data = Data()
        Stocks.index { emptyList() }
        TestDb.open()
        ServerBank.load()
        Ledger.wallet = purse
    }

    @AfterTest
    fun tearDown() {
        TestDb.close()
        Config.s = Settings()
        Stocks.index { emptyList() }
    }

    private fun assertConserved(start: Long) {
        assertEquals(start, total())
        val memory = ServerBank.balance
        SavePoint.run()
        Db.flush()
        ServerBank.load()
        assertEquals(memory, ServerBank.balance)
    }

    @Test
    fun stockSaleAndPurchaseMoveTheBankByTheOppositeAmount() {
        val start = total()
        assertTrue(Ledger.sellNow(seller.toString(), item, 64) is SellResult.Sold)
        assertTrue(purse.balance(seller) > 1_000L)
        assertConserved(start)
        assertTrue(Ledger.buy(buyer.toString(), item, 128) is BuyResult.Ok)
        assertTrue(purse.balance(buyer) < 10_000L)
        assertConserved(start)
    }

    @Test
    fun playerTradeTaxAndAuctionFeeAreCollected() {
        val start = total()
        Matching.insertSell("test:item", Order(1, seller.toString(), 10, 5))
        assertEquals(BuyResult.Ok(5, 50), Ledger.buy(buyer.toString(), "test:item", 5))
        assertEquals(5L, ServerBank.balance)
        assertConserved(start)
        val auction = Market.nextId()
        Auctions.insert(auction, seller.toString(), "", "x", 10, 50)
        assertEquals(BuyNowResult.Ok, Ledger.auctionBuyNow(buyer.toString(), auction))
        assertEquals(7L, ServerBank.balance)
        assertConserved(start)
    }

    @Test
    fun replayAfterCrashDoesNotDoublePost() {
        val start = total()
        val intent = Intent(900, IntentType.SELL, LedgerState.BOOK_INSERTED, actor = seller.toString(), item = item, qty = 64, unitPrice = 20, taxSpurs = 2, netSpurs = 18, instant = true, lot = 64, preBalance = 1_000)
        purse.funds[seller] = 1_018L
        repeat(2) {
            TestDb.wal(intent)
            Ledger.attach()
            Ledger.recover()
            assertEquals(1_018L, purse.balance(seller))
            assertEquals(-18L, ServerBank.balance)
            assertConserved(start)
        }
        assertEquals(2L, Db.read { one("SELECT COUNT(*) AS v FROM libs_journal WHERE party = 'server' AND ref LIKE 'eco:900:%'") { it.long("v") } })
    }
}
