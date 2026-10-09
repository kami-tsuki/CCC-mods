package kami.economy.economy

import kami.economy.LOG
import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.libs.db.SavePoint
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LedgerTest {
    private val buyer = UUID.randomUUID()
    private val sellerA = UUID.randomUUID()
    private val sellerB = UUID.randomUUID()

    @AfterTest
    fun tearDown() = TestDb.close()

    private fun setup(wallet: Till): Till {
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        Ledger.wallet = wallet
        return wallet
    }

    @Test
    fun overflowingTotalIsRejected() {
        val wallet = setup(Till(Long.MAX_VALUE))
        Matching.insertSell("test:item", Order(1, sellerA.toString(), 1_100_000_000, 1))
        Matching.insertSell("test:item", Order(2, sellerB.toString(), 1_100_000_000, 1))
        assertEquals(2_200_000_000L, Matching.plan("test:item", 2).totalSpurs)
        assertEquals(BuyResult.OutOfRange, Ledger.buy(buyer.toString(), "test:item", 2))
        assertEquals(Long.MAX_VALUE, wallet.funds)
        assertTrue(wallet.deposits.isEmpty())
    }

    @Test
    fun intentIsWrittenBeforeTheDebit() {
        val wallet = setup(Till(100))
        wallet.onDeduct = { SavePoint.run(); assertTrue("PENDING" in TestDb.states()) }
        Matching.insertSell("test:item", Order(1, sellerA.toString(), 10, 5))
        val result = Ledger.buy(buyer.toString(), "test:item", 5)
        assertEquals(BuyResult.Ok(5, 50), result)
        assertEquals(50, wallet.funds)
        assertEquals(listOf(sellerA to 45), wallet.deposits)
    }

    @Test
    fun replayPaysOnlyUnpaidFillsOnce() {
        val wallet = setup(Till(0))
        val fills = listOf(LedgerFill(1, sellerA.toString(), 2, 10), LedgerFill(2, sellerB.toString(), 3, 10))
        val intent = Intent(7, IntentType.BUY, LedgerState.BOOK_INSERTED, actor = buyer.toString(), item = "test:item", qty = 5, netSpurs = 50, fills = fills, paid = listOf(1))
        TestDb.wal(intent)
        TestDb.restart()
        Ledger.recover()
        assertEquals(listOf(sellerB to 27), wallet.deposits)
        TestDb.restart()
        Ledger.recover()
        assertEquals(1, wallet.deposits.size)
        SavePoint.run()
        assertTrue(TestDb.states().isEmpty())
    }

    @Test
    fun uncleanSellIsRejectedAndCleanSellListsWholeLots() {
        setup(Till(0))
        Config.s = Settings(lots = mapOf("test:log" to 64))
        assertEquals(SellResult.NotClean(64), Ledger.sell(sellerA.toString(), "test:log", 32, 20))
        assertEquals(SellResult.NotClean(128), Ledger.sell(sellerA.toString(), "test:log", 64, 15))
        assertTrue(Ledger.sell(sellerA.toString(), "test:log", 128, 15) is SellResult.Ok)
        assertEquals(64, Matching.bookFor("test:log").sells.single().lot)
    }

    @Test
    fun lotPricedBuyPaysPerLotAndTaxIsWhole() {
        val wallet = setup(Till(1_000))
        Config.s = Settings(lots = mapOf("test:log" to 64))
        Matching.insertSell("test:log", Order(1, sellerA.toString(), 15, 256, lot = 64))
        assertEquals(BuyResult.NothingAvailable, Ledger.buy(buyer.toString(), "test:log", 100))
        assertEquals(BuyResult.Ok(128, 30), Ledger.buy(buyer.toString(), "test:log", 192))
        assertEquals(listOf(sellerA to 27), wallet.deposits)
    }
    @Test
    fun crashAfterPendingWriteBeforeDeductIsVoidedAndBalanceUntouched() {
        val wallet = setup(Till(100))
        val fills = listOf(LedgerFill(1, sellerA.toString(), 5, 10))
        val intent = Intent(5, IntentType.BUY, LedgerState.PENDING, actor = buyer.toString(), item = "test:item", qty = 5, netSpurs = 50, fills = fills, preBalance = 100)
        TestDb.wal(intent)
        TestDb.restart()
        Ledger.recover()
        assertEquals(100, wallet.funds)
        assertTrue(wallet.deposits.isEmpty())
        assertTrue(Market.data.pendingDelivery.isEmpty())
        TestDb.restart()
        Ledger.recover()
        assertEquals(100, wallet.funds)
        assertTrue(wallet.deposits.isEmpty())
    }

    @Test
    fun crashAfterDeductBeforeDebitedResumesAndDeliversOnce() {
        val wallet = setup(Till(50))
        val fills = listOf(LedgerFill(2, sellerB.toString(), 5, 10))
        val intent = Intent(6, IntentType.BUY, LedgerState.PENDING, actor = buyer.toString(), item = "test:item", qty = 5, netSpurs = 50, fills = fills, preBalance = 100)
        TestDb.wal(intent)
        TestDb.restart()
        Ledger.recover()
        assertEquals(50, wallet.funds)
        assertEquals(listOf(sellerB to 45), wallet.deposits)
        assertEquals(5, Market.data.pendingDelivery[buyer.toString()]!!.sumOf { it.qty })
        TestDb.restart()
        Ledger.recover()
        assertEquals(50, wallet.funds)
        assertEquals(1, wallet.deposits.size)
        assertEquals(5, Market.data.pendingDelivery[buyer.toString()]!!.sumOf { it.qty })
    }

    @Test
    fun crashRecoveryReplayOfTheResumePathIsIdempotent() {
        val resumed = setup(Till(50))
        val resumeIntent = Intent(6, IntentType.BUY, LedgerState.PENDING, actor = buyer.toString(), item = "test:item", qty = 5, netSpurs = 50, fills = listOf(LedgerFill(2, sellerB.toString(), 5, 10)), preBalance = 100)
        TestDb.wal(resumeIntent)
        repeat(2) { TestDb.restart(); Ledger.recover() }
        assertEquals(50, resumed.funds)
        assertEquals(1, resumed.deposits.size)
        assertEquals(5, Market.data.pendingDelivery[buyer.toString()]!!.sumOf { it.qty })
    }

    @Test
    fun recoverResolvesPendingDebitsBeforeOtherCreditsForSamePayer() {
        val wallet = Purse(initial = 100)
        wallet.funds[buyer] = 50
        val bidIntent = Intent(6, IntentType.BID, LedgerState.PENDING, actor = buyer.toString(), item = "test:item", qty = 10, unitPrice = 5, netSpurs = 50, preBalance = 100, lot = 1)
        val cancelIntent = Intent(11, IntentType.BID_CANCEL, LedgerState.BOOK_INSERTED, actor = buyer.toString(), item = "test:item", orderId = 5, netSpurs = 20)
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        TestDb.wal(cancelIntent, bidIntent)
        TestDb.restart()
        Ledger.wallet = wallet
        Ledger.recover()
        assertEquals(70, wallet.balance(buyer))
        assertEquals(listOf(buyer to 20), wallet.deposits)
        assertEquals(1, Matching.bookFor("test:item").buys.size)
    }

    @Test
    fun recoverSkipsAlreadyCreditedFillAndCreditsRemainingFillOnce() {
        val wallet = Purse()
        val fills = listOf(
            LedgerFill(1, sellerA.toString(), 2, 10, lot = 1, tax = 0, pre = 0),
            LedgerFill(2, sellerB.toString(), 3, 10, lot = 1, tax = 0)
        )
        wallet.funds[sellerA] = 20
        val intent = Intent(7, IntentType.BUY, LedgerState.BOOK_INSERTED, actor = buyer.toString(), item = "test:item", qty = 5, netSpurs = 50, fills = fills)
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        TestDb.wal(intent)
        TestDb.restart()
        Ledger.wallet = wallet
        Ledger.recover()
        assertEquals(20, wallet.balance(sellerA))
        assertEquals(30, wallet.balance(sellerB))
        assertEquals(listOf(sellerB to 30), wallet.deposits)
        TestDb.restart()
        Ledger.recover()
        assertEquals(1, wallet.deposits.size)
    }

    @Test
    fun recoverCreditsSameSellerAppearingInTwoFillsExactlyOnceEach() {
        val wallet = Purse()
        val fills = listOf(
            LedgerFill(1, sellerA.toString(), 2, 10, lot = 1, tax = 0),
            LedgerFill(3, sellerA.toString(), 1, 10, lot = 1, tax = 0)
        )
        val intent = Intent(8, IntentType.BUY, LedgerState.BOOK_INSERTED, actor = buyer.toString(), item = "test:item", qty = 3, netSpurs = 30, fills = fills)
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        TestDb.wal(intent)
        TestDb.restart()
        Ledger.wallet = wallet
        Ledger.recover()
        assertEquals(30, wallet.balance(sellerA))
        assertEquals(listOf(sellerA to 20, sellerA to 10), wallet.deposits)
        TestDb.restart()
        Ledger.recover()
        assertEquals(2, wallet.deposits.size)
    }

    @Test
    fun oldSaveLoadsWithLotOne() {
        val old = """{"books":{"test:item":{"item":"test:item","sells":[{"id":3,"owner":"a","price":7,"amount":10}]}},"nextOrderId":4}"""
        val data = Json { ignoreUnknownKeys = true }.decodeFromString<Data>(old)
        val order = data.books.getValue("test:item").sells.single()
        assertEquals(1, order.lot)
        setup(Till(0))
        Market.data = data
        assertEquals(10, Matching.plan("test:item", 10).filled)
        assertEquals(0, Matching.plan("test:item", 5).filled)
    }
}
