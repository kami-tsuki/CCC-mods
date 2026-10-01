package kami.economy.economy

import kami.economy.Config
import kami.economy.Market
import kami.libs.claims.ClaimsApi
import kami.libs.claims.CountryCapacity
import kami.libs.claims.Locks
import kami.libs.text.Phrase
import java.util.UUID

object Limits {
    fun marketSlots(player: String): Int = slots(player, CountryCapacity.MARKET_SLOTS, Config.s.marketSlots)

    fun auctionSlots(player: String): Int = slots(player, CountryCapacity.AUCTION_SLOTS, Config.s.auctionSlots)

    fun marketUsed(player: String): Int = Market.data.books.values.sumOf { book -> book.sells.count { it.owner == player } + book.buys.count { it.owner == player } }

    fun auctionUsed(player: String): Int = Auctions.open().count { it.seller == player }

    fun marketFull(player: String) = marketUsed(player) >= marketSlots(player)

    fun auctionFull(player: String) = auctionUsed(player) >= auctionSlots(player)

    fun marketDenial(player: String): Phrase = denial(player, CountryCapacity.MARKET_SLOTS, "kami_economy.slots.orders", marketSlots(player))

    fun auctionDenial(player: String): Phrase = denial(player, CountryCapacity.AUCTION_SLOTS, "kami_economy.slots.auctions", auctionSlots(player))

    fun hint(player: String, key: String, max: Int): String =
        runCatching { UUID.fromString(player) }.getOrNull()?.let { ClaimsApi.limit(it, key, max)?.json() } ?: ""

    private fun denial(player: String, key: String, name: String, max: Int): Phrase =
        runCatching { UUID.fromString(player) }.getOrNull()?.let { ClaimsApi.limit(it, key, max) } ?: Locks.capacity(Phrase.of(name), max, null, null)

    private fun slots(player: String, key: String, fallback: Int): Int =
        runCatching { UUID.fromString(player) }.getOrNull()?.let { ClaimsApi.capacity(it, key, fallback) } ?: fallback
}
