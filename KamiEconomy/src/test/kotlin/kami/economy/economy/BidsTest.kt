package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BidsTest {
    private class FakeWallet(var funds: Long) : Wallet {
        val deposits = mutableListOf<Pair<UUID, Int>>()
        override fun balance(id: UUID) = funds
        override fun deduct(id: UUID, amount: Int): Int {
            val taken = minOf(amount.toLong(), funds).toInt()
            funds -= taken
            return taken
        }
        override fun deposit(id: UUID, amount: Int): Boolean {
            deposits += id to amount
            return true
        }
    }

    private val item = "test:item"
    private val bidderA = UUID.randomUUID()
    private val bidderB = UUID.randomUUID()
    private val seller = UUID.randomUUID()
    private val wal = Files.createTempFile("kami_bids", ".wal")
    private val json = Json { encodeDefaults = true }

    private fun setup(funds: Long = 1000): FakeWallet {
        Config.s = Settings()
        Market.data = Data()
        Files.writeString(wal, "")
        Ledger.attach(wal)
        return FakeWallet(funds).also { Ledger.wallet = it }
    }

    private fun bids() = Market.book(item).buys

    @Test
    fun bidHoldsEscrowAndCancelRefundsIt() {
        val wallet = setup()
        assertTrue(Ledger.bid(bidderA.toString(), item, 10, 20) is OrderResult.Ok)
        assertEquals(800, wallet.funds)
        assertEquals(1, bids().size)
        assertEquals(OrderResult.Exists, Ledger.bid(bidderA.toString(), item, 10, 20))
        assertEquals(200L, Ledger.cancelBid(bidderA.toString(), item))
        assertEquals(listOf(bidderA to 200), wallet.deposits)
        assertTrue(bids().isEmpty())
    }

    @Test
    fun bidMustBeCleanAffordableAndBelowTheAsks() {
        val wallet = setup(100)
        assertEquals(OrderResult.NotClean(2), Ledger.bid(bidderA.toString(), item, 1, 15))
        assertEquals(OrderResult.InsufficientFunds, Ledger.bid(bidderA.toString(), item, 10, 20))
        Matching.insertSell(item, Order(99, seller.toString(), 20, 5))
        assertEquals(OrderResult.Crosses, Ledger.bid(bidderA.toString(), item, 2, 20))
        assertEquals(100, wallet.funds)
    }

    @Test
    fun instantSellFillsBestBidsPartially() {
        val wallet = setup()
        Ledger.bid(bidderB.toString(), item, 10, 15)
        Ledger.bid(bidderA.toString(), item, 10, 20)
        assertEquals(SellResult.Sold(12, 207), Ledger.sellNow(seller.toString(), item, 12))
        assertEquals(listOf(seller to 207), wallet.deposits)
        assertEquals(listOf(8), bids().map { it.amount })
        assertEquals(listOf(10, 2), listOf(bidderA, bidderB).map { Market.data.pendingDelivery[it.toString()]!!.sumOf { d -> d.qty } })
        assertEquals(120L, Ledger.cancelBid(bidderB.toString(), item))
    }

    @Test
    fun newAskFillsBidsBeforeListing() {
        val wallet = setup()
        Ledger.bid(bidderA.toString(), item, 10, 20)
        val result = Ledger.sell(seller.toString(), item, 15, 18)
        assertTrue(result is SellResult.Ok && result.filled == 10)
        assertEquals(listOf(seller to 180), wallet.deposits)
        assertTrue(bids().isEmpty())
        assertEquals(listOf(5), Market.book(item).sells.map { it.amount })
    }

    @Test
    fun instantSellWithoutBuyersIsRejected() {
        setup()
        assertEquals(SellResult.NoBuyers, Ledger.sellNow(seller.toString(), item, 5))
    }

    @Test
    fun walReplayOfBidsIsIdempotent() {
        val wallet = setup()
        val placed = Intent(10, IntentType.BID, LedgerState.DEBITED, actor = bidderA.toString(), item = item, qty = 10, unitPrice = 20, netSpurs = 200)
        val cancel = Intent(11, IntentType.BID_CANCEL, LedgerState.PENDING, actor = bidderB.toString(), item = item, qty = 4, orderId = 5, unitPrice = 15, netSpurs = 60)
        val sale = Intent(12, IntentType.SELL_NOW, LedgerState.BOOK_INSERTED, actor = seller.toString(), item = item, qty = 2, netSpurs = 36)
        Files.writeString(wal, listOf(placed, cancel, sale).joinToString("") { json.encodeToString(it) + "\n" })
        Matching.insertBid(item, Order(5, bidderB.toString(), 15, 4))
        repeat(2) {
            Ledger.attach(wal)
            Ledger.recover()
        }
        assertEquals(listOf(bidderA.toString()), bids().map { it.owner })
        assertEquals(listOf(bidderB to 60, seller to 36), wallet.deposits)
        assertEquals(1000, wallet.funds)
    }
}
