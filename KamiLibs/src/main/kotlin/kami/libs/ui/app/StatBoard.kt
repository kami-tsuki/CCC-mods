package kami.libs.ui.app

import kami.libs.admin.StatResult
import kami.libs.admin.StatsReply
import kami.libs.admin.StatsViews
import kami.libs.util.Time

private const val STATS_REFRESH_MS = 15_000L

class StatQuery internal constructor(internal val index: Int, private val head: String, private val tail: String) {
    fun arg(from: Long?) = "$head|${from ?: ""}|$tail"
}

class StatBoard(private val range: TimeRange, refreshMs: Long = STATS_REFRESH_MS) {
    private val queries = ArrayList<StatQuery>()
    val remote = Remote(StatsViews.QUERY, StatsReply.serializer(), refreshMs) { range.from.let { from -> queries.map { it.arg(from) } } }
    private val shown get() = remote.data ?: remote.latest
    val ready get() = shown != null
    val stale get() = remote.data == null && remote.latest != null
    val bucket get() = shown?.bucket ?: Time.HOUR_MS

    fun poll() = remote.poll()

    operator fun get(q: StatQuery) = shown?.results?.getOrNull(q.index) ?: StatResult()

    fun series(key: String, agg: String = "avg", subject: String = "", obj: String = "") = add("$key|series|$agg|$subject|", obj)

    fun counted(key: String, subject: String = "", obj: String = "") = add("$key|counted||$subject|", obj)

    fun total(key: String, subject: String = "", obj: String = "") = add("$key|total||$subject|", obj)

    fun distinct(key: String, obj: String = "") = add("$key|distinct|||", obj)

    fun top(key: String, by: String = "s", subject: String = "", obj: String = "") = add("$key|top||$subject|$by", obj)

    fun latest(key: String) = add("$key|latest|||", "")

    private fun add(head: String, tail: String) = StatQuery(queries.size, head, tail).also { queries += it }
}
