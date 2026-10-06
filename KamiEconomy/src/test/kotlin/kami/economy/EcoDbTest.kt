package kami.economy

import kami.economy.db.EcoDb
import kami.economy.db.HistoryTables
import kami.economy.db.MarketTables
import kami.economy.economy.HistoryData
import kami.economy.economy.Intent
import kami.economy.economy.IntentType
import kami.economy.economy.LedgerState
import kami.libs.config.Configs
import kami.libs.db.Db
import kami.libs.db.Migrations
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EcoDbTest {
    private val world = Files.createTempDirectory("kami-eco-fixture")
    private val files = listOf("kami_economy.json", "kami_economy_history.json", "kami_economy.wal")

    @AfterTest
    fun tearDown() {
        Db.close()
        world.toFile().deleteRecursively()
    }

    @Test
    fun fixtureMigratesAndReloads() {
        EcoDb.register()
        files.forEach { f -> javaClass.getResourceAsStream("/fixtures/0.1.0/$f")!!.use { Files.copy(it, world.resolve(f)) } }
        val intent = Intent(7, IntentType.BUY, LedgerState.DEBITED, actor = "a", item = "test:item", qty = 2)
        Files.writeString(world.resolve(files[2]), Db.json.encodeToString(Intent.serializer(), intent.copy(state = LedgerState.PENDING)) + "\nbroken\n" + Db.json.encodeToString(Intent.serializer(), intent) + "\n", StandardOpenOption.APPEND)
        val market = Configs.json().decodeFromString(Data.serializer(), Files.readString(world.resolve(files[0])))
        val history = Configs.json().decodeFromString(HistoryData.serializer(), Files.readString(world.resolve(files[1])))
        val file = world.resolve("kami").resolve("kami.db")
        fun check() {
            assertEquals(Db.json.encodeToString(Data.serializer(), market), Db.json.encodeToString(Data.serializer(), Db.read { MarketTables.read(this) }))
            assertEquals(Db.json.encodeToString(HistoryData.serializer(), history), Db.json.encodeToString(HistoryData.serializer(), Db.read { HistoryTables.read(this) }))
            assertEquals(listOf("DEBITED"), Db.read { query("SELECT state FROM eco_intent") { it.string("state") } })
        }
        Db.open(file)
        check()
        files.forEach { assertTrue(Files.exists(world.resolve("$it.migrated"))); assertFalse(Files.exists(world.resolve(it))) }
        Db.close()
        Db.open(file)
        assertEquals(3, Db.read { one("SELECT COUNT(*) AS n FROM libs_migration WHERE module = 'eco'") { it.int("n") } })
        check()
    }

    @Test
    fun lockMatchesSignature() {
        EcoDb.register()
        assertEquals(javaClass.getResource("/migrations.lock")!!.readText().lines().filter { it.isNotBlank() }, Migrations.signature("eco"))
    }
}
