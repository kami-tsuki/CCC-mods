package kami.economy

import kami.economy.db.EcoDb
import kami.libs.db.Db
import kami.libs.db.Migrations
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EcoDbTest {
    private val world = Files.createTempDirectory("kami-eco-fixture")
    private val files = listOf("kami_economy.json", "kami_economy_history.json", "kami_economy.wal")

    @AfterTest
    fun tearDown() {
        Db.close()
        world.toFile().deleteRecursively()
    }

    @Test
    fun freshWorldMigratesAndReloads() {
        EcoDb.register()
        val file = world.resolve("kami").resolve("kami.db")
        Db.open(file)
        Db.close()
        Db.open(file)
        assertEquals(9, Db.read { one("SELECT COUNT(*) AS n FROM libs_migration WHERE module = 'eco'") { it.int("n") } })
        assertEquals(0, Db.read { one("SELECT COUNT(*) AS n FROM eco_server") { it.int("n") } })
    }

    @Test
    fun lockMatchesSignature() {
        EcoDb.register()
        assertEquals(javaClass.getResource("/migrations.lock")!!.readText().lines().filter { it.isNotBlank() }, Migrations.signature("eco"))
    }
}
