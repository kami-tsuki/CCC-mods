package kami.economy.economy

import kami.libs.util.toUuidOrNull
import kami.economy.Config
import kami.economy.Market
import kami.libs.claims.ClaimsApi
import kami.libs.claims.CountryCapacity
import kami.libs.claims.Locks
import kami.libs.skills.Skills
import kami.libs.text.Phrase

object Limits {
    fun marketSlots(player: String): Int = slots(player, CountryCapacity.MARKET_SLOTS, Config.s.marketSlots) + bonus(player, Skills.ORDER_SLOTS)

    fun auctionSlots(player: String): Int = slots(player, CountryCapacity.AUCTION_SLOTS, Config.s.auctionSlots) + bonus(player, Skills.AUCTION_SLOTS)

    fun marketUsed(player: String): Int = Market.data.books.values.sumOf { book -> listOf(book.sells, book.buys).count { orders -> orders.any { it.owner == player } } }

    fun auctionUsed(player: String): Int = Auctions.open().count { it.seller == player }

    fun marketFull(player: String) = marketUsed(player) >= marketSlots(player)

    fun auctionFull(player: String) = auctionUsed(player) >= auctionSlots(player)

    fun marketDenial(player: String): Phrase = denial(player, CountryCapacity.MARKET_SLOTS, "kami_economy.slots.orders", marketUsed(player), marketSlots(player))

    fun auctionDenial(player: String): Phrase = denial(player, CountryCapacity.AUCTION_SLOTS, "kami_libs.common.auctions", auctionUsed(player), auctionSlots(player))

    fun hint(player: String, key: String, used: Int): String = limit(player, key, used)?.json() ?: ""

    private fun limit(player: String, key: String, used: Int): Phrase? = player.toUuidOrNull()?.let { ClaimsApi.limit(it, key, (used - bonus(player, if (key == CountryCapacity.AUCTION_SLOTS) Skills.AUCTION_SLOTS else Skills.ORDER_SLOTS)).coerceAtLeast(0)) }

    private fun denial(player: String, key: String, name: String, used: Int, max: Int): Phrase = limit(player, key, used) ?: Locks.capacity(Phrase.of(name), max, null, null)

    fun bonus(player: String, key: String): Int = player.toUuidOrNull()?.let { Skills.value(it, key).toInt() } ?: 0

    private fun slots(player: String, key: String, fallback: Int): Int = player.toUuidOrNull()?.let { ClaimsApi.capacity(it, key, fallback) } ?: fallback
}
