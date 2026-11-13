package kami.economy.economy

import kami.economy.Config
import kami.economy.db.HistoryTables
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.util.Time.DAY_MS
import kotlinx.serialization.Serializable
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val FIVE_MIN = TimeUnit.MINUTES.toMillis(5)
private val HOUR = TimeUnit.HOURS.toMillis(1)
private const val ALL_POINTS = 180
private const val MONTH_DAYS = 30

@Serializable
class Bucket(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long, val at: Long)

enum class Range(val arg: String, val span: Long, val step: Long) {
    DAY("day", DAY_MS, FIVE_MIN),
    WEEK("week", 7 * DAY_MS, HOUR),
    MONTH("month", MONTH_DAYS * DAY_MS, 4 * HOUR),
    ALL("all", 0L, 0L);

    companion object {
        fun parse(s: String) = entries.firstOrNull { it.arg == s } ?: WEEK
    }
}

class Series(val start: Long, val open: Double, val buckets: List<Bucket>)

@Serializable
class ItemHistory(
    val five: MutableList<Bucket> = mutableListOf(),
    val hourly: MutableList<Bucket> = mutableListOf(),
    val daily: MutableList<Bucket> = mutableListOf(),
    val fiveAcc: Bucket? = null,
    val fiveStart: Long = 0L,
    val hourAcc: Bucket? = null,
    val hourStart: Long = 0L,
    val dayAcc: Bucket? = null,
    val dayStart: Long = 0L
)

@Serializable
class HistoryData(val items: MutableMap<String, ItemHistory> = mutableMapOf())

private fun merge(a: Bucket, b: Bucket) = Bucket(a.open, max(a.high, b.high), min(a.low, b.low), b.close, a.volume + b.volume, b.at)

private fun Bucket.scaled(f: Double) = Bucket(open * f, high * f, low * f, close * f, volume, at)

private class Tier(val period: Long, val retention: () -> Int) {
    val done = ArrayDeque<Bucket>()
    var acc: Bucket? = null
    var start = 0L
    private val added = ArrayList<Bucket>()
    private val removed = ArrayList<Long>()
    private var rescaled = false

    fun restore(list: List<Bucket>, acc: Bucket?, start: Long) {
        done.addAll(list)
        this.acc = acc
        this.start = start
    }

    fun fold(tick: Bucket, now: Long) {
        val periodStart = now - (now % period)
        val current = acc
        if (current == null || start != periodStart) {
            if (current != null) {
                done.addLast(current)
                added.add(current)
                while (done.size > retention()) removed.add(done.removeFirst().at)
            }
            acc = tick
            start = periodStart
        } else {
            acc = merge(current, tick)
        }
    }

    fun scale(f: Double) {
        val scaled = done.map { it.scaled(f) }
        done.clear()
        done.addAll(scaled)
        acc = acc?.scaled(f)
        rescaled = true
    }

    fun drain(item: String, index: Int, rows: MutableList<List<Any?>>, gone: MutableList<List<Any?>>) {
        val dropped = removed.toHashSet()
        (if (rescaled) done.toList() else added.filter { it.at !in dropped }).mapTo(rows) { HistoryTables.bucketRow(item, index, it) }
        removed.mapTo(gone) { HistoryTables.bucketKey(item, index, it) }
        added.clear()
        removed.clear()
        rescaled = false
    }

    fun list() = done.toList() + listOfNotNull(acc)

    fun before(t: Long): Bucket? = acc?.takeIf { it.at < t } ?: done.descendingIterator().asSequence().firstOrNull { it.at < t }

    fun oldest(): Bucket? = done.firstOrNull() ?: acc

    fun volumeSince(t: Long): Long =
        done.descendingIterator().asSequence().takeWhile { it.at >= t }.sumOf { it.volume } + (acc?.takeIf { it.at >= t }?.volume ?: 0L)
}

private class Track {
    val five = Tier(FIVE_MIN) { Config.s.historyFiveMinRetention }
    val hourly = Tier(HOUR) { Config.s.historyHourlyRetention }
    val daily = Tier(DAY_MS) { Config.s.historyDailyRetention }
    val tiers = listOf(five, hourly, daily)

    fun before(t: Long) = tiers.mapNotNull { it.before(t) }.maxByOrNull { it.at }
    fun oldest() = tiers.mapNotNull { it.oldest() }.minByOrNull { it.at }
    fun latest() = tiers.mapNotNull { it.acc }.maxByOrNull { it.at }
}

object History {
    private val tracks = HashMap<String, Track>()
    private val dirty = HashSet<String>()
    private var attached = false

    fun load() {
        val data = Db.read { HistoryTables.read(this) }
        HistoryTables.all.forEach { it.prime(data) }
        attached = true
        tracks.clear()
        dirty.clear()
        data.items.forEach { (item, h) ->
            val t = Track()
            t.five.restore(h.five, h.fiveAcc, h.fiveStart)
            t.hourly.restore(h.hourly, h.hourAcc, h.hourStart)
            t.daily.restore(h.daily, h.dayAcc, h.dayStart)
            tracks[item] = t
        }
    }

    fun unload() {
        attached = false
    }

    internal fun full() = HistoryData(tracks.mapValuesTo(LinkedHashMap()) { (_, t) ->
        ItemHistory(
            t.five.done.toMutableList(), t.hourly.done.toMutableList(), t.daily.done.toMutableList(),
            t.five.acc, t.five.start, t.hourly.acc, t.hourly.start, t.daily.acc, t.daily.start
        )
    })

    internal fun changes(): List<(Session.() -> Unit)?> {
        if (!attached) return emptyList()
        val items = ArrayList<List<Any?>>()
        val rows = ArrayList<List<Any?>>()
        val gone = ArrayList<List<Any?>>()
        dirty.forEach { item ->
            val t = tracks[item] ?: return@forEach
            items.add(HistoryTables.itemRow(item, t.five.acc, t.five.start, t.hourly.acc, t.hourly.start, t.daily.acc, t.daily.start))
            t.tiers.forEachIndexed { i, tier -> tier.drain(item, i, rows, gone) }
        }
        dirty.clear()
        return listOf(HistoryTables.itemTable.delta(items, emptyList(), ::full), HistoryTables.bucketTable.delta(rows, gone, ::full))
    }

    fun record(item: String, open: Double, high: Double, low: Double, close: Double, volume: Long) {
        val now = System.currentTimeMillis()
        val bucket = Bucket(open, high, low, close, volume, now)
        tracks.getOrPut(item) { Track() }.tiers.forEach { it.fold(bucket, now) }
        dirty.add(item)
    }

    fun scale(item: String, f: Double) {
        tracks[item]?.tiers?.forEach { it.scale(f) }
        if (tracks.containsKey(item)) dirty.add(item)
    }

    fun change(item: String, now: Long): Int {
        val t = tracks[item] ?: return 0
        val open = t.before(now - DAY_MS)?.close ?: t.oldest()?.open ?: return 0
        val last = t.latest()?.close ?: return 0
        return if (open > 0) ((last - open) * 1000 / open).roundToInt() else 0
    }

    fun courseAt(item: String, t: Long): Double? = tracks[item]?.before(t)?.close?.takeIf { it > 0.0 }

    fun items(): Set<String> = tracks.keys

    fun dayVolume(item: String, now: Long) = tracks[item]?.five?.volumeSince(now - DAY_MS) ?: 0L

    fun dayVolume(now: Long = System.currentTimeMillis()) = tracks.values.sumOf { it.five.volumeSince(now - DAY_MS) }

    fun series(item: String, range: Range, now: Long, mid: Double): Series {
        val t = tracks[item] ?: Track()
        val span = now - (t.oldest()?.at ?: now)
        val daily = range == Range.ALL && span >= MONTH_DAYS * DAY_MS
        val unit = if (daily) DAY_MS else HOUR
        val step = if (range == Range.ALL) unit * ceil(span.toDouble() / unit / ALL_POINTS).toLong().coerceAtLeast(1) else range.step
        val count = if (range == Range.ALL) ceil(span.toDouble() / step).toInt().coerceAtLeast(1) else (range.span / step).toInt()
        val from = now - count * step
        val source = when {
            range == Range.DAY -> t.five
            daily -> t.daily
            else -> t.hourly
        }.list()
        val groups = arrayOfNulls<Bucket>(count)
        source.forEach { b ->
            if (b.at >= from) {
                val i = ((b.at - from) / step).toInt().coerceAtMost(count - 1)
                groups[i] = groups[i]?.let { merge(it, b) } ?: b
            }
        }
        val firstReal = groups.indexOfFirst { it != null }
        val ref = t.before(from)?.close ?: mid.takeIf { it > 0 }
        val skip = if (ref != null) 0 else firstReal
        if (skip < 0) return Series(0L, 0.0, emptyList())
        var prev = ref ?: groups[skip]!!.open
        val open = prev
        val buckets = (skip until count).map { i ->
            val at = from + i * step
            val g = groups[i]
            (if (g != null) Bucket(g.open, g.high, g.low, g.close, g.volume, at) else Bucket(prev, prev, prev, prev, 0L, at)).also { prev = it.close }
        }
        return Series(if (firstReal < 0) 0L else from + firstReal * step, open, buckets)
    }
}
