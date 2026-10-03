package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Delivery
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

    private class KeyedWallet(private val initial: Long) : Wallet {
        val balances = mutableMapOf<UUID, Long>()
        val deposits = mutableListOf<Pair<UUID, Int>>()
        override fun balance(id: UUID) = balances.getOrDefault(id, initial)
        override fun deduct(id: UUID, amount: Int): Int {
            val bal = balance(id)
            val taken = minOf(amount.toLong(), bal).toInt()
            balances[id] = bal - taken
            return taken
        }
        override fun deposit(id: UUID, amount: Int): Boolean {
            deposits += id to amount
            balances[id] = balance(id) + amount
            return true
        }
    }

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
    fun uncleanAmountIsRoundedNotRejected() {
        val wallet = setup()
        val sold = Ledger.sell(seller.toString(), item, 7, 15)
        assertTrue(sold is SellResult.Ok && sold.listed == 6)
        assertEquals(listOf(6), Market.book(item).sells.map { it.amount })
        val bid = Ledger.bid(bidderA.toString(), item, 25, 7)
        assertTrue(bid is OrderResult.Ok && bid.qty == 20)
        assertEquals(860, wallet.funds)
    }

    @Test
    fun tradedCountersTrackBuysAndSells() {
        setup()
        Ledger.bid(bidderA.toString(), item, 10, 20)
        Ledger.sellNow(seller.toString(), item, 4)
        assertEquals(4, Market.data.traded.getValue(item).sold)
        Matching.insertSell(item, Order(50, seller.toString(), 40, 5))
        Ledger.buy(bidderB.toString(), item, 5)
        assertEquals(5, Market.data.traded.getValue(item).bought)
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
    fun repriceBidRaisingPriceDebitsTheDeltaAndLoweringRefundsIt() {
        val wallet = setup()
        assertTrue(Ledger.bid(bidderA.toString(), item, 10, 20) is OrderResult.Ok)
        assertEquals(800, wallet.funds)
        assertTrue(Ledger.repriceBid(bidderA.toString(), item, 25) is OrderResult.Ok)
        assertEquals(750, wallet.funds)
        assertEquals(25, bids().single().price)
        assertTrue(Ledger.repriceBid(bidderA.toString(), item, 15) is OrderResult.Ok)
        assertEquals(listOf(bidderA to 100), wallet.deposits)
        assertEquals(750, wallet.funds)
        assertEquals(15, bids().single().price)
    }

    @Test
    fun repriceBidToSamePriceOrInvalidPriceIsRejectedOrNoOp() {
        val wallet = setup()
        Ledger.bid(bidderA.toString(), item, 3, 10)
        assertEquals(OrderResult.Ok(bids().single().id), Ledger.repriceBid(bidderA.toString(), item, 10))
        assertEquals(OrderResult.NotClean(2), Ledger.repriceBid(bidderA.toString(), item, 15))
        assertEquals(OrderResult.OutOfRange, Ledger.repriceBid(bidderA.toString(), item, 0))
        assertEquals(970, wallet.funds)
        assertEquals(10, bids().single().price)
    }

    @Test
    fun repriceBidInsufficientFundsLeavesEscrowUntouched() {
        val wallet = setup(220)
        Ledger.bid(bidderA.toString(), item, 10, 20)
        assertEquals(20, wallet.funds)
        assertEquals(OrderResult.InsufficientFunds, Ledger.repriceBid(bidderA.toString(), item, 25))
        assertEquals(20, wallet.funds)
        assertEquals(20, bids().single().price)
    }

    @Test
    fun walReplayOfRepriceBidIsIdempotent() {
        val wallet = setup()
        Matching.insertBid(item, Order(9, bidderA.toString(), 20, 10))
        val raise = Intent(20, IntentType.REPRICE_BID, LedgerState.DEBITED, actor = bidderA.toString(), item = item, orderId = 9, unitPrice = 25, netSpurs = 50)
        Files.writeString(wal, json.encodeToString(raise) + "\n")
        repeat(2) {
            Ledger.attach(wal)
            Ledger.recover()
        }
        assertEquals(25, bids().single().price)
        assertEquals(1000, wallet.funds)
        assertTrue(wallet.deposits.isEmpty())
    }

    @Test
    fun crashMidRepriceBidResumesConsistently() {
        val wallet = setup(1000)
        Matching.insertBid(item, Order(9, bidderA.toString(), 20, 10))
        wallet.funds = 950
        val pending = Intent(20, IntentType.REPRICE_BID, LedgerState.PENDING, actor = bidderA.toString(), item = item, orderId = 9, unitPrice = 25, netSpurs = 50, preBalance = 1000)
        Files.writeString(wal, json.encodeToString(pending) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(950, wallet.funds)
        assertEquals(25, bids().single().price)
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(950, wallet.funds)
        assertEquals(25, bids().single().price)
    }

    @Test
    fun crashAtBookInsertedBeforeRefundCreditIsAppliedExactlyOnce() {
        Config.s = Settings()
        Market.data = Data()
        Files.writeString(wal, "")
        Ledger.attach(wal)
        val wallet = KeyedWallet(750)
        Ledger.wallet = wallet
        Matching.insertBid(item, Order(9, bidderA.toString(), 15, 10))
        val intent = Intent(21, IntentType.REPRICE_BID, LedgerState.BOOK_INSERTED, actor = bidderA.toString(), item = item, orderId = 9, unitPrice = 15, netSpurs = -50, preBalance = 750)
        Files.writeString(wal, json.encodeToString(intent) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(800, wallet.balance(bidderA))
        assertEquals(listOf(bidderA to 50), wallet.deposits)
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(800, wallet.balance(bidderA))
        assertEquals(1, wallet.deposits.size)
    }

    @Test
    fun crashBetweenRefundCreditAndPaidWriteDoesNotDoubleRefund() {
        Config.s = Settings()
        Market.data = Data()
        Files.writeString(wal, "")
        Ledger.attach(wal)
        val wallet = KeyedWallet(750)
        Ledger.wallet = wallet
        wallet.balances[bidderA] = 800
        Matching.insertBid(item, Order(9, bidderA.toString(), 15, 10))
        val intent = Intent(20, IntentType.REPRICE_BID, LedgerState.BOOK_INSERTED, actor = bidderA.toString(), item = item, orderId = 9, unitPrice = 15, netSpurs = -50, preBalance = 750)
        Files.writeString(wal, json.encodeToString(intent) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(800, wallet.balance(bidderA))
        assertTrue(wallet.deposits.isEmpty())
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(800, wallet.balance(bidderA))
        assertTrue(wallet.deposits.isEmpty())
    }

    @Test
    fun crashBetweenSellNowCreditAndPaidWriteDoesNotDoubleCredit() {
        Config.s = Settings()
        Market.data = Data()
        Files.writeString(wal, "")
        Ledger.attach(wal)
        val wallet = KeyedWallet(0)
        Ledger.wallet = wallet
        wallet.balances[seller] = 207
        val fills = listOf(LedgerFill(1, bidderA.toString(), 12, 20, tax = 0))
        val intent = Intent(30, IntentType.SELL_NOW, LedgerState.BOOK_INSERTED, actor = seller.toString(), item = item, qty = 12, netSpurs = 207, fills = fills, preBalance = 0)
        Files.writeString(wal, json.encodeToString(intent) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(207, wallet.balance(seller))
        assertTrue(wallet.deposits.isEmpty())
    }

    @Test
    fun cancelResumeSkipsDeliveryAlreadyQueuedByAPriorCrash() {
        setup()
        Market.data.pendingDelivery.getOrPut(seller.toString()) { mutableListOf() } += Delivery(item = item, qty = 7, id = "50:d")
        val intent = Intent(50, IntentType.CANCEL, LedgerState.BOOK_INSERTED, actor = seller.toString(), item = item, qty = 7, orderId = 3, unitPrice = 10)
        Files.writeString(wal, json.encodeToString(intent) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        val queued = Market.data.pendingDelivery[seller.toString()]!!
        assertEquals(1, queued.size)
        assertEquals(7, queued.sumOf { it.qty })
    }

    @Test
    fun buyResumeSkipsDeliveryAlreadyQueuedByAPriorCrash() {
        setup()
        Market.data.pendingDelivery.getOrPut(bidderA.toString()) { mutableListOf() } += Delivery(item = item, qty = 5, id = "60:d")
        val intent = Intent(60, IntentType.BUY, LedgerState.DEBITED, actor = bidderA.toString(), item = item, qty = 5, netSpurs = 50)
        Files.writeString(wal, json.encodeToString(intent) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        val queued = Market.data.pendingDelivery[bidderA.toString()]!!
        assertEquals(1, queued.size)
        assertEquals(5, queued.sumOf { it.qty })
    }

    @Test
    fun sellToStockResumeCreditIsIdempotentAcrossCrashes() {
        Config.s = Settings()
        Market.data = Data()
        Files.writeString(wal, "")
        Ledger.attach(wal)
        val wallet = KeyedWallet(0)
        Ledger.wallet = wallet
        wallet.balances[seller] = 100
        val intent = Intent(70, IntentType.SELL, LedgerState.BOOK_INSERTED, actor = seller.toString(), item = item, qty = 10, netSpurs = 50, instant = true, lot = 1, preBalance = 100)
        Files.writeString(wal, json.encodeToString(intent) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(150, wallet.balance(seller))
        assertEquals(listOf(seller to 50), wallet.deposits)
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(150, wallet.balance(seller))
        assertEquals(listOf(seller to 50), wallet.deposits)
    }

    @Test
    fun auctionCancelResumeSkipsStackDeliveryAlreadyQueuedByAPriorCrash() {
        setup()
        Market.data.pendingDelivery.getOrPut(seller.toString()) { mutableListOf() } += Delivery(stackData = "stack-1", id = "51:d")
        val intent = Intent(51, IntentType.AUCTION_CANCEL, LedgerState.BOOK_INSERTED, actor = seller.toString(), auctionId = 1, stackData = "stack-1")
        Files.writeString(wal, json.encodeToString(intent) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(1, Market.data.pendingDelivery[seller.toString()]!!.size)
    }

    @Test
    fun crashBetweenAuctionBidRefundAndPaidWriteDoesNotDoubleRefund() {
        Config.s = Settings()
        Market.data = Data()
        Files.writeString(wal, "")
        Ledger.attach(wal)
        val wallet = KeyedWallet(0)
        Ledger.wallet = wallet
        wallet.balances[bidderB] = 150
        val intent = Intent(40, IntentType.AUCTION_BID, LedgerState.BOOK_INSERTED, actor = bidderA.toString(), auctionId = 1, unitPrice = 100, prevBidder = bidderB.toString(), prevBid = 50, prevBalance = 100)
        Files.writeString(wal, json.encodeToString(intent) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(150, wallet.balance(bidderB))
        assertTrue(wallet.deposits.isEmpty())
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(150, wallet.balance(bidderB))
        assertTrue(wallet.deposits.isEmpty())
    }

    @Test
    fun crashBetweenAuctionSettlePayoutsAndPaidWriteDoesNotDoubleAnyPayoutOrDelivery() {
        Config.s = Settings()
        Market.data = Data()
        Files.writeString(wal, "")
        Ledger.attach(wal)
        val wallet = KeyedWallet(0)
        Ledger.wallet = wallet
        wallet.balances[bidderB] = 150
        wallet.balances[seller] = 90
        Market.data.pendingDelivery.getOrPut(bidderA.toString()) { mutableListOf() } += Delivery(stackData = "stack-2", id = "42:d")
        val intent = Intent(
            42, IntentType.AUCTION_SETTLE, LedgerState.BOOK_INSERTED, actor = bidderA.toString(), seller = seller.toString(),
            auctionId = 1, unitPrice = 100, netSpurs = 90, stackData = "stack-2",
            prevBidder = bidderB.toString(), prevBid = 50, prevBalance = 100, sellerBalance = 0
        )
        Files.writeString(wal, json.encodeToString(intent) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(150, wallet.balance(bidderB))
        assertEquals(90, wallet.balance(seller))
        assertTrue(wallet.deposits.isEmpty())
        assertEquals(1, Market.data.pendingDelivery[bidderA.toString()]!!.size)
    }

    @Test
    fun permanentCreditFailureFreezesTheIntentInsteadOfLoopingForever() {
        Config.s = Settings()
        Market.data = Data()
        Files.writeString(wal, "")
        Ledger.attach(wal)
        val wallet = object : Wallet {
            override fun balance(id: UUID) = 0L
            override fun deduct(id: UUID, amount: Int) = amount
            override fun deposit(id: UUID, amount: Int) = false
        }
        Ledger.wallet = wallet
        val intent = Intent(60, IntentType.BID_CANCEL, LedgerState.BOOK_INSERTED, actor = bidderA.toString(), item = item, orderId = 5, netSpurs = 20)
        Files.writeString(wal, json.encodeToString(intent) + "\n")
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(listOf(60L), Market.data.frozen)
        Ledger.attach(wal)
        Ledger.recover()
        assertEquals(listOf(60L), Market.data.frozen)
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
