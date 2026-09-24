package kami.economy.economy

import kami.economy.Config
import kami.economy.KamiEconomy
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

@Serializable
class Bucket(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long, val at: Long)

enum class Resolution(val millis: Long) {
    RAW(0L), HOURLY(TimeUnit.HOURS.toMillis(1)), DAILY(TimeUnit.DAYS.toMillis(1));

    companion object {
        fun parse(s: String) = when (s) { "hourly" -> HOURLY; "daily" -> DAILY; else -> RAW }
    }
}

@Serializable
class ItemHistory(
    val raw: MutableList<Bucket> = mutableListOf(),
    val hourly: MutableList<Bucket> = mutableListOf(),
    val daily: MutableList<Bucket> = mutableListOf(),
    val hourAcc: Bucket? = null,
    val hourStart: Long = 0L,
    val dayAcc: Bucket? = null,
    val dayStart: Long = 0L
)

@Serializable
class HistoryData(val items: MutableMap<String, ItemHistory> = mutableMapOf())

private class Track {
    val raw = ArrayDeque<Bucket>()
    val hourly = ArrayDeque<Bucket>()
    val daily = ArrayDeque<Bucket>()
    var hourAcc: Bucket? = null
    var hourStart = 0L
    var dayAcc: Bucket? = null
    var dayStart = 0L
}

object History {
    private val json = Json { encodeDefaults = true }
    private var file: Path? = null
    private val tracks = HashMap<String, Track>()
    private var dirty = false

    fun load(server: MinecraftServer) {
        val path = server.getWorldPath(LevelResource.ROOT).resolve("kami_economy_history.json")
        file = path
        val data = if (Files.exists(path)) runCatching { json.decodeFromString<HistoryData>(Files.readString(path)) }.getOrElse {
            KamiEconomy.LOG.error("Unreadable price history, kept as .bad", it)
            Files.move(path, path.resolveSibling("kami_economy_history.json.bad"), StandardCopyOption.REPLACE_EXISTING)
            HistoryData()
        } else HistoryData()
        tracks.clear()
        data.items.forEach { (item, h) ->
            val t = Track()
            t.raw.addAll(h.raw)
            t.hourly.addAll(h.hourly)
            t.daily.addAll(h.daily)
            t.hourAcc = h.hourAcc
            t.hourStart = h.hourStart
            t.dayAcc = h.dayAcc
            t.dayStart = h.dayStart
            tracks[item] = t
        }
    }

    fun save(force: Boolean = false) {
        val path = file ?: return
        if (!dirty && !force) return
        val data = HistoryData(tracks.mapValuesTo(LinkedHashMap()) { (_, t) ->
            ItemHistory(t.raw.toMutableList(), t.hourly.toMutableList(), t.daily.toMutableList(), t.hourAcc, t.hourStart, t.dayAcc, t.dayStart)
        })
        val tmp = path.resolveSibling("kami_economy_history.json.tmp")
        Files.writeString(tmp, json.encodeToString(data))
        if (Files.exists(path)) Files.copy(path, path.resolveSibling("kami_economy_history.json.bak"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        dirty = false
    }

    fun record(item: String, open: Double, high: Double, low: Double, close: Double, volume: Long) {
        val now = System.currentTimeMillis()
        val bucket = Bucket(open, high, low, close, volume, now)
        val t = tracks.getOrPut(item) { Track() }

        t.raw.addLast(bucket)
        while (t.raw.size > Config.s.historyRawRetention) t.raw.removeFirst()

        foldInto(t.hourly, bucket, now, Resolution.HOURLY, Config.s.historyHourlyRetention, { t.hourAcc }, { t.hourAcc = it }, { t.hourStart }, { t.hourStart = it })
        foldInto(t.daily, bucket, now, Resolution.DAILY, Config.s.historyDailyRetention, { t.dayAcc }, { t.dayAcc = it }, { t.dayStart }, { t.dayStart = it })
        dirty = true
    }

    private fun foldInto(
        deque: ArrayDeque<Bucket>, tick: Bucket, now: Long, res: Resolution, retention: Int,
        getAcc: () -> Bucket?, setAcc: (Bucket?) -> Unit, getStart: () -> Long, setStart: (Long) -> Unit
    ) {
        val periodStart = now - (now % res.millis)
        val acc = getAcc()
        if (acc == null || getStart() != periodStart) {
            if (acc != null) {
                deque.addLast(acc)
                while (deque.size > retention) deque.removeFirst()
            }
            setAcc(tick)
            setStart(periodStart)
        } else {
            setAcc(Bucket(acc.open, max(acc.high, tick.high), min(acc.low, tick.low), tick.close, acc.volume + tick.volume, tick.at))
        }
    }

    fun of(item: String, resolution: Resolution, limit: Int): List<Bucket> {
        val t = tracks[item] ?: return emptyList()
        val list = when (resolution) {
            Resolution.RAW -> t.raw.toList()
            Resolution.HOURLY -> t.hourly.toList() + listOfNotNull(t.hourAcc)
            Resolution.DAILY -> t.daily.toList() + listOfNotNull(t.dayAcc)
        }
        return list.takeLast(limit)
    }
}
