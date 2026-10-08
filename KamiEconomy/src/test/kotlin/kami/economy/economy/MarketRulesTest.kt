package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.economy.StarterGood
import kami.economy.now
import kami.libs.economy.ServerBank
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketRulesTest {
    private class Purse(var funds: Long) : Wallet {
        override fun balance(id: UUID) = funds
        override fun deduct(id: UUID, amount: Int): Int = minOf(amount.toLong(), funds).toInt().also { funds -= it }
        override fun deposit(id: UUID, amount: Int): Boolean { funds += amount; return true }
    }

    private val item = "test:oak_log"
    private val buyer = UUID.randomUUID().toString()
    private val seller = UUID.randomUUID().toString()
    private val bidderB = UUID.randomUUID().toString()

    @AfterTest
    fun tearDown() = TestDb.close()

    private fun setup(supply: Int = 0, demand: Int = 0) {
        Config.s = Settings(starterGoods = listOf(StarterGood(item, 20, 64, 32, supply, demand)))
        Market.data = Data()
        Stocks.index { emptyList() }
        TestDb.open()
        ServerBank.load()
        Ledger.wallet = Purse(100_000)
    }

    @Test
    fun limitBidFillsCrossingSupplyAndRestsTheRemainder() {
        setup(supply = 1)
        val result = Ledger.bid(buyer, item, 128, 30)
        assertTrue(result is OrderResult.Ok)
        assertEquals(64, result.qty)
        assertEquals(64, result.filled)
        assertEquals(64, Market.book(item).buys.single().amount)
        assertEquals(0, Stocks.supplyLeft(item))
    }

    @Test
    fun bidThatCrossesOwnSellOrderIsRejected() {
        setup(supply = 10)
        Matching.insertSell(item, Order(1, buyer, 25, 64, lot = 64))
        assertEquals(OrderResult.OwnCross, Ledger.bid(buyer, item, 64, 30))
        assertTrue(Market.book(item).buys.isEmpty())
    }

    @Test
    fun auctionBidsStepUpAndLateBidsExtendTheEnd() {
        setup()
        Auctions.insert(1, seller, "", "x", 100, null)
        val a = Auctions.find(1)!!
        assertEquals(100, Auctions.minBid(a))
        assertEquals(BidResult.Ok, Ledger.auctionBid(buyer, 1, 100))
        assertEquals(105, Auctions.minBid(a))
        assertEquals(BidResult.TooLow, Ledger.auctionBid(bidderB, 1, 104))
        a.expiresAt = now() + 10_000
        assertEquals(BidResult.Ok, Ledger.auctionBid(bidderB, 1, 105))
        assertTrue(a.expiresAt - now() > 50_000)
        assertEquals(bidderB, a.currentBidder)
    }

    @Test
    fun pegTickRepricesAPeggedSellOrder() {
        setup(supply = 10)
        Matching.insertSell(item, Order(1, seller, 30, 640, lot = 64, peg = 100))
        Pegs.tick()
        assertEquals(22, Market.book(item).sells.single().price)
    }
}
