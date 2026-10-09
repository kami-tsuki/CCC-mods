package kami.libs.economy

import kami.libs.config.Configs
import kami.libs.db.Db
import kami.libs.db.Migrations
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatementDbTest {
    private val fixture = Path.of("src/test/resources/fixtures/0.1.0/kami_libs_statement.json")

    private fun snapshot(ids: Collection<String>) = ids.associateWith {
        val id = UUID.fromString(it)
        Db.json.encodeToString(StatementBook.serializer(), StatementBook(Statement.movements(id).toMutableList(), Statement.days(id).toMutableList()))
    }

    @Test
    fun fixtureMigratesAndReloads() {
        val world = Files.createTempDirectory("kami-libs")
        try {
            Files.copy(fixture, world.resolve("kami_libs_statement.json"))
            val legacy = Configs.json().decodeFromString(StatementData.serializer(), Files.readString(fixture))
            val expected = legacy.books.mapValues { Db.json.encodeToString(StatementBook.serializer(), it.value) }
            val db = world.resolve("kami").resolve("kami.db")
            Db.open(db)
            assertEquals(expected, snapshot(legacy.books.keys))
            assertTrue(Files.exists(world.resolve("kami_libs_statement.json.migrated")))
            val fresh = UUID.randomUUID()
            Statement.record(fresh, -75, Flow.RENT, "Landlord")
            Statement.record(fresh, 100, Flow.REFUND)
            Db.close()
            Db.open(db)
            assertEquals(expected, snapshot(legacy.books.keys))
            assertEquals(listOf(100L, -75L), Statement.movements(fresh).map { it.amount })
            assertEquals(listOf(100L to 75L), Statement.days(fresh).map { it.income to it.expense })
            assertEquals(listOf(Flow.REFUND, Flow.RENT), Statement.totals(fresh, 7).map { it.flow })
            Db.close()
        } finally {
            Db.close()
            world.toFile().deleteRecursively()
        }
    }

    @Test
    fun bookMatchesSeparateReads() {
        val world = Files.createTempDirectory("kami-libs-book")
        try {
            Db.open(world.resolve("kami").resolve("kami.db"))
            val id = UUID.randomUUID()
            Statement.record(id, -75, Flow.RENT, "Landlord")
            Statement.record(id, 100, Flow.REFUND)
            Statement.record(id, 20, Flow.REFUND)
            Db.flush()
            val book = Statement.book(id)
            fun json(m: List<Movement>) = Db.json.encodeToString(StatementBook.serializer(), StatementBook(m.toMutableList(), ArrayList()))
            fun flat(t: List<FlowTotal>) = t.map { Triple(it.flow, it.income, it.expense) }
            assertEquals(json(Statement.movements(id)), json(book.movements))
            assertEquals(Statement.days(id).map { Triple(it.day, it.income, it.expense) }, book.days.map { Triple(it.day, it.income, it.expense) })
            assertEquals(flat(Statement.totals(id, 7)), flat(book.week))
            assertEquals(flat(Statement.totals(id, 30)), flat(book.month))
            assertEquals(listOf(Triple(Flow.REFUND, 120L, 0L), Triple(Flow.RENT, 0L, 75L)), flat(book.week))
        } finally {
            Db.close()
            world.toFile().deleteRecursively()
        }
    }

    @Test
    fun totalsHonourWindowOrderingSignAndPlayer() {
        val world = Files.createTempDirectory("kami-libs-totals")
        try {
            Db.open(world.resolve("kami").resolve("kami.db"))
            val id = UUID.randomUUID()
            val other = UUID.randomUUID()
            val now = System.currentTimeMillis()
            val day = 24L * 3600_000
            Db.write {
                journalLeg(now - 8 * day, PlayerParty(id), 500, Flow.WAGES, null, "", "")
                journalLeg(now - 31 * day, PlayerParty(id), 900, Flow.REWARD, null, "", "")
                journalLeg(now - 2 * day, PlayerParty(id), 40, Flow.RENT, null, "", "")
                journalLeg(now - 1 * day, PlayerParty(id), -10, Flow.RENT, null, "", "")
                journalLeg(now - 1 * day, PlayerParty(id), 0, Flow.FEE, null, "", "")
                journalLeg(now - 1 * day, PlayerParty(id), 7, Flow.TAX, null, "", "")
                journalLeg(now - 1 * day, PlayerParty(other), 1000, Flow.TAX, null, "", "")
            }
            Db.flush()
            fun flat(t: List<FlowTotal>) = t.map { Triple(it.flow, it.income, it.expense) }
            assertEquals(listOf(Triple(Flow.TAX, 7L, 0L), Triple(Flow.FEE, 0L, 0L), Triple(Flow.RENT, 40L, 10L)), flat(Statement.totals(id, 7)))
            assertEquals(listOf(Flow.TAX, Flow.FEE, Flow.RENT, Flow.WAGES), Statement.totals(id, 30).map { it.flow })
            assertEquals(500L, Statement.totals(id, 30).single { it.flow == Flow.WAGES }.income)
            assertTrue(Statement.totals(id, 30).none { it.flow == Flow.REWARD })
            assertTrue(Statement.totals(UUID.randomUUID(), 30).isEmpty())
        } finally {
            Db.close()
            world.toFile().deleteRecursively()
        }
    }

    @Test
    fun hiddenFlowsAreExcludedFromTotals() {
        val world = Files.createTempDirectory("kami-libs-hidden")
        try {
            Db.open(world.resolve("kami").resolve("kami.db"))
            val id = UUID.randomUUID()
            val now = System.currentTimeMillis()
            Db.write {
                journalLeg(now, PlayerParty(id), 5, Flow.TAX, null, "", "")
                journalLeg(now, PlayerParty(id), 100, Flow.UNEXPLAINED, null, "", "")
                journalLeg(now, PlayerParty(id), -3, Flow.ROUNDING, null, "", "")
                journalLeg(now, PlayerParty(id), 9, Flow.RECOVERY, null, "", "")
            }
            Db.flush()
            assertEquals(listOf(Flow.TAX), Statement.totals(id, 7).map { it.flow })
            assertEquals(listOf(Flow.TAX), Statement.book(id).week.map { it.flow })
        } finally {
            Db.close()
            world.toFile().deleteRecursively()
        }
    }

    @Test
    fun migrationLock() {
        assertEquals(Files.readAllLines(Path.of("src/test/resources/migrations.lock")).filter { it.isNotBlank() }, Migrations.signature("libs"))
    }
}
