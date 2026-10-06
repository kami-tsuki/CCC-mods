package kami.libs.economy

import kami.libs.LibSettings
import kami.libs.db.Db
import kami.libs.db.SavePoint
import kami.libs.util.Time.DAY_MS
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyGuardTest {
    @Test
    fun alertDecision() {
        val s = LibSettings()
        assertTrue(MoneyGuard.alarming(-10_000, 100, s))
        assertTrue(MoneyGuard.alarming(0, 5_000, s))
        assertFalse(MoneyGuard.alarming(9_999, 4_999, s))
    }

    @Test
    fun explainedAggregationAndPersistence() {
        val root = Files.createTempDirectory("kami-money-guard")
        try {
            val file = root.resolve("kami").resolve("kami.db")
            Db.open(file)
            MoneyGuard.load()
            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            MoneyGuard.explained("test") { MoneyGuard.changed(a, true, 0, 50_000) }
            MoneyGuard.changed(a, true, 100, 3_100)
            MoneyGuard.changed(a, true, 3_100, 9_100)
            val owner = UUID.randomUUID()
            MoneyGuard.changed(b, false, 500, 400, owner)
            assertEquals(listOf(a), MoneyGuard.flush(1_000).map { it.account })
            Db.flush()
            val leg = Journal.query(Journal.Filter(party = PlayerParty(a)), 0).rows.single()
            assertEquals(listOf<Any>(Flow.UNEXPLAINED, 9_000L, "×2"), listOf(leg.flow, leg.delta, leg.note))
            assertEquals(-100L, Journal.query(Journal.Filter(party = PlayerParty(owner)), 0).rows.single().delta)
            assertTrue(MoneyGuard.recent(1_000 + DAY_MS - 1))
            assertFalse(MoneyGuard.recent(1_000 + DAY_MS + 1))
            SavePoint.run()
            Db.flush()
            Db.close()
            Db.open(file)
            MoneyGuard.load()
            val rows = MoneyGuard.accounts(0, 1_000) { "name" }.rows
            assertEquals(listOf(a.toString() to 9_000L, b.toString() to -100L), rows.map { it.uuid to it.total })
            assertEquals(listOf(true to 2L, false to 1L), rows.map { it.flagged to it.count })
        } finally {
            Db.close()
            root.toFile().deleteRecursively()
        }
    }
}
