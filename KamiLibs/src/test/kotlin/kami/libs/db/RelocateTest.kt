package kami.libs.db

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelocateTest {
    private lateinit var root: Path
    private val old get() = root.resolve("world").resolve("kami").resolve("kami.db")
    private val target get() = root.resolve("kami").resolve("kami.db")

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("kami-relocate")
        Migrations.register("t", listOf(Migration("t", 1, "items", ddl = listOf("CREATE TABLE t_item (id TEXT PRIMARY KEY, qty INTEGER NOT NULL)"))))
        Db.open(old)
        Db.write { exec("INSERT INTO t_item (id, qty) VALUES (?, ?)", "a", 3) }
        Db.close()
        Files.createDirectories(old.resolveSibling("backups"))
        Files.writeString(old.resolveSibling("backups").resolve("b.db"), "x")
    }

    @AfterTest
    fun tearDown() {
        Db.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun relocationCopiesDataAndRenamesOld() {
        Relocate.run(old, target)
        assertFalse(old.exists())
        assertTrue(old.resolveSibling("kami.db.migrated").exists())
        assertTrue(target.resolveSibling("backups").resolve("b.db").exists())
        Db.open(target, root.resolve("world"))
        assertEquals(listOf("a"), Db.read { query("SELECT id FROM t_item") { it.string("id") } })
    }

    @Test
    fun refusesWhenMigratedWithoutTargetOrWhenBothExist() {
        Relocate.run(old, target)
        Files.delete(target)
        assertFailsWith<MigrationFailure> { Relocate.run(old, target) }
        Files.move(old.resolveSibling("kami.db.migrated"), old)
        Files.writeString(target, "x")
        assertFailsWith<MigrationFailure> { Relocate.run(old, target) }
    }
}
