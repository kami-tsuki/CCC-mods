package kami.economy.economy

import kami.economy.Book
import kami.economy.Config
import kami.economy.Market
import kotlin.math.max
import kotlin.math.min

object Pricing {
    fun tick() {
        Market.data.books.values.forEach { recompute(it) }
        Market.dirty = true
    }

    private fun recompute(book: Book) {
        val fills = book.recentFills
        val open = if (book.midPrice > 0.0) book.midPrice else fills.firstOrNull()?.price?.toDouble() ?: 0.0

        if (fills.isNotEmpty()) {
            val totalQty = fills.sumOf { it.qty }
            if (totalQty > 0) {
                val weighted = fills.sumOf { it.price.toDouble() * it.qty } / totalQty
                val base = if (book.midPrice <= 0.0) weighted else book.midPrice
                val maxMove = base * (Config.s.categoryBounds[book.item]?.maxMovePct ?: Config.s.maxPriceMovePct)
                book.midPrice = base + (weighted - base).coerceIn(-maxMove, maxMove)
            }
        }

        val override = Config.s.categoryBounds[book.item]
        val floor = override?.floor ?: Config.s.defaultFloor
        val ceiling = override?.ceiling ?: Config.s.defaultCeiling
        if (book.midPrice < floor) book.midPrice = floor.toDouble()
        if (ceiling != null && book.midPrice > ceiling) book.midPrice = ceiling.toDouble()

        val endless = Config.s.endlessByItem[book.item]
        if (endless != null) {
            val synth = book.sells.firstOrNull { it.synthetic }
            if (synth != null) {
                var price = if (book.midPrice > 0.0) book.midPrice * endless.synthPremiumFactor else endless.floorPrice.toDouble()
                if (price < endless.floorPrice) price = endless.floorPrice.toDouble()
                if (ceiling != null && price > ceiling) price = ceiling.toDouble()
                synth.price = price.toInt().coerceAtLeast(1)
                synth.amount = Int.MAX_VALUE
            }
        }

        if (fills.isNotEmpty()) {
            val close = book.midPrice
            val high = max(fills.maxOf { it.price }.toDouble(), max(open, close))
            val low = min(fills.minOf { it.price }.toDouble(), min(open, close))
            val volume = fills.sumOf { it.qty }.toLong()
            History.record(book.item, open, high, low, close, volume)
            fills.clear()
        }
    }
}
