package kami.economy.economy

import kami.economy.Config
import kami.economy.Market
import kami.economy.StarterGood
import kami.economy.net.Net
import kami.economy.sane
import kami.libs.admin.AdminFailure

object Starters {
    fun rev(goods: List<StarterGood> = Config.s.starterGoods): String = Integer.toHexString(goods.hashCode())

    fun items(key: String): List<String> = Stocks.items().filter { Stocks.good(it)?.item == key }

    fun upsert(good: StarterGood, rev: String): Pair<StarterGood?, StarterGood> {
        val goods = current(rev)
        val before = goods.firstOrNull { it.item == good.item }
        val next = Config.s.copy(starterGoods = if (before == null) goods + good else goods.map { if (it.item == good.item) good else it }).sane()
        val saved = next.starterGoods.firstOrNull { it.item == good.item } ?: throw AdminFailure("kami_economy.admin.invalid_good")
        apply(next.starterGoods)
        if (before?.infinite == true && !saved.infinite) {
            items(saved.item).forEach { Market.data.stocks[it]?.lots = saved.target }
            Market.dirty = true
        }
        push()
        return before to saved
    }

    fun remove(key: String, rev: String): StarterGood {
        val goods = current(rev)
        val before = goods.firstOrNull { it.item == key } ?: throw AdminFailure("kami_economy.admin.unknown_good", listOf(key))
        val items = items(key)
        apply(goods - before)
        items.filter { Stocks.good(it) == null }.forEach { item ->
            Market.data.stocks.remove(item)
            Market.peek(item)?.let { b -> b.sells.removeAll { it.synthetic }; b.buys.removeAll { it.synthetic } }
        }
        Market.dirty = true
        push()
        return before
    }

    fun push() {
        val server = Notify.server ?: return
        server.playerList.players.forEach(Net::sendFullPrices)
        Net.broadcastOpen(server)
    }

    private fun current(rev: String): List<StarterGood> = Config.s.starterGoods.also { if (rev(it) != rev) throw AdminFailure("kami_economy.admin.stale") }

    private fun apply(goods: List<StarterGood>) {
        Config.save(Config.s.copy(starterGoods = goods).sane())
        Stocks.reindex()
    }
}
