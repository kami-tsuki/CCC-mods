package kami.claims.press

import kami.libs.db.Db
import kami.libs.util.Time
import kami.claims.now
import kotlinx.serialization.Serializable

@Serializable
private class Pending(val day: Long, val items: List<List<String>>)

object NewsDigest {
    private const val KEY = "digest"
    private const val MAX_ITEMS = 500
    private const val MAX_PER_TEMPLATE = 40
    private val pending = Pending.serializer()

    private fun load() = NewsStore.state(KEY)?.let { Db.json.decodeFromString(pending, it) }

    internal fun accepts(items: List<List<String>>, item: List<String>) =
        items.size < MAX_ITEMS && item !in items && items.count { it.firstOrNull() == item.firstOrNull() } < MAX_PER_TEMPLATE

    fun add(item: List<String>) {
        val day = now() / Time.DAY_MS
        val current = load()
        val items = current?.items.orEmpty()
        if (!accepts(items, item)) return
        NewsStore.putState(KEY, Db.json.encodeToString(pending, Pending(current?.day ?: day, items + listOf(item))))
    }

    fun flush() {
        val current = load() ?: return
        if (current.day >= now() / Time.DAY_MS) return
        NewsStore.putState(KEY, Db.json.encodeToString(pending, Pending(now() / Time.DAY_MS, emptyList())))
        if (current.items.isEmpty()) return
        News.system("digest", emptyList(), current.items.map { it.joinToString(PressRules.UNIT.toString()) })
    }
}
