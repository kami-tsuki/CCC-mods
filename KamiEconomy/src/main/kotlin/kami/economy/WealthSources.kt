package kami.economy

import kami.economy.economy.AuctionState
import kami.economy.economy.Matching
import kami.libs.economy.Wealth

internal object WealthSources {
    fun register() {
        Wealth.source("escrow.bids") { Market.data.books.values.sumOf { b -> b.buys.filter { !it.synthetic }.sumOf { Matching.escrow(it) } } }
        Wealth.source("escrow.auctions") {
            Market.data.auctions.filter { it.state == AuctionState.OPEN && it.currentBidder.isNotEmpty() }.sumOf { it.currentBid.toLong() }
        }
    }
}
