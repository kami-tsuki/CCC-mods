package kami.libs.db

import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DbTest {
    private lateinit var world: Path
    private val file get() = world.resolve("kami").resolve("kami.db")

    private val items = Migration("t", 1, "items", ddl = listOf("CREATE TABLE t_item (id TEXT PRIMARY KEY, qty INTEGER NOT NULL)"))

    private fun <T> raw(sql: String, row: (Row) -> T) = JDBC.createConnection("jdbc:sqlite:$file", Properties()).use { Session(it).query(sql, row = row) }

    @BeforeTest
    fun setUp() {
        world = Files.createTempDirectory("kami-db")
        Migrations.register("t", listOf(items))
    }

    @AfterTest
    fun tearDown() {
        Db.close()
        world.toFile().deleteRecursively()
    }

    @Test
    fun writeFlushReadSurvivesReopen() {
        Db.open(file)
        Db.write { exec("INSERT INTO t_item (id, qty) VALUES (?, ?)", "a", 3) }
        Db.flush()
        assertEquals(3, Db.read { one("SELECT qty FROM t_item WHERE id = ?", "a") { it.int("qty") } })
        Db.close()
        Db.open(file)
        assertEquals(listOf("a"), Db.read { query("SELECT id FROM t_item") { it.string("id") } })
    }

    @Test
    fun rowsSyncUpsertsAndDeletes() {
        Db.open(file)
        val rows = Rows<String>("t_item", listOf("id"), listOf("qty"))
        rows.sync(mapOf("a" to listOf("a", 1), "b" to listOf("b", 2)))
        rows.sync(mapOf("a" to listOf("a", 5)))
        assertEquals(listOf("a" to 5), Db.read { query("SELECT id, qty FROM t_item") { it.string("id") to it.int("qty") } })
    }

    @Test
    fun durableIsVisibleOnReturn() {
        Db.open(file)
        Db.durable { exec("INSERT INTO t_item (id, qty) VALUES (?, ?)", "d", 1) }
        assertEquals(listOf("d"), raw("SELECT id FROM t_item") { it.string("id") })
    }

    @Test
    fun poisonJobIsDroppedAndRowsRewrite() {
        Db.open(file)
        val rows = Rows<String>("t_item", listOf("id"), listOf("qty"))
        rows.sync(mapOf("a" to listOf("a", 1)))
        assertFailsWith<SQLException> { Db.durable { exec("INSERT INTO t_missing (id) VALUES (1)") } }
        Db.write { exec("INSERT INTO t_item (id, qty) VALUES (?, ?)", "b", 2) }
        Db.flush()
        assertEquals(listOf("a", "b"), raw("SELECT id FROM t_item ORDER BY id") { it.string("id") })
        rows.sync(mapOf("a" to listOf("a", 1)))
        Db.flush()
        assertEquals(listOf("a"), raw("SELECT id FROM t_item") { it.string("id") })
    }

    @Test
    fun failedMigrationRollsBackAndKeepsLegacy() {
        Db.open(file)
        Db.close()
        val legacy = world.resolve("legacy.json").also { Files.writeString(it, "{}") }
        Migrations.register("t", listOf(items, Migration("t", 2, "broken", legacy = listOf("legacy.json")) {
            exec("CREATE TABLE t_other (id TEXT)")
            error("boom")
        }))
        assertFailsWith<MigrationFailure> { Db.open(file) }
        assertFailsWith<IllegalStateException> { Db.write {} }
        assertEquals("{}", Files.readString(legacy))
        assertEquals(listOf(1), raw("SELECT version FROM libs_migration WHERE module = 't'") { it.int("version") })
        assertEquals(emptyList(), raw("SELECT name FROM sqlite_master WHERE name = 't_other'") { it.string("name") })
        val backups = Files.list(file.resolveSibling("backups")).use { s -> s.map { it.name }.toList() }
        assertTrue(backups.any { it.endsWith("-pre.db") } && backups.any { it.startsWith("legacy-") })
        assertTrue(!world.resolve("legacy.json.migrated").exists())
    }

    @Test
    fun changedMigrationIsRefused() {
        Db.open(file)
        Db.close()
        Migrations.register("t", listOf(Migration("t", 1, "renamed")))
        assertContains(assertFailsWith<MigrationFailure> { Db.open(file) }.message.orEmpty(), "changed after release")
    }

    @Test
    fun downgradeIsRefused() {
        Migrations.register("t", listOf(items, Migration("t", 2, "more")))
        Db.open(file)
        Db.close()
        Migrations.register("t", listOf(items))
        assertContains(assertFailsWith<MigrationFailure> { Db.open(file) }.message.orEmpty(), "newer Kami version")
    }

    @Test
    fun sqlIsNeverBuiltFromTemplates() {
        val template = Regex("""\b(?:exec|query|one|batch)\(\s*"{1,3}[^"]*\$[{\w]""")
        val root = Path.of("").toAbsolutePath().parent
        val sources = Files.list(root).use { s -> s.map { it.resolve("src").resolve("main") }.filter { Files.isDirectory(it) }.toList() }
            .flatMap { dir -> Files.walk(dir).use { s -> s.filter { it.name.endsWith(".kt") }.toList() } }
        assertTrue(sources.isNotEmpty())
        assertEquals(emptyList(), sources.filter { template.containsMatchIn(Files.readString(it)) })
    }
}
