package kami.economy.economy

import kami.economy.Config
import kami.economy.LOG
import kami.economy.StarterGood
import kami.libs.config.Configs

object Catalog {
    private const val RESOURCE = "/kami_economy/starter_goods.json"
    private const val OLD_LOT = 64
    private val oldIngotTags = setOf("#c:ingots/iron", "#c:ingots/copper", "#c:ingots/zinc", "#c:ingots/lead", "#c:ingots/gold")

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
}
