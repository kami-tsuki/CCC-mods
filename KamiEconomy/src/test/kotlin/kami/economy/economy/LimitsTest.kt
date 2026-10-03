package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.libs.claims.ClaimsApi
import kami.libs.claims.CountryCapacity
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitsTest {
    private class Purse : Wallet {
        override fun balance(id: UUID) = 1_000_000L
        override fun deduct(id: UUID, amount: Int) = amount
        override fun deposit(id: UUID, amount: Int) = true
    }

    private val player = UUID.randomUUID().toString()
    private var nextId = 1L

    private fun setup(limits: Map<String, Int>? = null) {
        Config.s = Settings()
        Market.data = Data()
        Ledger.attach(Files.createTempFile("kami_economy", ".wal"))
        Ledger.wallet = Purse()
        ClaimsApi.register(limits?.let { l -> Fakes.claims().also { it.capacities += l } })
    }

    private fun sellOrders(count: Int) = repeat(count) { Matching.insertSell("test:item$it", Order(nextId++, player, 10, 5)) }

    private fun auctions(count: Int) = repeat(count) { Auctions.insert(nextId, player, "", "x", 10, null); nextId++ }

    @AfterTest
    fun clear() = ClaimsApi.register(null)

    @Test
    fun withoutClaimsTheConfigDefaultsApply() {
        setup()
        assertEquals(5, Limits.marketSlots(player))
        assertEquals(5, Limits.auctionSlots(player))
    }

    @Test
    fun countryCapacityReplacesTheDefault() {
        setup(mapOf(CountryCapacity.MARKET_SLOTS to 8, CountryCapacity.AUCTION_SLOTS to 2))
        assertEquals(8, Limits.marketSlots(player))
        assertEquals(2, Limits.auctionSlots(player))
    }

    @Test
    fun sellAndBidOrdersBothCountAgainstTheMarketLimit() {
        setup(mapOf(CountryCapacity.MARKET_SLOTS to 3))
        Matching.insertSell("test:a", Order(nextId++, player, 10, 5))
        Matching.insertBid("test:b", Order(nextId++, player, 10, 5))
        Matching.insertSell("test:c", Order(nextId++, UUID.randomUUID().toString(), 10, 5))
        assertEquals(2, Limits.marketUsed(player))
        assertFalse(Limits.marketFull(player))
        Matching.insertSell("test:d", Order(nextId++, player, 10, 5))
        assertTrue(Limits.marketFull(player))
    }

    @Test
    fun fullMarketRefusesNewSellsAndBidsButAllowsFullyFilledSales() {
        setup(mapOf(CountryCapacity.MARKET_SLOTS to 2))
        sellOrders(2)
        assertEquals(SellResult.Limit(2), Ledger.sell(player, "test:new", 1, 10))
        assertEquals(OrderResult.Limit(2), Ledger.bid(player, "test:other", 1, 10))
        assertEquals(2, Limits.marketUsed(player))
        Matching.insertBid("test:new", Order(nextId++, UUID.randomUUID().toString(), 10, 1))
        assertTrue(Ledger.sell(player, "test:new", 1, 10) is SellResult.Ok)
    }

    @Test
    fun sellIntoBidsWithFullSlotsFillsAndReturnsRest() {
        setup(mapOf(CountryCapacity.MARKET_SLOTS to 2))
        sellOrders(2)
        Matching.insertBid("test:new", Order(nextId++, UUID.randomUUID().toString(), 10, 3))
        assertEquals(SellResult.Ok(0, 3, 0, true), Ledger.sell(player, "test:new", 5, 10))
        assertEquals(2, Limits.marketUsed(player))
        assertTrue(Matching.bookFor("test:new").sells.isEmpty())
    }

    @Test
    fun secondListingOfSameItemUsesNoNewSlot() {
        setup(mapOf(CountryCapacity.MARKET_SLOTS to 2))
        sellOrders(1)
        assertTrue(Ledger.sell(player, "test:new", 1, 10) is SellResult.Ok)
        assertTrue(Ledger.sell(player, "test:new", 1, 10) is SellResult.Ok)
        assertEquals(2, Limits.marketUsed(player))
        assertEquals(2, Matching.bookFor("test:new").sells.size)
    }

    @Test
    fun auctionsCountOnlyOpenOnesOfThePlayer() {
        setup(mapOf(CountryCapacity.AUCTION_SLOTS to 2))
        auctions(2)
        assertTrue(Limits.auctionFull(player))
        Auctions.cancelled(Market.data.auctions.first().id)
        assertEquals(1, Limits.auctionUsed(player))
        assertFalse(Limits.auctionFull(player))
    }
}
