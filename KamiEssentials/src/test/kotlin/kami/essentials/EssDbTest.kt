package kami.essentials

import kami.libs.db.Db
import kami.libs.db.Migrations
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EssDbTest {
    private fun snapshot() = Flag.entries.associateWith { Store.ids(it).toSet() }

    @Test
    fun fixtureMigratesAndReloads() {
        val world = Files.createTempDirectory("kami-ess")
        try {
            Files.copy(Path.of("src/test/resources/fixtures/0.1.0/kami_essentials.json"), world.resolve("kami_essentials.json"))
            EssMigrations.register()
            val db = world.resolve("kami").resolve("kami.db")
            Db.open(db)
            Db.read { Store.load(this) }
            val expected = Flag.entries.associateWith { emptySet<UUID>() } + mapOf(
                Flag.INVISIBLE to setOf(UUID.fromString("11111111-1111-1111-1111-111111111111"), UUID.fromString("00000000-0000-0000-0000-000000000002")),
                Flag.COUNTRY_CHAT to setOf(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                Flag.QUIET_DM to setOf(UUID.fromString("22222222-2222-2222-2222-222222222222"))
            )
            assertEquals(expected, snapshot())
            assertTrue(Files.exists(world.resolve("kami_essentials.json.migrated")))
            val id = UUID.randomUUID()
            Store[Flag.NO_TRADES, id] = true
            Db.close()
            Db.open(db)
            Db.read { Store.load(this) }
            assertEquals(expected + (Flag.NO_TRADES to setOf(id)), snapshot())
            Db.close()
        } finally {
            Db.close()
            world.toFile().deleteRecursively()
        }
    }

    @Test
    fun migrationLock() {
        EssMigrations.register()
        assertEquals(Files.readAllLines(Path.of("src/test/resources/migrations.lock")).filter { it.isNotBlank() }, Migrations.signature("ess"))
    }
}
