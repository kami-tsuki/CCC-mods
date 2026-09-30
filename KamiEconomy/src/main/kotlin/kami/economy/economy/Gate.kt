package kami.economy.economy

object Gate {
    val TRADING = setOf("sell", "sell_market", "bid", "reprice", "reprice_bid", "buy", "auction_list", "auction_bid", "auction_buy")

    fun blocked(action: String, citizen: Boolean) = !citizen && action in TRADING
}
