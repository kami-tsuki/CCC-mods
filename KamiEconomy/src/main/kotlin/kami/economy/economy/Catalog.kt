package kami.economy.economy

import kami.economy.Config
import kami.economy.Fill
import kami.economy.LOG
import kami.economy.Market
import kami.economy.Order
import kami.economy.StarterGood
import kami.libs.config.Configs
import kotlin.math.ceil
import kotlin.math.roundToInt

object Catalog {
    private const val RESOURCE = "/kami_economy/starter_goods.json"
    private const val OLD_LOT = 64
    private val oldIngotTags = setOf("#c:ingots/iron", "#c:ingots/copper", "#c:ingots/zinc", "#c:ingots/lead", "#c:ingots/gold")
    private val ingots = listOf("minecraft:iron_ingot", "minecraft:copper_ingot", "create:zinc_ingot", "tfmg:lead_ingot", "minecraft:gold_ingot")

    private fun versions(): Map<Int, List<StarterGood>> = runCatching {
        val text = Catalog::class.java.getResourceAsStream(RESOURCE)!!.use { it.readBytes().decodeToString() }
        Configs.json().decodeFromString<Map<String, List<StarterGood>>>(text).mapKeys { it.key.toInt() }
    }.getOrElse {
        LOG.error("Could not read the bundled starter catalog {}", RESOURCE, it)
        emptyMap()
    }

    fun merge(resolve: (String) -> List<String>) {
        val s = Config.s
        val fresh = versions().filterKeys { it > s.catalog }.toSortedMap()
        if (fresh.isEmpty()) return
        fun ids(g: StarterGood) = if (g.item.startsWith("#")) resolve(g.item.drop(1)) else listOf(g.item)
        var goods = s.starterGoods
        if (s.catalog < 2) goods = goods.filterNot { it.item in oldIngotTags && it.lot == OLD_LOT }
        val covered = goods.flatMapTo(HashSet(), ::ids)
        fresh.values.flatten().forEach { g ->
            val expanded = ids(g)
            if (expanded.none { it in covered }) {
                goods = goods + g
                covered += expanded
            }
        }
        Config.save(s.copy(starterGoods = goods, catalog = fresh.lastKey()))
    }

    fun relot() {
        val data = Market.data
        if (data.version >= 1) return
        if (ingots.any { Stocks.good(it)?.lot == OLD_LOT }) return
        val cancels = ArrayList<Triple<String, String, Long>>()
        ingots.forEach { item ->
            val good = Stocks.good(item) ?: return@forEach
            if (good.lot !in 1 until OLD_LOT || OLD_LOT % good.lot != 0) return@forEach
            val f = OLD_LOT / good.lot
            data.stocks[item]?.let { s ->
                if (s.base > 0) s.observed *= good.base / s.base
                s.base = good.base.toDouble()
                s.lots = (s.lots * f).coerceAtMost(Stocks.limit(item))
            }
            Market.peek(item)?.let { book ->
                book.buys.filter { it.lot == OLD_LOT && it.price % f != 0 }.forEach { cancels += Triple(it.owner, item, it.id) }
                book.buys.replaceAll { if (it.lot == OLD_LOT && it.price % f == 0) Order(it.id, it.owner, it.price / f, it.amount, it.placedAt, it.synthetic, good.lot) else it }
                book.sells.replaceAll { if (it.lot == OLD_LOT) Order(it.id, it.owner, ceil(it.price.toDouble() / f).toInt(), it.amount, it.placedAt, it.synthetic, good.lot) else it }
                book.lastFill = (book.lastFill.toDouble() / f).roundToInt()
                book.midPrice /= f
                book.recentFills.replaceAll { Fill((it.price.toDouble() / f).roundToInt(), it.qty, it.at) }
            }
            History.scale(item, 1.0 / f)
        }
        data.version = 1
        Market.dirty = true
        cancels.forEach { (owner, item, id) ->
            LOG.info("Cancelling {}'s {} bid, its price does not fit the new lot size", owner, item)
            Ledger.cancelBid(owner, item, id)
        }
    }
}
