package kami.economy.economy

import kotlinx.serialization.Serializable

enum class TradeFlag {
    BUY, SELL, INSTANT, ORDER, AUCTION;

    companion object {
        private val ACTS = mapOf(
            "buy" to setOf(BUY, INSTANT), "sell_market" to setOf(SELL, INSTANT),
            "bid" to setOf(BUY, ORDER), "reprice_bid" to setOf(BUY, ORDER), "sell" to setOf(SELL, ORDER), "reprice" to setOf(SELL, ORDER),
            "auction_list" to setOf(SELL, AUCTION), "auction_bid" to setOf(BUY, AUCTION), "auction_buy" to setOf(BUY, AUCTION)
        )

        fun blocks(action: String, flags: Set<TradeFlag>): Boolean = ACTS[action]?.any { it in flags } == true

        fun parse(csv: String): Set<TradeFlag> = csv.split(',').mapNotNullTo(LinkedHashSet()) { s -> entries.firstOrNull { it.name.equals(s.trim(), true) } }

        fun csv(flags: Set<TradeFlag>): String = entries.filter { it in flags }.joinToString(",") { it.name.lowercase() }
    }
}

@Serializable
class ItemStatus(val flags: Set<TradeFlag>, val reason: String, val by: String, val at: Long)
