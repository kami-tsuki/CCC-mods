package kami.libs.tunnel

import kami.libs.db.Db
import kami.libs.util.Time.DAY_MS
import kami.libs.util.Time.HOUR_MS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.TimeoutException

internal object Probe {
    class Refused(message: String) : Exception(message)

    private class Filter(val sql: String, val params: List<String>)

    private class Lane(val table: String, val column: String, val aggregate: String, val minStep: Long, val unit: Long, val raw: Boolean, val window: Long, val newest: Boolean = false)

    private class Cell(val subject: String, val obj: String, val at: Long, val v: Double)

    private class Sample(val subject: String, val obj: String, val v: Double)

    private class Recent(val series: Int, val rows: Long, val window: Long)

    private val LANES = mapOf(
        "value" to Lane("libs_event", "at", "AVG(t.v)", minStep = 1000L, unit = 1L, raw = true, window = DAY_MS, newest = true),
        "events" to Lane("libs_event", "at", "COUNT(*)", minStep = 1000L, unit = 1L, raw = true, window = DAY_MS),
        "sum" to Lane("libs_event", "at", "TOTAL(t.v)", minStep = 1000L, unit = 1L, raw = true, window = DAY_MS),
        "count" to Lane("libs_count", "h", "TOTAL(t.n)", minStep = HOUR_MS, unit = HOUR_MS, raw = false, window = 366 * DAY_MS)
    )

    private const val MAX_SERIES = 500
    private const val MAX_POINTS = 11_000
    private const val MAX_ROWS = 25_000
    private const val MAX_KEYS = 5000
    private const val MAX_LABELS = 1000
    private const val MAX_PAIRS = 20_000
    private const val MAX_SCAN = 3_000_000L
    private const val MAX_FILTER = 50
    private const val MAX_TEXT = 128
    private const val MAX_TIME = 4_102_444_800_000L
    private const val MAX_SPAN = 31 * DAY_MS
    private const val EVENT_LABELS_MS = DAY_MS
    private const val COUNT_LABELS_MS = 30 * DAY_MS
    private const val RECENT_MS = 6 * HOUR_MS
    private const val RECENT_HOURS = 24L
    private const val TICK_MS = 1000

    private val NONE = buildJsonObject { put("series", JsonArray(emptyList())) }

    private fun skip(table: String, name: String) =
        "$name(k) AS (SELECT MIN(k) FROM $table UNION ALL SELECT (SELECT MIN(k) FROM $table WHERE k > $name.k) FROM $name WHERE $name.k IS NOT NULL)"

    private val KEYS = "WITH RECURSIVE ${skip("libs_event", "e")}, ${skip("libs_count", "c")} " +
        "SELECT key FROM libs_key WHERE id IN (SELECT k FROM e WHERE k IS NOT NULL UNION SELECT k FROM c WHERE k IS NOT NULL) ORDER BY key LIMIT ?"

    private const val LABELS = "SELECT ks.key s, ko.key o FROM (" +
        "SELECT s, o FROM libs_event WHERE k = ? AND at >= ? GROUP BY s, o UNION SELECT s, o FROM libs_count WHERE k = ? AND h >= ? GROUP BY s, o" +
        ") p JOIN libs_key ks ON ks.id = p.s JOIN libs_key ko ON ko.id = p.o LIMIT ?"

    fun run(op: String, args: JsonObject): JsonElement = try {
        when (op) {
            "keys" -> keys()
            "labels" -> labels(args.key())
            "range" -> range(args)
            "now" -> now(args)
            else -> throw Refused("unknown op")
        }
    } catch (_: TimeoutException) {
        throw Refused("too slow")
    }

    private fun keys() = buildJsonObject {
        put("keys", JsonArray(Db.probe { query(KEYS, MAX_KEYS) { JsonPrimitive(it.string("key")) } }))
    }

    private fun labels(key: String): JsonElement {
        val id = keyId(key) ?: return buildJsonObject { put("subjects", JsonArray(emptyList())); put("objects", JsonArray(emptyList())) }
        val now = System.currentTimeMillis()
        val pairs = Db.probe { query(LABELS, id, now - EVENT_LABELS_MS, id, (now - COUNT_LABELS_MS) / HOUR_MS, MAX_PAIRS) { it.string("s") to it.string("o") } }
        return buildJsonObject {
            put("subjects", names(pairs.map { it.first }))
            put("objects", names(pairs.map { it.second }))
        }
    }

    private fun names(values: List<String>) = JsonArray(values.distinct().sorted().take(MAX_LABELS).map(::JsonPrimitive))

    private fun range(args: JsonObject): JsonElement {
        val lane = args.lane()
        val from = args.time("from")
        val to = args.time("to")
        if (to <= from) throw Refused("empty range")
        if (lane.raw && to - from > MAX_SPAN) throw Refused("range too long")
        val filter = args.filter()
        val low = from / lane.unit
        val high = ceilDiv(to, lane.unit)
        val asked = ceilDiv(maxOf(args.time("step"), lane.minStep), lane.unit)
        if (buckets(low, high, asked) > MAX_POINTS) throw Refused("too many points")
        val id = keyId(args.key()) ?: return NONE
        val recent = recent(lane, id, filter, low, high)
        val series = recent.series
        if (series > MAX_SERIES) throw Refused("too many series")
        if (lane.raw && recent.rows * (high - low) / recent.window > MAX_SCAN) throw Refused("too many samples")
        val width = maxOf(asked, ceilDiv(high - low, (MAX_ROWS / series - 1).coerceAtLeast(1).toLong()))
        val col = "t.${lane.column}"
        val sql = "SELECT ($col / ?) * ? b, ks.key s, ko.key o, ${lane.aggregate} v ${join(lane)} " +
            "WHERE t.k = ? AND $col >= ? AND $col < ?${filter.sql} GROUP BY b, t.s, t.o ORDER BY t.s, t.o, b LIMIT ?"
        val cells = Db.probe {
            query(sql, width, width, id, low, high, *filter.params.toTypedArray(), MAX_ROWS + 1) { Cell(it.string("s"), it.string("o"), it.long("b") * lane.unit, it.double("v")) }
        }
        if (cells.size > MAX_ROWS) throw Refused("too many rows")
        val grouped = cells.groupBy { it.subject to it.obj }
        if (grouped.size > MAX_SERIES) throw Refused("too many series")
        return buildJsonObject {
            put("series", buildJsonArray {
                grouped.forEach { (labels, points) ->
                    add(entry(labels.first, labels.second, "points" to buildJsonArray { points.forEach { add(buildJsonArray { add(JsonPrimitive(it.at)); add(JsonPrimitive(it.v)) }) } }))
                }
            })
        }
    }

    private fun now(args: JsonObject): JsonElement {
        val lane = args.lane()
        val to = args.time("to")
        val from = maxOf(args.time("from"), to - lane.window)
        val filter = args.filter()
        val id = keyId(args.key()) ?: return NONE
        val low = from / lane.unit
        val high = to / lane.unit
        val params = buildList<Any> {
            addAll(listOf(id, low, high))
            if (lane.newest) addAll(listOf(id, low, high))
            addAll(filter.params)
            add(MAX_SERIES + 1)
        }
        val samples = Db.probe { query(nowSql(lane, filter), *params.toTypedArray()) { Sample(it.string("s"), it.string("o"), it.double("v")) } }
        if (samples.size > MAX_SERIES) throw Refused("too many series")
        return buildJsonObject { put("series", buildJsonArray { samples.forEach { add(entry(it.subject, it.obj, "v" to JsonPrimitive(it.v))) } }) }
    }

    private fun nowSql(lane: Lane, filter: Filter): String {
        val col = "t.${lane.column}"
        val latest = " AND $col >= (SELECT MAX(n.${lane.column}) FROM ${lane.table} n WHERE n.k = ? AND n.${lane.column} >= ? AND n.${lane.column} <= ?) - $TICK_MS"
        val value = if (lane.newest) "t.v v, MAX($col) m" else "${lane.aggregate} v"
        return "SELECT ks.key s, ko.key o, $value ${join(lane)} WHERE t.k = ? AND $col >= ? AND $col <= ?${if (lane.newest) latest else ""}${filter.sql} GROUP BY t.s, t.o LIMIT ?"
    }

    private fun recent(lane: Lane, id: Int, filter: Filter, low: Long, high: Long): Recent {
        val since = maxOf(low, high - if (lane.raw) RECENT_MS else RECENT_HOURS)
        val col = "t.${lane.column}"
        val sql = "SELECT COUNT(*) c, COALESCE(SUM(n), 0) r FROM (SELECT COUNT(*) n ${join(lane)} WHERE t.k = ? AND $col >= ? AND $col < ?${filter.sql} GROUP BY t.s, t.o)"
        val found = Db.probe { one(sql, id, since, high, *filter.params.toTypedArray()) { it.int("c") to it.long("r") } }
        return Recent(maxOf(found?.first ?: 1, 1), found?.second ?: 0, maxOf(high - since, 1))
    }

    private fun entry(subject: String, obj: String, extra: Pair<String, JsonElement>) = buildJsonObject {
        put("subject", subject)
        put("object", obj)
        put(extra.first, extra.second)
    }

    private fun join(lane: Lane) = "FROM ${lane.table} t JOIN libs_key ks ON ks.id = t.s JOIN libs_key ko ON ko.id = t.o"

    private fun ceilDiv(value: Long, unit: Long) = (value + unit - 1) / unit

    private fun buckets(low: Long, high: Long, width: Long) = (high - 1) / width - low / width + 1

    private fun keyId(key: String) = Db.probe { one("SELECT id FROM libs_key WHERE key = ?", key) { it.int("id") } }

    private fun JsonObject.key() = text("key", MAX_TEXT) ?: throw Refused("bad key")

    private fun JsonObject.time(name: String) = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..MAX_TIME } ?: throw Refused("bad $name")

    private fun JsonObject.lane() = LANES[text("kind", MAX_TEXT)] ?: throw Refused("bad kind")

    private fun JsonObject.list(name: String): List<String>? {
        val raw = this[name]?.takeUnless { it is JsonNull } ?: return null
        val items = (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf { s -> s.length <= MAX_TEXT } ?: throw Refused("bad $name") }
        return items?.takeIf { it.size <= MAX_FILTER } ?: throw Refused("bad $name")
    }

    private fun JsonObject.filter(): Filter {
        val subjects = list("subjects")
        val objects = list("objects")
        val sql = (if (subjects != null) " AND ks.key IN (SELECT value FROM json_each(?))" else "") + (if (objects != null) " AND ko.key IN (SELECT value FROM json_each(?))" else "")
        return Filter(sql, listOfNotNull(subjects, objects).map { items -> JsonArray(items.map(::JsonPrimitive)).toString() })
    }
}

internal fun JsonObject.text(name: String, max: Int = Int.MAX_VALUE): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length in 1..max }
