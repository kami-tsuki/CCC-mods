package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.economy.StarterGood
import kami.libs.admin.AdminFailure
import kami.libs.config.Configs
import kami.libs.economy.ServerBank
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminMarketTest {
    private class Purse(vararg start: Pair<UUID, Long>) : Wallet {
        val funds = mutableMapOf(*start)
        override fun balance(id: UUID) = funds.getOrDefault(id, 0L)
        override fun deduct(id: UUID, amount: Int): Int = minOf(amount.toLong(), balance(id)).toInt().also { funds[id] = balance(id) - it }
        override fun deposit(id: UUID, amount: Int): Boolean { funds[id] = balance(id) + amount; return true }
    }

    private val item = "test:log"
    private val other = "test:wheat"
    private val seller = UUID.randomUUID()
    private val bidder = UUID.randomUUID()

    @AfterTest
    fun tearDown() {
        TestDb.close()
        Configs.base?.toFile()?.deleteRecursively()
        Configs.base = null
    }

    @Test
    fun cancelAsServerRefundsEscrowAndQueuesItems() {
        Config.s = Settings()
        Market.data = Data()
        Stocks.index { emptyList() }
        TestDb.open()
        ServerBank.load()
        val purse = Purse(bidder to 1_000L)
        Ledger.wallet = purse
        val total = { purse.funds.values.sum() + ServerBank.balance }
        val before = total()
        val bid = (Ledger.bid(bidder.toString(), item, 10, 20) as OrderResult.Ok).orderId
        val sell = Market.nextId()
        Matching.insertSell(item, Order(sell, seller.toString(), 50, 5))
        assertEquals(800L, purse.balance(bidder))

        assertEquals(bidder.toString(), Ledger.cancelAsServer("buy", item, bid, "abuse"))
        assertEquals(seller.toString(), Ledger.cancelAsServer("sell", item, sell, "abuse"))
        assertNull(Ledger.cancelAsServer("sell", item, sell, "abuse"))

        assertEquals(1_000L, purse.balance(bidder))
        assertEquals(before, total())
        assertTrue(Market.book(item).buys.isEmpty() && Market.book(item).sells.isEmpty())
        val queued = Market.data.pendingDelivery.getValue(seller.toString())
        assertTrue(queued.any { it.item == item && it.qty == 5 })
        assertTrue(queued.any { it.qty == 0 && it.id.endsWith("abuse") })
    }

    @Test
    fun starterEditsCheckRevisionAndToggleInfinite() {
        Configs.base = Files.createTempDirectory("kami-eco-cfg")
        Config.s = Settings(starterGoods = listOf(StarterGood(item, 20)))
        Market.data = Data()
        Stocks.index { emptyList() }

        assertFailsWith<AdminFailure> { Starters.upsert(StarterGood(other, 5), "stale") }
        val rev = Starters.rev()
        Starters.upsert(StarterGood(other, 5, infinite = true), rev)
        val mid = Starters.rev()
        assertEquals(2, Config.s.starterGoods.size)
        assertFailsWith<AdminFailure> { Starters.upsert(StarterGood(other, 6), rev) }

        assertEquals(-1, Stocks.stock(other)!!.demand)
        Starters.upsert(Stocks.good(other)!!.copy(demand = 10), Starters.rev())

        Matching.insertSell(other, Order(Market.nextId(), seller.toString(), 9, 1))
        Starters.remove(other, Starters.rev())
        assertNull(Stocks.good(other))
        assertTrue(other !in Market.data.stocks)
        assertEquals(1, Market.book(other).sells.size)
        assertFailsWith<AdminFailure> { Starters.remove(item, mid) }
    }
}
