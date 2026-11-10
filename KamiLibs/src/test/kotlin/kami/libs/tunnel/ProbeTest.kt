package kami.libs.tunnel

import kami.libs.db.Db
import kami.libs.util.Time.DAY_MS
import kami.libs.util.Time.HOUR_MS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProbeTest {
    private lateinit var world: Path
    private val hour = HOUR_MS
    private val base = 1_000 * hour

    @BeforeTest
    fun setUp() {
        world = Files.createTempDirectory("kami-probe")
        Db.open(world.resolve("kami").resolve("kami.db"))
        Db.write {
            listOf(1 to "server.tps", 2 to "", 3 to "p:a", 4 to "p:b", 5 to "money.paid", 6 to "gold").forEach { (id, key) -> exec("INSERT INTO libs_key (id, key) VALUES (?, ?)", id, key) }
            exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, 1, 2, 2, 2000)", base)
            exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, 1, 2, 2, 1800)", base + 1000)
            exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, 1, 2, 2, 1000)", base + hour)
            exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, 1, 3, 2, 7)", base)
            exec("INSERT INTO libs_count (h, k, s, o, n) VALUES (?, 5, 3, 6, 4)", base / hour)
            exec("INSERT INTO libs_count (h, k, s, o, n) VALUES (?, 5, 3, 6, 5)", base / hour + 1)
            exec("INSERT INTO libs_count (h, k, s, o, n) VALUES (?, 5, 4, 6, 2)", base / hour + 1)
        }
        Db.flush()
    }

    @AfterTest
    fun tearDown() {
        Db.close()
        world.toFile().deleteRecursively()
    }

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is Long -> put(k, v)
                is String -> put(k, v)
                is List<*> -> put(k, JsonArray(v.map { JsonPrimitive(it as String) }))
                else -> error("unsupported argument $k")
            }
        }
    }

    private fun series(result: JsonElement) = result.jsonObject["series"]!!.jsonArray.map { it.jsonObject }

    private fun values(series: JsonObject) = series["points"]!!.jsonArray.map { it.jsonArray[1].jsonPrimitive.double }

    private fun times(series: JsonObject) = series["points"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content.toLong() }

    private fun subjects(found: List<JsonObject>) = found.map { it["subject"]!!.jsonPrimitive.content }

    private fun range(key: String, kind: String, from: Long, to: Long, step: Long, vararg extra: Pair<String, Any>) =
        series(Probe.run("range", args("key" to key, "kind" to kind, "from" to from, "to" to to, "step" to step, *extra)))

    private fun now(key: String, kind: String, from: Long, to: Long, vararg extra: Pair<String, Any>) =
        series(Probe.run("now", args("key" to key, "kind" to kind, "from" to from, "to" to to, *extra)))

    @Test
    fun keysListsOnlyStatKeys() {
        val keys = Probe.run("keys", JsonObject(emptyMap())).jsonObject["keys"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("money.paid", "server.tps"), keys)
    }

    @Test
    fun labelsListSubjectsAndObjectsOfTheLastMonth() {
        Db.write { exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, 1, 4, 6, 1)", System.currentTimeMillis()) }
        Db.flush()
        val result = Probe.run("labels", args("key" to "server.tps")).jsonObject
        assertEquals(listOf("p:b"), result["subjects"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("gold"), result["objects"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptyList(), Probe.run("labels", args("key" to "none")).jsonObject["subjects"]!!.jsonArray)
    }

    @Test
    fun rangeAveragesValuesPerBucketAndSeries() {
        val found = range("server.tps", "value", base, base + 2 * hour, hour)
        assertEquals(listOf("", "p:a"), subjects(found))
        assertEquals(listOf(1900.0, 1000.0), values(found[0]))
        assertEquals(listOf(base, base + hour), times(found[0]))
        assertEquals(listOf(7.0), values(found[1]))
    }

    @Test
    fun rangeFiltersBySubject() {
        val found = range("server.tps", "value", base, base + hour, hour, "subjects" to listOf("p:a"))
        assertEquals(listOf("p:a"), subjects(found))
    }

    @Test
    fun rangeEndsExclusively() {
        assertEquals(listOf(1900.0), values(range("server.tps", "value", base, base + hour, hour)[0]))
    }

    @Test
    fun countRangeSumsPerHour() {
        val found = range("money.paid", "count", base, base + 2 * hour, hour)
        assertEquals(listOf("p:a", "p:b"), subjects(found))
        assertEquals(listOf(4.0, 5.0), values(found[0]))
        assertEquals(listOf(2.0), values(found[1]))
    }

    @Test
    fun countRangeIncludesThePartialHoursAtBothEnds() {
        val found = range("money.paid", "count", base + hour / 2, base + hour + hour / 2, hour)
        assertEquals(listOf(4.0, 5.0), values(found[0]))
        assertEquals(listOf(base, base + hour), times(found[0]))
    }

    @Test
    fun countStepIsRoundedUpToWholeHours() {
        val found = range("money.paid", "count", base, base + 2 * hour, hour * 3 / 2)
        assertEquals(listOf(9.0), values(found[0]))
        assertEquals(listOf(base), times(found[0]))
    }

    @Test
    fun unknownKeyGivesNoSeries() {
        assertEquals(emptyList(), range("none", "value", 0, hour, hour))
        assertEquals(emptyList(), now("none", "count", 0, hour))
    }

    @Test
    fun nowReturnsOnlyTheLatestTickAtOrBeforeTheEvaluationTime() {
        assertEquals(listOf(1000.0), now("server.tps", "value", base, base + hour).map { it["v"]!!.jsonPrimitive.double })
        val past = now("server.tps", "value", base, base + 2000)
        assertEquals(listOf(7.0, 1800.0), past.map { it["v"]!!.jsonPrimitive.double }.sorted())
    }

    @Test
    fun nowIgnoresSamplesBeforeTheWindowAndLaterThanTheEvaluationTime() {
        assertEquals(emptyList(), now("server.tps", "value", base + hour + 1, base + 2 * hour))
        assertEquals(emptyList(), now("server.tps", "value", 0, base - 1))
    }

    @Test
    fun nowLooksBackAtMostADayForValues() {
        assertEquals(emptyList(), now("server.tps", "value", 0, base + 2 * DAY_MS))
        assertEquals(1, now("server.tps", "value", 0, base + DAY_MS - 1).size)
    }

    @Test
    fun eventsCountRowsAndSumAddsTheirValues() {
        assertEquals(listOf(2.0), values(range("server.tps", "events", base, base + hour, hour)[0]))
        assertEquals(listOf(3800.0), values(range("server.tps", "sum", base, base + hour, hour)[0]))
        val total = now("server.tps", "sum", base, base + hour).associate { it["subject"]!!.jsonPrimitive.content to it["v"]!!.jsonPrimitive.double }
        assertEquals(mapOf("" to 4800.0, "p:a" to 7.0), total)
        assertEquals(listOf(1.0), now("server.tps", "events", base + hour, base + hour).map { it["v"]!!.jsonPrimitive.double })
    }

    @Test
    fun aRangeWithTooManySeriesTimesBucketsGetsCoarserSteps() {
        Db.write {
            exec("INSERT INTO libs_key (id, key) VALUES (10, 'dense'), (11, 'a'), (12, 'b'), (13, 'c')")
            listOf(11, 12, 13).forEach { subject -> (0 until 10_000).forEach { i -> exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, 10, ?, 2, 1)", base + i * 4000L, subject) } }
        }
        Db.flush()
        val found = range("dense", "value", base, base + 40_000_000, 4000)
        assertEquals(3, found.size)
        assertTrue(found.all { it["points"]!!.jsonArray.size in 5_000..9_000 })
    }

    @Test
    fun aDenseKeyIsRefusedForALongRangeBeforeAnyScan() {
        val end = base + 31 * DAY_MS
        Db.write {
            exec("INSERT INTO libs_key (id, key) VALUES (30, 'hot')")
            (0 until 25_000).forEach { i -> exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, 30, 3, 2, 1)", end - 6 * hour + i * 500L) }
        }
        Db.flush()
        val refused = assertFailsWith<Probe.Refused> { range("hot", "value", base, end, 5 * 60_000) }
        assertEquals("too many samples", refused.message)
        assertEquals(1, range("hot", "value", end - 6 * hour, end, 5 * 60_000).size)
    }

    @Test
    fun labelsLeaveOutEventsOlderThanADay() {
        val now = System.currentTimeMillis()
        Db.write {
            exec("INSERT INTO libs_key (id, key) VALUES (20, 'old.key')")
            exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, 20, 3, 2, 1)", now - 2 * DAY_MS)
            exec("INSERT INTO libs_event (at, k, s, o, v) VALUES (?, 20, 4, 2, 1)", now - 1000)
        }
        Db.flush()
        assertEquals(listOf("p:b"), Probe.run("labels", args("key" to "old.key")).jsonObject["subjects"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun theBucketCountIsExactForUnalignedRanges() {
        assertEquals(emptyList(), range("none", "value", 500, 500 + 10_999 * 1000L, 1000))
        assertFailsWith<Probe.Refused> { range("none", "value", 500, 500 + 11_000 * 1000L, 1000) }
    }

    @Test
    fun nowSumsCountsFromTheStartHourThroughTheEvaluationHour() {
        val total = now("money.paid", "count", base, base + hour + hour / 2).associate { it["subject"]!!.jsonPrimitive.content to it["v"]!!.jsonPrimitive.double }
        assertEquals(mapOf("p:a" to 9.0, "p:b" to 2.0), total)
        val before = now("money.paid", "count", base, base + hour / 2).associate { it["subject"]!!.jsonPrimitive.content to it["v"]!!.jsonPrimitive.double }
        assertEquals(mapOf("p:a" to 4.0), before)
    }

    @Test
    fun nowFiltersBySubject() {
        assertEquals(listOf("p:b"), subjects(now("money.paid", "count", 0, base + 2 * hour, "subjects" to listOf("p:b"))))
    }

    @Test
    fun refusesBadInput() {
        fun bad(op: String, vararg pairs: Pair<String, Any>) = assertFailsWith<Probe.Refused> { Probe.run(op, args(*pairs)) }
        val ok = arrayOf("key" to "server.tps", "kind" to "value", "from" to 0L, "to" to hour, "step" to hour)
        bad("drop")
        bad("range", *ok, "kind" to "sql")
        bad("range", *ok, "key" to "x".repeat(200))
        bad("range", *ok, "from" to hour)
        bad("range", *ok, "to" to 32 * DAY_MS)
        bad("range", *ok, "to" to 31 * DAY_MS, "step" to 1000L)
        bad("range", *ok, "from" to "0")
        bad("range", *ok, "to" to 4_102_444_800_001L)
        bad("range", *ok, "subjects" to List(51) { "s$it" })
        bad("now", "key" to "server.tps", "kind" to "value", "from" to 0L)
        bad("labels", "key" to "")
    }

    @Test
    fun theLongestAllowedValueRangeIsAccepted() {
        assertEquals(emptyList(), range("none", "value", 0, 31 * DAY_MS, 5 * 60_000))
    }
}
