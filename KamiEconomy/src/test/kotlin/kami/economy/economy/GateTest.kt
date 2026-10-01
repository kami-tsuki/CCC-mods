package kami.economy.economy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GateTest {
    private val mutating = listOf("sell", "sell_market", "bid", "reprice", "reprice_bid", "buy", "auction_list", "auction_bid", "auction_buy")
    private val recovery = listOf("open", "close", "search", "detail", "quote", "auctions", "cancel", "cancel_bid", "auction_cancel", "claim")

    @Test
    fun nonCitizensAreBlockedFromTrading() = mutating.forEach { assertTrue(Gate.blocked(it, false), it) }

    @Test
    fun nonCitizensKeepBrowsingAndRecovery() = recovery.forEach { assertFalse(Gate.blocked(it, false), it) }

    @Test
    fun citizensAreNeverBlocked() = (mutating + recovery).forEach { assertFalse(Gate.blocked(it, true), it) }
}
