package kami.claims

import kami.libs.config.Configs
import kami.libs.db.Db
import kami.libs.db.Migrations
import kami.libs.db.SavePoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RealmDbTest {
    private lateinit var world: Path
    private val file get() = world.resolve("kami").resolve("kami.db")
    private val fixture = javaClass.getResource("/fixtures/0.1.0/kami_claims.json")!!.readText()

    private fun encode(d: Data) = Db.json.encodeToString(Data.serializer(), d)

    @BeforeTest
    fun setUp() {
        world = Files.createTempDirectory("kami-claims")
        RealmDb.register()
    }

    @AfterTest
    fun tearDown() {
        RealmDb.unload()
        Db.close()
        Realm.reset(Data())
        world.toFile().deleteRecursively()
    }

    @Test
    fun legacyFileImportsAndSyncs() {
        val expected = encode(Configs.json().decodeFromString(Data.serializer(), fixture).also { Realm.reset(it) })
        Files.writeString(world.resolve(RealmDb.FILE), fixture)
        Db.open(file)
        val loaded = RealmDb.load()
        assertEquals(expected, encode(loaded))
        assertTrue(Files.exists(world.resolve("${RealmDb.FILE}.migrated")))
        Realm.reset(loaded)
        val c = loaded.countries.values.first()
        c.ledger += LedgerEntry(now(), LedgerKind.DEPOSIT, 5, c.treasury + 5)
        c.members.remove(c.members.keys.first())
        loaded.claims.removeAt(0)
        SavePoint.run()
        Db.flush()
        val synced = encode(loaded)
        Db.close()
        Db.open(file)
        assertEquals(listOf(1), Db.read { query("SELECT version FROM libs_migration WHERE module = ?", "claims") { it.int("version") } })
        assertEquals(synced, encode(RealmDb.load()))
    }

    @Test
    fun migrationsMatchLock() {
        val lock = javaClass.getResource("/migrations.lock")!!.readText().lines().filter { it.isNotBlank() }
        assertEquals(lock, Migrations.signature("claims"))
    }
}
