package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.libs.economy.ServerBank
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TradeStatusTest {
    private val item = "test:item"
    private val seller = UUID.randomUUID()
    private val buyer = UUID.randomUUID()
    private val purse = Purse(seller to 1_000L, buyer to 10_000L)

    private fun total() = purse.funds.values.sum() + ServerBank.balance

    @BeforeTest
    fun setUp() {
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        ServerBank.load()
        Ledger.wallet = purse
        TradeStatus.load()
    }

    @AfterTest
    fun tearDown() {
        TradeStatus.set(item, emptySet(), "", "")
        TradeStatus.set("$item#v", emptySet(), "", "")
        TradeStatus.unload()
        TestDb.close()
    }

    @Test
    fun frozenKindsAreRefused() {
        TradeStatus.set(item, setOf(TradeFlag.ORDER), "audit", "admin")
        listOf("bid", "sell", "reprice", "reprice_bid").forEach { assertNotNull(TradeStatus.denial(it, item)) }
        listOf("buy", "sell_market", "auction_list", "cancel", "cancel_bid").forEach { assertNull(TradeStatus.denial(it, item)) }
        TradeStatus.set(item, setOf(TradeFlag.BUY, TradeFlag.SELL), "audit", "admin")
        listOf("buy", "sell_market", "bid", "auction_list", "auction_bid", "auction_buy").forEach { assertNotNull(TradeStatus.denial(it, item)) }
        assertNull(TradeStatus.denial("auction_cancel", item))
        assertNull(TradeStatus.denial("buy", "test:other"))
    }

    @Test
    fun pausedOrdersResumeAfterUnfreeze() {
        val start = total()
        Matching.insertSell(item, Order(1, seller.toString(), 10, 5))
        TradeStatus.set(item, setOf(TradeFlag.SELL), "audit", "admin")
        assertEquals(BuyResult.NothingAvailable, Ledger.buy(buyer.toString(), item, 5))
        assertEquals(10_000L, purse.balance(buyer))
        TradeStatus.set(item, emptySet(), "", "admin")
        assertEquals(BuyResult.Ok(5, 50), Ledger.buy(buyer.toString(), item, 5))
        assertEquals(start, total())

        assertTrue(Ledger.bid(buyer.toString(), item, 5, 10) is OrderResult.Ok)
        TradeStatus.set(item, setOf(TradeFlag.BUY), "audit", "admin")
        assertEquals(SellResult.NoBuyers, Ledger.sellNow(seller.toString(), item, 5))
        assertEquals(1, Market.book(item).buys.size)
        TradeStatus.set(item, emptySet(), "", "admin")
        assertTrue(Ledger.sellNow(seller.toString(), item, 5) is SellResult.Sold)
        assertTrue(Market.book(item).buys.isEmpty())
        assertEquals(start, total())
    }

    @Test
    fun variantMergesBaseFlags() {
        TradeStatus.set(item, setOf(TradeFlag.BUY), "base", "admin")
        TradeStatus.set("$item#v", setOf(TradeFlag.INSTANT), "variant", "admin")
        assertEquals(setOf(TradeFlag.BUY, TradeFlag.INSTANT), TradeStatus.flags("$item#v"))
        assertEquals("variant", TradeStatus.of("$item#v")?.reason)
        assertEquals(setOf(TradeFlag.BUY), TradeStatus.flags("$item#w"))
    }

    @Test
    fun instantFreezeListsCrossingSell() {
        assertTrue(Ledger.bid(buyer.toString(), item, 5, 10) is OrderResult.Ok)
        TradeStatus.set(item, setOf(TradeFlag.INSTANT), "audit", "admin")
        val r = Ledger.sell(seller.toString(), item, 5, 10)
        assertTrue(r is SellResult.Ok && r.filled == 0 && r.listed == 5)
        assertEquals(1, Market.book(item).buys.size)
        assertEquals(1_000L to 9_950L, purse.balance(seller) to purse.balance(buyer))
    }

    @Test
    fun statusPersistsThroughSavePoint() {
        TradeStatus.set(item, setOf(TradeFlag.AUCTION, TradeFlag.INSTANT), "audit", "admin")
        TestDb.restart()
        TradeStatus.load()
        assertEquals(setOf(TradeFlag.AUCTION, TradeFlag.INSTANT), TradeStatus.flags(item))
        assertEquals("audit", TradeStatus.of(item)?.reason)
        TradeStatus.set(item, emptySet(), "", "admin")
        TestDb.restart()
        TradeStatus.load()
        assertTrue(TradeStatus.all().isEmpty())
    }
}
