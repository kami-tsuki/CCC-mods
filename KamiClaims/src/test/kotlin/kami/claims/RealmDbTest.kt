package kami.claims

import kami.claims.world.PlacedLog
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
import kotlin.test.assertFalse
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
        PlacedLog.reset()
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
        assertEquals(listOf(1, 2, 3), Db.read { query("SELECT version FROM libs_migration WHERE module = ?", "claims") { it.int("version") } })
        assertEquals(synced, encode(RealmDb.load()))
    }

    private fun reload(d: Data): Data {
        Realm.reset(d)
        SavePoint.run()
        Db.flush()
        Db.close()
        Db.open(file)
        return RealmDb.load()
    }

    @Test
    fun antiAbuseStateSurvivesAReload() {
        Db.open(file)
        val fresh = RealmDb.load()
        val c = Country("Persist").also { fresh.countries[it.id] = it }
        c.members["p"] = Member("CITIZEN", leftJobs = mutableMapOf("miner" to 7L), rankPrev = "OFFICER", rankAt = 9L)
        c.payBudget.day = 3
        c.payBudget.spent = 40
        c.creditLedger.day = 3
        c.creditLedger.used["p|miner|0"] = 5
        c.creditLedger.inputs["p"] = 2.5
        c.withdrawals += WithdrawalRequest("abc", "p", 500, 11)
        c.withdrawn.add(4, 700)
        val back = reload(fresh).countries.getValue(c.id)
        val member = back.members.getValue("p")
        assertEquals(mapOf("miner" to 7L), member.leftJobs)
        assertEquals("OFFICER", member.rankPrev)
        assertEquals(9L, member.rankAt)
        assertEquals(3L to 40L, back.payBudget.day to back.payBudget.spent)
        assertEquals(mapOf("p|miner|0" to 5), back.creditLedger.used)
        assertEquals(mapOf("p" to 2.5), back.creditLedger.inputs)
        assertEquals(listOf("abc"), back.withdrawals.map { it.id })
        assertEquals(4L to 700L, back.withdrawn.day to back.withdrawn.spent)
        assertEquals(500L, back.withdrawals.single().amount)
    }

    @Test
    fun aCorruptBudgetBlobFallsBackToDefaultsInsteadOfAbortingTheLoad() {
        Db.open(file)
        val fresh = RealmDb.load()
        val c = Country("Broken").also { fresh.countries[it.id] = it }
        reload(fresh)
        Db.write { exec("UPDATE claims_country SET data = json_set(data, '$.payBudget', 'oops', '$.withdrawals', 5) WHERE id = ?", c.id) }
        Db.flush()
        Db.close()
        Db.open(file)
        val back = RealmDb.load().countries.getValue(c.id)
        assertEquals(-1L, back.payBudget.day)
        assertTrue(back.withdrawals.isEmpty())
    }

    @Test
    fun placedBlocksSurviveAReloadAndDropWhenRemoved() {
        Db.open(file)
        val fresh = RealmDb.load()
        val dim = "minecraft:overworld"
        PlacedLog.mark(dim, 1001, "alice")
        PlacedLog.mark(dim, 2002)
        PlacedLog.mark(dim, 3003, "bob")
        reload(fresh)
        assertTrue(PlacedLog.contains(dim, 1001))
        assertTrue(PlacedLog.contains(dim, 2002))
        assertTrue(PlacedLog.takeBy(dim, 1001, "alice"))
        assertFalse(PlacedLog.takeBy(dim, 2002, "alice"))
        assertFalse(PlacedLog.takeBy(dim, 3003, "alice"))
        PlacedLog.remove(dim, 3003)
        reload(Realm.data)
        assertTrue(PlacedLog.contains(dim, 1001))
        assertFalse(PlacedLog.takeBy(dim, 1001, "alice"))
        assertFalse(PlacedLog.contains(dim, 3003))
    }

    @Test
    fun migrationsMatchLock() {
        val lock = javaClass.getResource("/migrations.lock")!!.readText().lines().filter { it.isNotBlank() }
        assertEquals(lock, Migrations.signature("claims"))
    }
}
