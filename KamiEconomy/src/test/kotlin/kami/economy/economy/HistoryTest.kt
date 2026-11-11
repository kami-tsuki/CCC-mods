package kami.economy.economy

import kami.economy.db.HistoryTables
import kami.economy.Config
import kami.economy.Settings
import kami.libs.db.Db
import kami.libs.db.SavePoint
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {
    private val a = "test:a"
    private val b = "test:b"
    private val c = "test:c"

    private fun reseed(vararg items: Pair<String, ItemHistory>) {
        val data = HistoryData(mutableMapOf(*items))
        Db.write {
            exec("DELETE FROM eco_history")
            exec("DELETE FROM eco_history_item")
            HistoryTables.all.forEach { it.insert(this, data) }
        }
        Db.flush()
        History.load()
    }

    private fun rows() = Db.read(fresh = true) {
        query("SELECT * FROM eco_history") {
            listOf(it.string("item"), it.int("tier"), it.long("at"), it.double("open"), it.double("high"), it.double("low"), it.double("close"), it.long("volume")).joinToString("|")
        } to query("SELECT * FROM eco_history_item") {
            listOf(it.string("item"), it.stringOrNull("five_acc"), it.long("five_start"), it.stringOrNull("hour_acc"), it.long("hour_start"), it.stringOrNull("day_acc"), it.long("day_start")).joinToString("|")
        }
    }

    private fun assertSynced() {
        val data = History.full()
        val buckets = data.items.flatMap { (k, h) -> listOf(h.five, h.hourly, h.daily).flatMapIndexed { t, l -> l.map { HistoryTables.bucketRow(k, t, it).joinToString("|") } } }
        val items = data.items.map { (k, h) -> HistoryTables.itemRow(k, h.fiveAcc, h.fiveStart, h.hourAcc, h.hourStart, h.dayAcc, h.dayStart).joinToString("|") }
        val (storedBuckets, storedItems) = rows()
        assertEquals(buckets.sorted(), storedBuckets.sorted())
        assertEquals(items.sorted(), storedItems.sorted())
    }

    private fun bucket(at: Long, close: Double) = Bucket(close, close, close, close, 1, at)

    private fun flushChanges(): List<Boolean> = History.changes().map { part ->
        part?.let { Db.write(it); true } ?: false
    }.also { Db.flush() }

    private fun stored() = Db.read(fresh = true) {
        query("SELECT item, at, close FROM eco_history ORDER BY item, at") { Triple(it.string("item"), it.long("at"), it.double("close")) }
    }

    @BeforeTest
    fun setUp() {
        TestDb.open()
        val data = HistoryData(
            mutableMapOf(
                a to ItemHistory(five = mutableListOf(bucket(10, 5.0))),
                b to ItemHistory(five = mutableListOf(bucket(20, 7.0), bucket(30, 8.0)))
            )
        )
        Db.write { HistoryTables.all.forEach { it.insert(this, data) } }
        Db.flush()
        History.load()
    }

    @AfterTest
    fun tearDown() {
        Config.s = Settings()
        History.unload()
        TestDb.close()
    }

    @Test
    fun unchangedHistoryWritesNothing() {
        assertEquals(listOf(false, false), flushChanges())
    }

    @Test
    fun scaledItemRewritesOnlyItsOwnBuckets() {
        History.scale(a, 2.0)
        assertEquals(listOf(false, true), flushChanges())
        assertEquals(listOf(Triple(a, 10L, 10.0), Triple(b, 20L, 7.0), Triple(b, 30L, 8.0)), stored())
        assertEquals(listOf(false, false), flushChanges())
    }

    @Test
    fun recordedItemIsTracked() {
        History.record(a, 1.0, 1.0, 1.0, 1.0, 1)
        assertEquals(listOf(true, false), flushChanges())
        assertSynced()
        assertEquals(listOf(false, false), flushChanges())
    }

    @Test
    fun rolloverMovesAccumulatorsIntoBuckets() {
        reseed(c to ItemHistory(mutableListOf(bucket(1, 1.0)), mutableListOf(), mutableListOf(), bucket(2, 2.0), 0L, bucket(3, 3.0), 0L, bucket(4, 4.0), 0L))
        History.record(c, 9.0, 9.0, 9.0, 9.0, 5)
        assertEquals(listOf(true, true), flushChanges())
        assertSynced()
    }

    @Test
    fun retentionPruneRemovesStoredKeys() {
        Config.s = Settings(historyFiveMinRetention = 2)
        reseed(c to ItemHistory(mutableListOf(bucket(1, 1.0), bucket(2, 2.0)), fiveAcc = bucket(3, 3.0), fiveStart = 0L))
        History.record(c, 9.0, 9.0, 9.0, 9.0, 5)
        flushChanges()
        assertSynced()
        assertEquals(listOf(2L, 3L), rows().first.filter { it.startsWith("$c|0|") }.map { it.split("|")[2].toLong() }.sorted())
    }

    @Test
    fun bucketAddedAndPrunedInOneIntervalIsNeverStored() {
        Config.s = Settings(historyFiveMinRetention = 0)
        reseed(c to ItemHistory(mutableListOf(bucket(1, 1.0)), fiveAcc = bucket(3, 3.0), fiveStart = 0L))
        History.record(c, 9.0, 9.0, 9.0, 9.0, 5)
        flushChanges()
        assertSynced()
        assertEquals(emptyList(), rows().first.filter { it.startsWith("$c|0|") })
    }

    @Test
    fun scaleCombinedWithPruneKeepsOnlyScaledSurvivors() {
        Config.s = Settings(historyFiveMinRetention = 2)
        reseed(c to ItemHistory(mutableListOf(bucket(1, 1.0), bucket(2, 2.0)), fiveAcc = bucket(3, 3.0), fiveStart = 0L))
        History.scale(c, 2.0)
        History.record(c, 9.0, 9.0, 9.0, 9.0, 5)
        flushChanges()
        assertSynced()
        assertEquals(listOf(4.0, 6.0), rows().first.filter { it.startsWith("$c|0|") }.map { it.split("|")[6].toDouble() }.sorted())
    }

    @Test
    fun droppedWriteFallsBackToFullRewrite() {
        History.record(a, 1.0, 1.0, 1.0, 1.0, 1)
        flushChanges()
        Db.write { exec("INSERT INTO eco_history (item, tier, at, open, high, low, close, volume) VALUES ('junk', 0, 1, 1, 1, 1, 1, 1)") }
        Db.flush()
        fail = true
        SavePoint.run()
        fail = false
        History.record(b, 2.0, 2.0, 2.0, 2.0, 1)
        flushChanges()
        assertSynced()
        assertEquals(emptyList(), rows().first.filter { it.startsWith("junk|") })
    }

    @Test
    fun consecutiveSavesStayInSync() {
        History.record(a, 1.0, 1.0, 1.0, 1.0, 1)
        flushChanges()
        assertSynced()
        History.record(a, 2.0, 3.0, 1.0, 2.0, 4)
        History.record(c, 5.0, 5.0, 5.0, 5.0, 1)
        flushChanges()
        assertSynced()
        assertEquals(listOf(false, false), flushChanges())
    }

    companion object {
        private var fail = false

        init {
            SavePoint.register("history-test-fail") { if (fail) error("collect failed") else null }
        }
    }
}
