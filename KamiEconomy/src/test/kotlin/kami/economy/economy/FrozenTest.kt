package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrozenTest {
    private class Gate(val purse: Purse) : Wallet {
        var refuse = false
        var onDeduct: () -> Unit = {}
        override fun balance(id: UUID) = purse.balance(id)
        override fun deduct(id: UUID, amount: Int) = purse.deduct(id, amount).also { onDeduct() }
        override fun deposit(id: UUID, amount: Int) = !refuse && purse.deposit(id, amount)
    }

    private val item = "test:item"
    private val seller = UUID.randomUUID()
    private val buyer = UUID.randomUUID()
    private val purse = Purse(seller to 100L, buyer to 10_000L)
    private val gate = Gate(purse)

    private fun setup() {
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        Ledger.wallet = gate
        assertTrue(Ledger.bid(buyer.toString(), item, 5, 10) is OrderResult.Ok)
    }

    @AfterTest
    fun tearDown() = TestDb.close()

    @Test
    fun sellNowReturnsFrozenWhenThePayoutCreditFails() {
        setup()
        gate.refuse = true
        assertEquals(SellResult.Frozen(5), Ledger.sellNow(seller.toString(), item, 5))
        assertTrue(Ledger.frozen().isNotEmpty())
    }

    @Test
    fun listingIntoABidReturnsFrozenWhenThePayoutCreditFails() {
        setup()
        gate.refuse = true
        assertEquals(SellResult.Frozen(5), Ledger.sell(seller.toString(), item, 5, 10))
    }

    @Test
    fun sellNowStillSucceedsWhenTheCreditWorks() {
        setup()
        assertEquals(SellResult.Sold(5, 45), Ledger.sellNow(seller.toString(), item, 5))
        assertTrue(Ledger.frozen().isEmpty())
    }

    @Test
    fun cancelBidCallsOnFrozenOnlyWhenTheRefundFreezes() {
        setup()
        var calls = 0
        gate.refuse = true
        assertEquals(50L, Ledger.cancelBid(buyer.toString(), item) { calls++ })
        assertEquals(1, calls)
        assertTrue(Ledger.frozen().isNotEmpty())
    }

    @Test
    fun cancelBidDoesNotCallOnFrozenWhenTheRefundWorks() {
        setup()
        var calls = 0
        assertEquals(50L, Ledger.cancelBid(buyer.toString(), item) { calls++ })
        assertEquals(0, calls)
        assertTrue(Ledger.frozen().isEmpty())
    }

    @Test
    fun repriceBidRejectsAPriceWhoseEscrowOverflows() {
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        Ledger.wallet = gate
        Matching.insertBid(item, Order(1, buyer.toString(), 1, 10_000))
        assertEquals(OrderResult.OutOfRange, Ledger.repriceBid(buyer.toString(), item, 1_000_000))
        assertEquals(1, Matching.bookFor(item).buys.single().price)
    }

    @Test
    fun buyReturnsFrozenWhenTheDroppedFillRefundFailsAndDoesNotRefundTwice() {
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        Ledger.wallet = gate
        Matching.insertSell(item, Order(1, seller.toString(), 10, 5))
        gate.onDeduct = { Matching.cancelOrder(item, 1, seller.toString()); gate.refuse = true }
        assertEquals(BuyResult.Frozen, Ledger.buy(buyer.toString(), item, 5))
        assertEquals(10_000L - 50L, purse.balance(buyer))
        assertTrue(Ledger.frozen().isNotEmpty())
        assertTrue(purse.funds.getValue(seller) == 100L)
    }
}
