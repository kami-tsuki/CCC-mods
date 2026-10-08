package kami.libs.db

import org.sqlite.JDBC
import java.sql.SQLException
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WriterTest {
    private fun writer() = Writer(JDBC.createConnection("jdbc:sqlite::memory:", Properties()))

    private fun Writer.rows(): Int {
        var n = -1
        submit({ n = one("SELECT COUNT(*) n FROM t") { it.int("n") } ?: -1 }, durable = true)
        return n
    }

    @Test
    fun busyBatchStaysQueuedAndIsRetried() {
        val w = writer()
        var runs = 0
        val before = Writer.dropCount
        w.submit({ if (runs++ == 0) throw SQLException("busy", "", 5) }, durable = true)
        assertEquals(2, runs)
        assertEquals(before, Writer.dropCount)
        w.close()
    }

    @Test
    fun droppedWriteIsCountedAndRecorded() {
        val w = writer()
        val before = Writer.dropCount
        assertFailsWith<SQLException> { w.submit({ throw SQLException("bad", "", 1) }, durable = true) }
        assertEquals(before + 1, Writer.dropCount)
        assertNotNull(Writer.failure)
        assertTrue(Writer.lastDropAt > 0)
        w.submit({}, durable = true)
        assertNotNull(Writer.failure)
        w.close()
    }

    @Test
    fun groupLargerThanBatchCommitsAtomically() {
        val w = writer()
        w.submit({ exec("CREATE TABLE t (n INTEGER)") }, durable = true)
        val inserts = List<Session.() -> Unit>(1200) { { exec("INSERT INTO t (n) VALUES (?)", it) } }
        assertFailsWith<SQLException> { w.submitAll(inserts + { throw SQLException("bad", "", 1) }, durable = true, wait = true) }
        assertEquals(0, w.rows())
        w.submitAll(inserts, durable = true, wait = true)
        assertEquals(1200, w.rows())
        w.close()
    }

    @Test
    fun failedGroupDoesNotRollBackNeighbours() {
        val w = writer()
        w.submit({ exec("CREATE TABLE t (n INTEGER)") }, durable = true)
        w.submitAll(listOf({ exec("INSERT INTO t (n) VALUES (1)") }), wait = false)
        assertFailsWith<SQLException> { w.submitAll(listOf({ exec("INSERT INTO t (n) VALUES (2)") }, { throw SQLException("bad", "", 1) }), durable = true, wait = true) }
        w.submit({}, durable = true)
        assertEquals(1, w.rows())
        w.close()
    }
}
