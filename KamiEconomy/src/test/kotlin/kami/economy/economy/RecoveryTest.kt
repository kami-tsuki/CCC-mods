package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.libs.claims.ClaimsApi
import kami.libs.db.Db
import kami.libs.economy.ServerBank
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryTest {
    private class Failing : Fakes.Claims() {
        override fun creditTariff(country: String, amount: Long, payer: String, note: String): Long = error("injected")
    }

    private val item = "test:item"
    private val seller = UUID.randomUUID()
    private val buyer = UUID.randomUUID()
    private val purse = Purse(seller to 1_000L, buyer to 10_000L)
    private lateinit var claims: Fakes.Claims

    private fun setup(failing: Boolean = false) {
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        ServerBank.load()
        Ledger.wallet = purse
        claims = (if (failing) Failing() else Fakes.claims()).apply {
            home += mapOf(buyer to "aurelia", seller to "ostland")
            tariffs["aurelia" to "ostland"] = 10
        }
        ClaimsApi.register(claims)
    }

    @AfterTest
    fun tearDown() {
        ClaimsApi.register(null)
        TestDb.close()
    }

    private fun total() = purse.funds.values.sum() + ServerBank.balance + claims.credits.sumOf { it.second }

    @Test
    fun sellNowResumedAfterThrowAppliesBookAndTariffOnce() {
        setup()
        val start = total()
        assertTrue(Ledger.bid(buyer.toString(), item, 5, 10) is OrderResult.Ok)
        purse.failBalance = seller
        assertTrue(Ledger.sellNow(seller.toString(), item, 5) is SellResult.IntentOpen)
        Ledger.recover()
        Ledger.recover()
        assertEquals(listOf("aurelia" to 5L), claims.credits)
        assertEquals(1_040L, purse.funds[seller])
        assertEquals(5L, ServerBank.balance)
        assertEquals(listOf(5), Market.data.pendingDelivery[buyer.toString()]!!.map { it.qty })
        assertTrue(Market.book(item).buys.isEmpty())
        assertEquals(start, total())
    }

    @Test
    fun auctionSettleReturnsAFailedTariffOnce() {
        setup(failing = true)
        val start = total()
        Auctions.insert(1, seller.toString(), "", "x", 10, null)
        assertEquals(BidResult.Ok, Ledger.auctionBid(buyer.toString(), 1, 100))
        Ledger.auctionSweepSettle(1)
        Ledger.recover()
        assertEquals(1_095L, purse.funds[seller])
        assertEquals(5L, ServerBank.balance)
        assertTrue(Ledger.frozen().isEmpty())
        assertEquals(start, total())
    }

    @Test
    fun payFillsKeepsTheTariffWhenItsCreditThrows() {
        setup(failing = true)
        val start = total()
        Matching.insertSell(item, Order(1, seller.toString(), 10, 5))
        assertEquals(BuyResult.Ok(5, 55), Ledger.buy(buyer.toString(), item, 5))
        Ledger.recover()
        assertEquals(1_045L, purse.funds[seller])
        assertEquals(9_950L, purse.funds[buyer])
        assertEquals(5L, ServerBank.balance)
        assertEquals(start, total())
    }

    private fun fill(orderId: Long) = LedgerFill(orderId, seller.toString(), 5, 10, 1, tax = 5, tariff = 0, gross = 50)

    private fun buyIntent(fills: List<LedgerFill>, ver: Int, applied: Boolean) =
        Intent(700, IntentType.BUY, LedgerState.DEBITED, actor = buyer.toString(), item = item, qty = fills.sumOf { it.qty }, netSpurs = fills.sumOf { it.gross }, fills = fills, ver = ver, applied = applied)

    private fun replay(intent: Intent) {
        purse.funds[buyer] = 10_000L - intent.netSpurs
        TestDb.wal(intent)
        Ledger.attach()
        repeat(2) { Ledger.recover() }
    }

    @Test
    fun replayAfterApplyFillsDoesNotDecrementTwice() {
        setup()
        val start = total()
        Matching.insertSell(item, Order(1, seller.toString(), 10, 3))
        replay(buyIntent(listOf(fill(1)), ver = 1, applied = true))
        assertEquals(3, Market.book(item).sells.single().amount)
        assertEquals(listOf(5), Market.data.pendingDelivery[buyer.toString()]!!.map { it.qty })
        assertEquals(1_045L, purse.funds[seller])
        assertEquals(5L, ServerBank.balance)
        assertEquals(start, total())
    }

    @Test
    fun unappliedReplayWithIntactOrderAppliesTheBookOnce() {
        setup()
        val start = total()
        Matching.insertSell(item, Order(1, seller.toString(), 10, 8))
        replay(buyIntent(listOf(fill(1)), ver = 1, applied = false))
        assertEquals(3, Market.book(item).sells.single().amount)
        assertEquals(listOf(5), Market.data.pendingDelivery[buyer.toString()]!!.map { it.qty })
        assertEquals(1_045L, purse.funds[seller])
        assertEquals(start, total())
    }

    @Test
    fun unappliedReplayRefundsFillWhoseOrderWasCancelled() {
        setup()
        val start = total()
        Matching.insertSell(item, Order(2, seller.toString(), 10, 5))
        replay(buyIntent(listOf(fill(1), fill(2)), ver = 1, applied = false))
        assertEquals(9_950L, purse.funds[buyer])
        assertEquals(listOf(5), Market.data.pendingDelivery[buyer.toString()]!!.map { it.qty })
        assertTrue(Market.book(item).sells.isEmpty())
        assertEquals(1_045L, purse.funds[seller])
        assertEquals(5L, ServerBank.balance)
        assertEquals(start, total())
    }

    @Test
    fun legacyIntentReplayKeepsSkippingTheBookCheck() {
        setup()
        val start = total()
        replay(buyIntent(listOf(fill(1)), ver = 0, applied = false))
        assertEquals(9_950L, purse.funds[buyer])
        assertEquals(listOf(5), Market.data.pendingDelivery[buyer.toString()]!!.map { it.qty })
        assertEquals(start, total())
    }

    @Test
    fun intentJsonRoundTripAndLegacyDecode() {
        val intent = buyIntent(listOf(fill(1)), ver = 1, applied = true)
        assertEquals(intent, Db.json.decodeFromString(Intent.serializer(), Db.json.encodeToString(Intent.serializer(), intent)))
        val old = Db.json.decodeFromString(Intent.serializer(), """{"id":1,"type":"BUY","state":"DEBITED"}""")
        assertEquals(0, old.ver)
        assertFalse(old.applied)
    }
}
