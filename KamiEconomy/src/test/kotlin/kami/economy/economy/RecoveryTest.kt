package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.libs.claims.ClaimsApi
import kami.libs.economy.ServerBank
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryTest {
    private class Purse(vararg start: Pair<UUID, Long>) : Wallet {
        val funds = mutableMapOf(*start)
        var failBalance: UUID? = null
        override fun balance(id: UUID): Long {
            if (id == failBalance) { failBalance = null; error("injected") }
            return funds.getOrDefault(id, 0L)
        }
        override fun deduct(id: UUID, amount: Int): Int = minOf(amount.toLong(), funds.getOrDefault(id, 0L)).toInt().also { funds[id] = funds.getOrDefault(id, 0L) - it }
        override fun deposit(id: UUID, amount: Int): Boolean { funds[id] = funds.getOrDefault(id, 0L) + amount; return true }
    }

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
}
