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
    fun migrationLock() {
        assertEquals(Files.readAllLines(Path.of("src/test/resources/migrations.lock")).filter { it.isNotBlank() }, Migrations.signature("libs"))
    }
}
