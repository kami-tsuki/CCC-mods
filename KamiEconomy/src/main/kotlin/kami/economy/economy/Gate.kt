package kami.economy.economy

import kami.economy.Config
import kami.libs.claims.ClaimsApi
import kami.libs.claims.FeatureIds
import kami.libs.claims.Locks
import kami.libs.text.Phrase
import java.util.UUID

object Gate {
    private val TRADING = setOf("sell", "sell_market", "bid", "reprice", "reprice_bid", "buy", "auction_list", "auction_bid", "auction_buy")
    private val AUCTION_ACTIONS = setOf("auction_list", "auction_bid", "auction_buy")

    internal fun blocked(action: String, citizen: Boolean) = !citizen && action in TRADING

    private fun feature(action: String): String? = if (action in AUCTION_ACTIONS) FeatureIds.AUCTIONS else null

    fun locked(player: UUID, feature: String?): Phrase? =
        if (Config.s.levelLocks && feature != null) ClaimsApi.lock(player, feature) else null

    fun denial(player: UUID, action: String): Phrase? =
        if (blocked(action, ClaimsApi.isCitizen(player))) Locks.noCountry() else locked(player, feature(action))
}
