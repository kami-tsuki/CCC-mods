package kami.economy.db

import kami.economy.economy.Bucket
import kami.economy.economy.HistoryData
import kami.economy.economy.ItemHistory
import kami.libs.db.Db
import kami.libs.db.Row
import kami.libs.db.Session
import kami.libs.db.Table

internal object HistoryTables {
    private fun acc(b: Bucket?) = b?.let { Db.json.encodeToString(Bucket.serializer(), it) }

    private fun acc(r: Row, col: String) = r.stringOrNull(col)?.let { Db.json.decodeFromString(Bucket.serializer(), it) }

    fun itemRow(item: String, fiveAcc: Bucket?, fiveStart: Long, hourAcc: Bucket?, hourStart: Long, dayAcc: Bucket?, dayStart: Long): List<Any?> =
        listOf(item, acc(fiveAcc), fiveStart, acc(hourAcc), hourStart, acc(dayAcc), dayStart)

    fun bucketKey(item: String, tier: Int, at: Long): List<Any?> = listOf(item, tier, at)

    fun bucketRow(item: String, tier: Int, b: Bucket): List<Any?> = listOf(item, tier, b.at, b.open, b.high, b.low, b.close, b.volume)

    val itemTable = Table<HistoryData>("eco_history_item", listOf("item"), listOf("five_acc", "five_start", "hour_acc", "hour_start", "day_acc", "day_start")) { d ->
        d.items.map { (k, h) -> itemRow(k, h.fiveAcc, h.fiveStart, h.hourAcc, h.hourStart, h.dayAcc, h.dayStart) }
    }

    val bucketTable = Table<HistoryData>("eco_history", listOf("item", "tier", "at"), listOf("open", "high", "low", "close", "volume")) { d ->
        d.items.flatMap { (k, h) ->
            listOf(h.five, h.hourly, h.daily).flatMapIndexed { tier, list -> list.map { bucketRow(k, tier, it) } }
        }
    }

    val all = listOf(itemTable, bucketTable)

    fun read(s: Session): HistoryData {
        val buckets = s.query("SELECT * FROM eco_history ORDER BY rowid") { r ->
            (r.string("item") to r.int("tier")) to Bucket(r.double("open"), r.double("high"), r.double("low"), r.double("close"), r.long("volume"), r.long("at"))
        }.groupBy({ it.first }, { it.second })
        fun tier(item: String, t: Int) = buckets[item to t].orEmpty().toMutableList()
        return HistoryData(s.query("SELECT * FROM eco_history_item ORDER BY rowid") { r ->
            val item = r.string("item")
            item to ItemHistory(
                tier(item, 0), tier(item, 1), tier(item, 2),
                acc(r, "five_acc"), r.long("five_start"), acc(r, "hour_acc"), r.long("hour_start"), acc(r, "day_acc"), r.long("day_start")
            )
        }.toMap(LinkedHashMap()))
    }

    fun totals(d: HistoryData): List<Long> {
        val buckets = d.items.values.flatMap { it.five + it.hourly + it.daily }
        return listOf(d.items.size.toLong(), buckets.size.toLong(), buckets.sumOf { it.volume })
    }

    fun totals(s: Session): List<Long> = listOf(
        s.count("SELECT COUNT(*) AS v FROM eco_history_item"), s.count("SELECT COUNT(*) AS v FROM eco_history"), s.count("SELECT SUM(volume) AS v FROM eco_history")
    )
}
