package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.economy.StarterGood
import kami.libs.claims.Citizenship
import kami.libs.claims.ClaimInfo
import kami.libs.claims.ClaimsApi
import kami.libs.claims.ClaimsProvider
import kami.libs.claims.CountryCapacity
import kami.libs.text.Phrase
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocksTest {
    private object Claims : ClaimsProvider {
        override fun at(dim: String, x: Int, z: Int): ClaimInfo? = null
        override fun isBanished(player: UUID, country: String) = false
        override fun countryOf(player: UUID) = "aurelia"
        override fun citizenship(player: UUID): Citizenship? = null
        override fun lock(player: UUID, feature: String) = Phrase.of("test.locked", feature)
    }

    private val player = UUID.randomUUID()

    private fun setup(levelLocks: Boolean = false) {
        Config.s = Settings(levelLocks = levelLocks, starterGoods = listOf(StarterGood("test:iron_ingot", 40)))
        Market.data = Data()
        Stocks.index { emptyList() }
        ClaimsApi.register(Claims)
    }

    @AfterTest
    fun clear() = ClaimsApi.register(null)

    @Test
    fun usedCountsOrdersAndAuctionsAndValueUsesTheBasePrice() {
        setup()
        Matching.insertSell("test:a", Order(1, player.toString(), 10, 5))
        Auctions.insert(2, player.toString(), "", "x", 10, null)
        assertEquals(1, EconomyProvider.used(player, CountryCapacity.MARKET_SLOTS))
        assertEquals(1, EconomyProvider.used(player, CountryCapacity.AUCTION_SLOTS))
        assertEquals(0, EconomyProvider.used(player, CountryCapacity.CHUNKS))
        assertEquals(40L, EconomyProvider.valueOf("test:iron_ingot", 64))
        assertEquals(20L, EconomyProvider.valueOf("test:iron_ingot", 32))
        assertEquals(0L, EconomyProvider.valueOf("test:unknown", 8))
    }

    @Test
    fun featureLocksAreIgnoredWhileLevelLocksIsOff() {
        setup()
        assertNull(Gate.denial(player, "auction_list"))
        assertNull(Gate.locked(player, Gate.VENDORS))
    }

    @Test
    fun featureLocksApplyWhenLevelLocksIsOn() {
        setup(levelLocks = true)
        assertNotNull(Gate.denial(player, "auction_bid"))
        assertNotNull(Gate.locked(player, Gate.VENDORS))
        assertNull(Gate.denial(player, "sell"))
    }
}
