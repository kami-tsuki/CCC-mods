package kami.libs.stats

import kami.libs.LibConfig
import kami.libs.db.Db
import kami.libs.db.Migration
import kami.libs.util.Time.DAY_MS
import kami.libs.util.Time.HOUR_MS
import java.util.concurrent.ConcurrentHashMap

object Stats {
    enum class Agg(val sql: String) {
        SUM("SELECT (at / ?) * ? b, SUM(v) v FROM libs_event WHERE k = ? AND at >= ?$FILTER GROUP BY b ORDER BY b"),
        AVG("SELECT (at / ?) * ? b, AVG(v) v FROM libs_event WHERE k = ? AND at >= ?$FILTER GROUP BY b ORDER BY b"),
        MAX("SELECT (at / ?) * ? b, MAX(v) v FROM libs_event WHERE k = ? AND at >= ?$FILTER GROUP BY b ORDER BY b"),
        COUNT("SELECT (at / ?) * ? b, COUNT(*) v FROM libs_event WHERE k = ? AND at >= ?$FILTER GROUP BY b ORDER BY b"),
        DISTINCT("SELECT (at / ?) * ? b, COUNT(DISTINCT s) v FROM libs_event WHERE k = ? AND at >= ?$FILTER GROUP BY b ORDER BY b")
    }

    class Point(val at: Long, val v: Double)
    class Top(val key: String, val v: Long)

    private data class Cell(val h: Long, val k: Int, val s: Int, val o: Int)

    internal val migration = Migration(
        "libs", 7, "stats",
        ddl = listOf(
            "CREATE TABLE libs_key (id INTEGER PRIMARY KEY, key TEXT NOT NULL UNIQUE)",
            "CREATE TABLE libs_event (at INTEGER NOT NULL, k INTEGER NOT NULL, s INTEGER NOT NULL, o INTEGER NOT NULL, v INTEGER NOT NULL)",
            "CREATE INDEX libs_event_k_at ON libs_event (k, at)",
            "CREATE INDEX libs_event_s_at ON libs_event (s, at)",
            "CREATE TABLE libs_count (h INTEGER NOT NULL, k INTEGER NOT NULL, s INTEGER NOT NULL, o INTEGER NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (k, h, s, o)) WITHOUT ROWID",
            "CREATE INDEX libs_count_s ON libs_count (s, k, h)"
        )
    )

    private val ids = ConcurrentHashMap<String, Int>()
    private val names = ConcurrentHashMap<Int, String>()
    private val pending = HashMap<Cell, Long>()
    private val gauges = LinkedHashMap<String, () -> Map<String, Long>>()
    private var sampled = 0L
    @Volatile
    private var origin: Long? = null
    private var flushed = 0L

    fun load() {
        origin = null
        ids.clear(); names.clear(); synchronized(pending) { pending.clear() }
        Db.read { query("SELECT id, key FROM libs_key") { it.int("id") to it.string("key") } }.forEach { (i, k) -> ids[k] = i; names[i] = k }
        sampled = System.currentTimeMillis()
    }

    fun on(key: String) = LibConfig.s.stats[key.substringBefore('.')] != false

    fun event(key: String, subject: String = "", obj: String = "", v: Long = 1, at: Long = System.currentTimeMillis()) {
        if (!Db.isOpen || !on(key)) return
        val row = listOf<Any>(at, id(key), id(subject), id(obj), v)
        Db.write { exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, ?, ?, ?, ?)", *row.toTypedArray()) }
    }

    fun add(key: String, subject: String = "", obj: String = "", n: Long = 1) {
        if (n == 0L || !Db.isOpen || !on(key)) return
        val cell = Cell(System.currentTimeMillis() / HOUR_MS, id(key), id(subject), id(obj))
        synchronized(pending) { pending.merge(cell, n, Long::plus) }
    }

    fun gauge(key: String, sample: () -> Map<String, Long>) {
        synchronized(gauges) { gauges[key] = sample }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        if (now - flushed >= 60_000) flush(now)
        if (now - sampled < LibConfig.s.statsSampleSeconds * 1000L) return
        sampled = now
        synchronized(gauges) { gauges.toList() }.filter { on(it.first) }.forEach { (key, sample) ->
            runCatching(sample).getOrNull()?.forEach { (s, v) -> event(key, s, v = v, at = now) }
        }
    }

    fun flush(now: Long = System.currentTimeMillis()) {
        flushed = now
        if (!Db.isOpen) return
        val rows = synchronized(pending) { pending.map { (c, n) -> listOf(c.h, c.k, c.s, c.o, n) }.also { pending.clear() } }
        if (rows.isNotEmpty()) Db.write {
            batch("INSERT INTO libs_count (h, k, s, o, n) VALUES (?, ?, ?, ?, ?) ON CONFLICT (k, h, s, o) DO UPDATE SET n = n + excluded.n", rows)
        }
    }

    fun bucket(from: Long?, now: Long = System.currentTimeMillis()): Long = when (now - (from ?: first())) {
        in 0..DAY_MS -> HOUR_MS
        in 0..7 * DAY_MS -> 6 * HOUR_MS
        in 0..120 * DAY_MS -> DAY_MS
        else -> 7 * DAY_MS
    }

    fun first(): Long = origin ?: Db.read { one("SELECT MIN(at) m FROM libs_event") { it.longOrNull("m") } }.also { origin = it } ?: System.currentTimeMillis()

    private const val FILTER = " AND (? IS NULL OR s = ?) AND (? IS NULL OR o = ?)"
    private const val COUNTED = "SELECT (h / ?) * ? b, SUM(n) v FROM libs_count WHERE k = ? AND h >= ?$FILTER GROUP BY b ORDER BY b"
    private const val TOTAL = "SELECT COALESCE(SUM(n), 0) v FROM libs_count WHERE k = ? AND h >= ?$FILTER"
    private const val TOP_S = "SELECT s g, SUM(n) v FROM libs_count WHERE k = ? AND h >= ?$FILTER GROUP BY g ORDER BY v DESC LIMIT ?"
    private const val TOP_O = "SELECT o g, SUM(n) v FROM libs_count WHERE k = ? AND h >= ?$FILTER GROUP BY g ORDER BY v DESC LIMIT ?"

    private fun args(vararg head: Any, s: Int?, o: Int?, tail: List<Any> = emptyList()): Array<Any?> = arrayOf(*head, s, s, o, o, *tail.toTypedArray())

    fun series(key: String, from: Long?, agg: Agg = Agg.AVG, subject: String? = null, bucket: Long = bucket(from), obj: String? = null): List<Point> {
        val k = ids[key] ?: return emptyList()
        val s = subject?.let { ids[it] ?: return emptyList() }
        val o = obj?.let { ids[it] ?: return emptyList() }
        return Db.read {
            query(
agg.sql, *args(bucket, bucket, k, from ?: 0L, s = s, o = o)) { Point(it.long("b"), it.double("v")) }
        }
    }

    fun counted(key: String, from: Long?, subject: String? = null, bucket: Long = bucket(from), obj: String? = null): List<Point> {
        flush()
        val k = ids[key] ?: return emptyList()
        val s = subject?.let { ids[it] ?: return emptyList() }
        val o = obj?.let { ids[it] ?: return emptyList() }
        val hours = bucket / HOUR_MS
        return Db.read {
            query(
COUNTED, *args(hours, hours, k, (from ?: 0L) / HOUR_MS, s = s, o = o)) { Point(it.long("b") * HOUR_MS, it.double("v")) }
        }
    }

    fun total(key: String, from: Long?, subject: String? = null, obj: String? = null): Long {
        flush()
        val k = ids[key] ?: return 0
        val s = subject?.let { ids[it] ?: return 0 }
        val o = obj?.let { ids[it] ?: return 0 }
        return Db.read {
            one(TOTAL, *args(k, (from ?: 0L) / HOUR_MS, s = s, o = o)) { it.long("v") }
        } ?: 0
    }

    fun distinct(key: String, from: Long?, obj: String? = null): Long {
        flush()
        val k = ids[key] ?: return 0
        val o = obj?.let { ids[it] ?: return 0 }
        val since = from ?: 0L
        return Db.read {
            one(
                "SELECT COUNT(*) v FROM (SELECT s FROM libs_event WHERE k = ? AND at >= ? AND (? IS NULL OR o = ?) UNION SELECT s FROM libs_count WHERE k = ? AND h >= ? AND (? IS NULL OR o = ?))",
                k, since, o, o, k, since / HOUR_MS, o, o
            ) { it.long("v") }
        } ?: 0
    }

    fun top(key: String, from: Long?, by: String = "s", subject: String? = null, limit: Int = 20, obj: String? = null): List<Top> {
        flush()
        val k = ids[key] ?: return emptyList()
        val s = subject?.let { ids[it] ?: return emptyList() }
        val o = obj?.let { ids[it] ?: return emptyList() }
        return Db.read {
            query(if (by == "o") TOP_O else TOP_S, *args(k, (from ?: 0L) / HOUR_MS, s = s, o = o, tail = listOf(limit))) { Top(names[it.int("g")].orEmpty(), it.long("v")) }
        }
    }

    fun events(key: String, from: Long?, subject: String? = null, limit: Int = 100): List<Triple<Long, String, Long>> {
        val k = ids[key] ?: return emptyList()
        val s = subject?.let { ids[it] ?: return emptyList() }
        return Db.read {
            query(
"SELECT at, o, v FROM libs_event WHERE k = ? AND at >= ? AND (? IS NULL OR s = ?) ORDER BY at DESC LIMIT ?", k, from ?: 0L, s, s, limit) { Triple(it.long("at"), names[it.int("o")].orEmpty(), it.long("v")) }
        }
    }

    fun latest(key: String): Map<String, Long> {
        val k = ids[key] ?: return emptyMap()
        return Db.read {
            query("SELECT s, v FROM libs_event WHERE k = ? AND at = (SELECT MAX(at) FROM libs_event WHERE k = ?)", k, k) { names[it.int("s")].orEmpty() to it.long("v") }
        }.toMap()
    }

    fun id(key: String): Int = ids[key] ?: synchronized(ids) {
        ids[key] ?: ((names.keys.maxOrNull() ?: 0) + 1).also { i ->
            names[i] = key
            ids[key] = i
            Db.write { exec("INSERT INTO libs_key (id, key) VALUES (?, ?)", i, key) }
        }
    }

    fun name(id: Int): String = names[id].orEmpty()
}
