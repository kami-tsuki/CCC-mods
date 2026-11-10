package kami.libs.db

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DbProbeTest {
    private lateinit var world: Path
    private val file get() = world.resolve("kami").resolve("kami.db")
    private val forever = "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM c) SELECT COUNT(*) n FROM c"

    @BeforeTest
    fun setUp() {
        world = Files.createTempDirectory("kami-probe-db")
        Db.open(file)
    }

    @AfterTest
    fun tearDown() {
        Db.close()
        world.toFile().deleteRecursively()
    }

    @Test
    fun aSlowQueryIsCancelledAndTheConnectionStaysUsable() {
        assertFailsWith<TimeoutException> { Db.probe(200) { one(forever) { it.int("n") } } }
        assertEquals(7, Db.probe { one("SELECT 7 n") { it.int("n") } })
    }

    @Test
    fun theConnectionIsReadOnly() {
        assertFailsWith<java.sql.SQLException> { Db.probe { exec("INSERT INTO libs_key (id, key) VALUES (99, 'x')") } }
    }

    @Test
    fun probingAfterCloseFailsAndAfterReopenWorks() {
        Db.close()
        assertFailsWith<IllegalStateException> { Db.probe { one("SELECT 1 n") { it.int("n") } } }
        Db.open(file)
        assertEquals(1, Db.probe { one("SELECT 1 n") { it.int("n") } })
    }
}
