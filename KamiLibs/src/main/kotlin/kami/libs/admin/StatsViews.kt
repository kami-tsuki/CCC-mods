package kami.libs.admin

import kami.libs.db.Db
import kami.libs.stats.Stats
import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
class StatPoint(val at: Long, val v: Double)

@Serializable
class StatRow(val key: String, val v: Long, val name: String = "")

@Serializable
class StatResult(val points: List<StatPoint> = emptyList(), val rows: List<StatRow> = emptyList(), val total: Double = 0.0)

@Serializable
class StatsReply(val bucket: Long, val results: List<StatResult>)

object StatsViews {
    const val QUERY = "stats.query"
    private const val MAX_REQUESTS = 60
    private const val TOP_ROWS = 10
    private const val LATEST_ROWS = 50
    val resolvers = CopyOnWriteArrayList<(String) -> String?>()

    internal fun register() {
        Admin.view(QUERY) { ctx, a ->
            val bucket = Stats.bucket(a.firstOrNull()?.split('|')?.long(5))
            val server = ctx.player.server
            Db.json.encodeToJsonElement(StatsReply.serializer(), StatsReply(bucket, a.take(MAX_REQUESTS).map { run(server, it.split('|'), bucket) }))
        }
    }

    private fun run(server: MinecraftServer, p: List<String>, bucket: Long): StatResult {
        val key = p.text(0) ?: return StatResult()
        val from = p.long(5)
        val subject = p.text(3)
        val obj = p.text(6)
        return when (p.text(1)) {
            "series" -> StatResult(points = Stats.series(key, from, agg(p.text(2)), subject, bucket, obj).map { StatPoint(it.at, it.v) })
            "counted" -> StatResult(points = Stats.counted(key, from, subject, bucket, obj).map { StatPoint(it.at, it.v) })
            "total" -> StatResult(total = Stats.total(key, from, subject, obj).toDouble())
            "distinct" -> StatResult(total = Stats.distinct(key, from, obj).toDouble())
            "top" -> StatResult(rows = Stats.top(key, from, p.text(4) ?: "s", subject, TOP_ROWS, obj).map { row(server, it.key, it.v) })
            "latest" -> StatResult(rows = Stats.latest(key).entries.sortedByDescending { it.value }.take(LATEST_ROWS).map { row(server, it.key, it.value) })
            else -> StatResult()
        }
    }

    private fun agg(raw: String?) = Stats.Agg.entries.firstOrNull { it.name.equals(raw, true) } ?: Stats.Agg.AVG

    private fun row(server: MinecraftServer, key: String, v: Long) = StatRow(key, v, name(server, key))

    private fun name(server: MinecraftServer, key: String): String {
        if (!key.startsWith("p:")) return resolvers.firstNotNullOfOrNull { it(key) }.orEmpty()
        val id = runCatching { UUID.fromString(key.substring(2)) }.getOrNull() ?: return ""
        return Players.profile(server, id).name.takeUnless { it == id.toString() }.orEmpty()
    }
}
