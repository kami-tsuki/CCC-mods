package kami.economy.economy

import kami.economy.LOG
import kami.economy.Config
import kami.libs.config.WorldStore
import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val FIVE_MIN = TimeUnit.MINUTES.toMillis(5)
private val HOUR = TimeUnit.HOURS.toMillis(1)
private val DAY_MS = TimeUnit.DAYS.toMillis(1)
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

class Series(val from: Long, val to: Long, val step: Long, val start: Long, val open: Double, val buckets: List<Bucket>)

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

private class Tier(val period: Long, val retention: () -> Int) {
    val done = ArrayDeque<Bucket>()
    var acc: Bucket? = null
    var start = 0L

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
                while (done.size > retention()) done.removeFirst()
            }
            acc = tick
            start = periodStart
        } else {
            acc = merge(current, tick)
        }
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
    private val store = WorldStore(HistoryData.serializer(), ::HistoryData)
    private val tracks = HashMap<String, Track>()
    private var dirty = false

    fun load(server: MinecraftServer) {
        val path = server.getWorldPath(LevelResource.ROOT).resolve("kami_economy_history.json")
        val data = store.load(path) { LOG.error("Unreadable price history, kept as .bad", it) }
        tracks.clear()
        data.items.forEach { (item, h) ->
            val t = Track()
            t.five.restore(h.five, h.fiveAcc, h.fiveStart)
            t.hourly.restore(h.hourly, h.hourAcc, h.hourStart)
            t.daily.restore(h.daily, h.dayAcc, h.dayStart)
            tracks[item] = t
        }
    }

    fun save(force: Boolean = false) {
        if (!dirty && !force) return
        store.data = HistoryData(tracks.mapValuesTo(LinkedHashMap()) { (_, t) ->
            ItemHistory(
                t.five.done.toMutableList(), t.hourly.done.toMutableList(), t.daily.done.toMutableList(),
                t.five.acc, t.five.start, t.hourly.acc, t.hourly.start, t.daily.acc, t.daily.start
            )
        })
        store.changed()
        store.save(force)
        dirty = false
    }

    fun record(item: String, open: Double, high: Double, low: Double, close: Double, volume: Long) {
        val now = System.currentTimeMillis()
        val bucket = Bucket(open, high, low, close, volume, now)
        tracks.getOrPut(item) { Track() }.tiers.forEach { it.fold(bucket, now) }
        dirty = true
    }

    fun change(item: String, now: Long): Int {
        val t = tracks[item] ?: return 0
        val open = t.before(now - DAY_MS)?.close ?: t.oldest()?.open ?: return 0
        val last = t.latest()?.close ?: return 0
        return if (open > 0) ((last - open) * 1000 / open).roundToInt() else 0
    }

    fun dayVolume(item: String, now: Long) = tracks[item]?.five?.volumeSince(now - DAY_MS) ?: 0L

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
        if (skip < 0) return Series(now, now, step, 0L, 0.0, emptyList())
        var prev = ref ?: groups[skip]!!.open
        val open = prev
        val buckets = (skip until count).map { i ->
            val at = from + i * step
            val g = groups[i]
            (if (g != null) Bucket(g.open, g.high, g.low, g.close, g.volume, at) else Bucket(prev, prev, prev, prev, 0L, at)).also { prev = it.close }
        }
        return Series(from + skip * step, now, step, if (firstReal < 0) 0L else from + firstReal * step, open, buckets)
    }
}
