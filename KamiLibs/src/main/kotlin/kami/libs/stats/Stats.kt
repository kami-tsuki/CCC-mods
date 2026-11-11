package kami.libs.stats

import kami.libs.LibConfig
import kami.libs.db.Db
import kami.libs.db.Migration
import kami.libs.util.Time.DAY_MS
import kami.libs.util.Time.HOUR_MS
import java.util.concurrent.ConcurrentHashMap

object Stats {
    enum class Agg(select: String) {
        SUM("TOTAL(v)"),
        AVG("AVG(v)"),
        MAX("MAX(v)"),
        COUNT("COUNT(*)"),
        DISTINCT("COUNT(DISTINCT s)");

        val sql = variants("SELECT (at / ?) * ? b, $select v FROM libs_event WHERE k = ? AND at >= ?", " GROUP BY b ORDER BY b")
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
    private val nextId = java.util.concurrent.atomic.AtomicInteger()
    private val pending = HashMap<Cell, Long>()
    private val gauges = LinkedHashMap<String, () -> Map<String, Long>>()
    private var sampled = 0L
    @Volatile
    private var origin: Long? = null
    @Volatile
    private var flushed = 0L
    private val flushing = java.util.concurrent.atomic.AtomicBoolean()

    fun load() {
        origin = null
        ids.clear(); names.clear(); synchronized(pending) { pending.clear() }
        Db.read { query("SELECT id, key FROM libs_key") { it.int("id") to it.string("key") } }.forEach { (i, k) -> ids[k] = i; names[i] = k }
        nextId.set(names.keys.maxOrNull() ?: 0)
        sampled = 0L
    }

    fun on(key: String) = LibConfig.s.stats[key.substringBefore('.')] != false

    fun event(key: String, subject: String = "", obj: String = "", v: Long = 1, at: Long = System.currentTimeMillis()) {
        val k = routed(key, subject) ?: return
        val s = id(subject)
        val o = id(obj)
        Db.write { exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, ?, ?, ?, ?)", at, k, s, o, v) }
    }

    fun add(key: String, subject: String = "", obj: String = "", n: Long = 1) {
        if (n == 0L) return
        val k = routed(key, subject) ?: return
        val cell = Cell(System.currentTimeMillis() / HOUR_MS, k, id(subject), id(obj))
        if (synchronized(pending) { pending.merge(cell, n, ::saturated); pending.size } > PENDING_MAX && flushing.compareAndSet(false, true)) {
            try {
                flush()
            } finally {
                flushing.set(false)
            }
        }
    }

    private fun routed(key: String, subject: String): Int? {
        if (!Db.isOpen || !on(key)) return null
        val k = Staff.route(key, subject)
        return if (k === key || on(k)) id(k) else null
    }

    private fun saturated(a: Long, b: Long): Long {
        val r = a + b
        return if ((a xor r) and (b xor r) < 0) if (a < 0) Long.MIN_VALUE else Long.MAX_VALUE else r
    }

    fun gauge(key: String, sample: () -> Map<String, Long>) {
        synchronized(gauges) { gauges[key] = sample }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        if (now - flushed >= 60_000) {
            flush(now)
            Db.backup()
        }
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

    fun first(): Long = origin ?: Db.read {
        one("SELECT value FROM libs_meta WHERE key = ?", ORIGIN) { it.long("value") }
            ?: one("SELECT MIN(a) m FROM (SELECT MIN(at) a FROM libs_event UNION ALL SELECT MIN(h) * ? FROM libs_count)", HOUR_MS) { it.longOrNull("m") }?.also { found ->
                Db.write { exec("INSERT OR IGNORE INTO libs_meta (key, value) VALUES (?, ?)", ORIGIN, found.toString()) }
            }
    }.also { origin = it } ?: System.currentTimeMillis()

    private const val ORIGIN = "stats.origin"
    private const val PENDING_MAX = 5000
    private const val MAX_KEY = 128
    private const val MAX_KEYS = 200_000
    private const val OVERFLOW = "~overflow"
    private val COUNTED = variants("SELECT (h / ?) * ? b, TOTAL(n) v FROM libs_count WHERE k = ? AND h >= ?", " GROUP BY b ORDER BY b")
    private val TOTAL = variants("SELECT TOTAL(n) v FROM libs_count WHERE k = ? AND h >= ?", "")
    private val TOTALS = variants("SELECT k, TOTAL(n) v FROM libs_count WHERE k IN (SELECT value FROM json_each(?)) AND h >= ?", " GROUP BY k")
    private val TOP_S = variants("SELECT s g, TOTAL(n) v FROM libs_count WHERE k = ? AND h >= ?", " GROUP BY g ORDER BY v DESC LIMIT ?")
    private val TOP_O = variants("SELECT o g, TOTAL(n) v FROM libs_count WHERE k = ? AND h >= ?", " GROUP BY g ORDER BY v DESC LIMIT ?")
    private val UNIQUES = Array(2) { v ->
        val f = if (v == 1) " AND o = ?" else ""
        "SELECT COUNT(*) v FROM (SELECT s FROM libs_event WHERE k = ? AND at >= ?$f UNION SELECT s FROM libs_count WHERE k = ? AND h >= ?$f)"
    }
    private val EVENTS = Array(2) { v -> "SELECT at, o, v FROM libs_event WHERE k = ? AND at >= ?${if (v == 1) " AND s = ?" else ""} ORDER BY at DESC LIMIT ?" }

    private fun args(vararg head: Any, s: Int?, o: Int?, tail: List<Any> = emptyList()): Array<Any?> = arrayOf(*head, *listOfNotNull(s, o).toTypedArray(), *tail.toTypedArray())

    private fun pick(s: Int?, o: Int?) = (if (s != null) 1 else 0) or (if (o != null) 2 else 0)

    fun series(key: String, from: Long?, agg: Agg = Agg.AVG, subject: String? = null, bucket: Long = bucket(from), obj: String? = null): List<Point> {
        val k = ids[key] ?: return emptyList()
        val s = subject?.let { ids[it] ?: return emptyList() }
        val o = obj?.let { ids[it] ?: return emptyList() }
        return Db.scan {
            query(agg.sql[pick(s, o)], *args(bucket, bucket, k, from ?: 0L, s = s, o = o)) { Point(it.long("b"), it.double("v")) }
        }
    }

    fun counted(key: String, from: Long?, subject: String? = null, bucket: Long = bucket(from), obj: String? = null): List<Point> {
        val k = ids[key] ?: return emptyList()
        val s = subject?.let { ids[it] ?: return emptyList() }
        val o = obj?.let { ids[it] ?: return emptyList() }
        val hours = bucket / HOUR_MS
        return Db.scan {
            query(COUNTED[pick(s, o)], *args(hours, hours, k, (from ?: 0L) / HOUR_MS, s = s, o = o)) { Point(it.long("b") * HOUR_MS, it.double("v")) }
        }
    }

    fun total(key: String, from: Long?, subject: String? = null, obj: String? = null): Long {
        val k = ids[key] ?: return 0
        val s = subject?.let { ids[it] ?: return 0 }
        val o = obj?.let { ids[it] ?: return 0 }
        return Db.scan {
            one(TOTAL[pick(s, o)], *args(k, (from ?: 0L) / HOUR_MS, s = s, o = o)) { it.long("v") }
        } ?: 0
    }

    fun totals(keys: List<String>, windows: List<Long?>, subject: String? = null): List<Map<String, Long>> {
        val s = subject?.let { ids[it] ?: return windows.map { emptyMap() } }
        val known = keys.mapNotNull { key -> ids[key]?.let { key to it } }
        val wanted = known.joinToString(",", "[", "]") { it.second.toString() }
        return Db.scan {
            windows.map { from ->
                val sums = query(TOTALS[pick(s, null)], *args(wanted, (from ?: 0L) / HOUR_MS, s = s, o = null)) { it.int("k") to it.long("v") }.toMap()
                known.associate { (key, k) -> key to (sums[k] ?: 0L) }
            }
        }
    }

    fun distinct(key: String, from: Long?, obj: String? = null): Long {
        val k = ids[key] ?: return 0
        val o = obj?.let { ids[it] ?: return 0 }
        val since = from ?: 0L
        return Db.scan {
            one(UNIQUES[if (o != null) 1 else 0], *listOfNotNull<Any>(k, since, o, k, since / HOUR_MS, o).toTypedArray()) { it.long("v") }
        } ?: 0
    }

    fun top(key: String, from: Long?, by: String = "s", subject: String? = null, limit: Int = 20, obj: String? = null): List<Top> {
        val k = ids[key] ?: return emptyList()
        val s = subject?.let { ids[it] ?: return emptyList() }
        val o = obj?.let { ids[it] ?: return emptyList() }
        return Db.scan {
            query((if (by == "o") TOP_O else TOP_S)[pick(s, o)], *args(k, (from ?: 0L) / HOUR_MS, s = s, o = o, tail = listOf(limit))) { Top(names[it.int("g")].orEmpty(), it.long("v")) }
        }
    }

    fun events(key: String, from: Long?, subject: String? = null, limit: Int = 100): List<Triple<Long, String, Long>> {
        val k = ids[key] ?: return emptyList()
        val s = subject?.let { ids[it] ?: return emptyList() }
        return Db.scan {
            query(EVENTS[pick(s, null)], *listOfNotNull<Any>(k, from ?: 0L, s, limit).toTypedArray()) { Triple(it.long("at"), names[it.int("o")].orEmpty(), it.long("v")) }
        }
    }

    fun latest(key: String): Map<String, Long> {
        val k = ids[key] ?: return emptyMap()
        return Db.scan {
            query("SELECT s, v FROM libs_event WHERE k = ? AND at = (SELECT MAX(at) FROM libs_event WHERE k = ?)", k, k) { names[it.int("s")].orEmpty() to it.long("v") }
        }.toMap()
    }

    fun id(key: String): Int {
        val clamped = if (key.length > MAX_KEY) key.take(MAX_KEY) else key
        val k = if (ids.size >= MAX_KEYS && !ids.containsKey(clamped)) OVERFLOW else clamped
        return ids.computeIfAbsent(k) {
            nextId.incrementAndGet().also { i ->
                Db.write { exec("INSERT INTO libs_key (id, key) VALUES (?, ?)", i, k) }
                names[i] = k
            }
        }
    }

    fun name(id: Int): String = names[id].orEmpty()
}

private fun variants(head: String, tail: String) = Array(4) { head + (if (it and 1 != 0) " AND s = ?" else "") + (if (it and 2 != 0) " AND o = ?" else "") + tail }
