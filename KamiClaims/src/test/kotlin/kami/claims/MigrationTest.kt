package kami.claims

import kami.libs.config.Configs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {
    private val dim = "minecraft:overworld"

    private val old = """
        {"countries": {"old": {"name": "Old", "tax": 7, "shutdown": 2, "release": 4, "members": {
            "p1": {"rank": "PRESIDENT", "job": "miner", "progress": 12, "start": 5, "zone": ["$dim|1|0"]},
            "p2": {"rank": "CITIZEN", "job": "worker"},
            "p3": {"rank": "CITIZEN"}
        }}},
        "claims": [
            {"country": "old", "dim": "$dim", "x": 0, "z": 0, "type": "mining"},
            {"country": "old", "dim": "$dim", "x": 1, "z": 0, "type": "mining"},
            {"country": "old", "dim": "$dim", "x": 2, "z": 0, "type": "factory"},
            {"country": "old", "dim": "$dim", "x": 3, "z": 0, "type": "residential", "owner": "p1", "tax": 9, "lapse": 2},
            {"country": "old", "dim": "$dim", "x": 4, "z": 0, "type": "residential", "owner": "p2", "tax": -1, "lapse": 0},
            {"country": "old", "dim": "$dim", "x": 5, "z": 0, "type": "residential", "owner": "p3", "tax": 9, "lapse": 5}
        ]}
    """

    @AfterTest
    fun clean() = Realm.reset(Data())

    private fun migrate(): Country {
        Realm.reset(Configs.json().decodeFromString(Data.serializer(), old))
        return Realm.data.countries.getValue("old")
    }

    private fun claim(x: Int) = assertNotNull(Realm.at(dim, x, 0))

    @Test
    fun jobsMoveIntoTheJobMapAndZonesBecomeWorkers() {
        val c = migrate()
        val job = assertNotNull(c.members.getValue("p1").jobs["miner"])
        assertEquals(12, job.progress)
        assertEquals(5L, job.start)
        assertEquals(emptySet(), claim(0).workers)
        assertEquals(setOf("p1"), claim(1).workers)
        assertEquals(setOf("p2"), claim(2).workers)
        assertTrue(c.members.getValue("p3").jobs.isEmpty())
    }

    @Test
    fun plotsGetOffersCategoriesAndDebt() {
        migrate()
        assertEquals(9, claim(3).offer?.rent?.get(Claimant.CITIZEN))
        assertEquals(Claimant.CITIZEN, claim(3).category)
        assertEquals(18L, claim(3).rentDebt)
        assertEquals(Tenancy.ACTIVE, claim(3).state)
        assertNull(claim(4).offer)
        assertEquals(0L, claim(4).rentDebt)
        assertNull(claim(0).category)
    }

    @Test
    fun countryRentAndDebtLimitComeFromTheOldValues() {
        val c = migrate()
        assertEquals(7, c.offer.rent[Claimant.CITIZEN])
        assertEquals(42L, c.rentDebtLimit)
        assertEquals(3, c.moveOutDays)
        assertEquals(CountryState.ACTIVE, c.state)
    }

    @Test
    fun debtOverTheLimitStartsTheMoveOut() {
        migrate()
        val plot = claim(5)
        assertEquals(45L, plot.rentDebt)
        assertEquals(Tenancy.MOVING_OUT, plot.state)
        assertTrue(plot.until > now())
    }

    @Test
    fun legacyFieldsAreClearedAndTheSchemaAdvances() {
        val c = migrate()
        assertEquals(Data.CURRENT, Realm.data.schema)
        assertNull(c.tax)
        assertNull(c.shutdown)
        assertNull(c.release)
        c.members.values.forEach { m ->
            assertNull(m.job)
            assertNull(m.progress)
            assertNull(m.start)
            assertNull(m.zone)
        }
        Realm.data.claims.forEach { assertNull(it.tax); assertNull(it.lapse) }
    }

    @Test
    fun migratedDataIsNotMigratedTwice() {
        migrate()
        claim(3).rentDebt = 1
        Realm.reset(Realm.data)
        assertEquals(1L, claim(3).rentDebt)
    }
}
