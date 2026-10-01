package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Settings
import kami.libs.claims.ClaimsApi
import kami.libs.claims.FeatureIds
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocksTest {
    private val player = UUID.randomUUID()

    private fun setup(levelLocks: Boolean = false) {
        Config.s = Settings(levelLocks = levelLocks)
        Market.data = Data()
        ClaimsApi.register(Fakes.claims().apply { locked = levelLocks })
    }

    @AfterTest
    fun clear() = ClaimsApi.register(null)

    @Test
    fun featureLocksAreIgnoredWhileLevelLocksIsOff() {
        setup()
        assertNull(Gate.denial(player, "auction_list"))
        assertNull(Gate.locked(player, FeatureIds.VENDORS))
    }

    @Test
    fun featureLocksApplyWhenLevelLocksIsOn() {
        setup(levelLocks = true)
        assertNotNull(Gate.denial(player, "auction_bid"))
        assertNotNull(Gate.locked(player, FeatureIds.VENDORS))
        assertNull(Gate.denial(player, "sell"))
    }
}
