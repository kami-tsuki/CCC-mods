package kami.economy.economy

import kami.economy.now
import kami.libs.db.Db
import kami.libs.stats.Stats

object Watchlist {
    const val CAP = 100
    private val lists = HashMap<String, LinkedHashMap<String, Long>>()

    fun load() {
        lists.clear()
        Db.read { query("SELECT player, item, at FROM eco_watch ORDER BY at, rowid") { Triple(it.string("player"), it.string("item"), it.long("at")) } }
            .forEach { (player, item, at) -> lists.getOrPut(player) { LinkedHashMap() }[item] = at }
    }

    fun canonicalize() = lists.toList().forEach { (player, list) ->
        list.entries.filter { Variants.of(it.key) != null }.map { it.key to it.value }.forEach { (variant, at) ->
            val core = Variants.core(variant)
            if (core !in list) {
                list[core] = at
                Db.write { exec("INSERT OR REPLACE INTO eco_watch (player, item, at) VALUES (?, ?, ?)", player, core, at) }
                Stats.add("market.watch", "p:$player", core, 1)
            }
            remove(player, variant)
        }
    }

    fun of(player: String): Collection<String> = lists[player]?.keys.orEmpty()

    fun has(player: String, item: String) = lists[player]?.containsKey(item) == true

    fun forEach(action: (String, Set<String>) -> Unit) = lists.forEach { (player, list) -> action(player, list.keys) }

    fun count(item: String) = lists.values.count { item in it }

    fun add(player: String, item: String): Boolean {
        val list = lists.getOrPut(player) { LinkedHashMap() }
        if (item in list || list.size >= CAP) return false
        val at = now()
        list[item] = at
        Db.write { exec("INSERT OR REPLACE INTO eco_watch (player, item, at) VALUES (?, ?, ?)", player, item, at) }
        Stats.add("market.watch", "p:$player", item, 1)
        return true
    }

    fun remove(player: String, item: String): Boolean {
        val list = lists[player] ?: return false
        if (list.remove(item) == null) return false
        if (list.isEmpty()) lists.remove(player)
        Db.write { exec("DELETE FROM eco_watch WHERE player = ? AND item = ?", player, item) }
        Stats.add("market.watch", "p:$player", item, -1)
        return true
    }

    fun import(player: String, items: List<String>): Int =
        if (lists[player].isNullOrEmpty()) items.count { add(player, it) } else 0

    fun summary(top: Int): WatchSummary {
        val counts = HashMap<String, Int>()
        var total = 0
        lists.values.forEach { l -> total += l.size; l.keys.forEach { counts.merge(it, 1, Int::plus) } }
        return WatchSummary(total, lists.size, counts.size, counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(top).map { it.key to it.value })
    }
}

class WatchSummary(val total: Int, val players: Int, val items: Int, val top: List<Pair<String, Int>>)
