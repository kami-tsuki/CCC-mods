package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.Data
import kami.claims.Member
import kami.claims.Realm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyMigrationTest : GovTest() {
    private fun oldCountry(): Country = Country("Old").also {
        it.members["p"] = Member("PRESIDENT")
        it.members["c"] = Member("CHANCELLOR")
        it.members["o"] = Member("OFFICER")
        it.members["z"] = Member("CITIZEN")
    }

    private fun migrated(c: Country): Country {
        val data = Data(schema = 1)
        data.countries[c.id] = c
        Realm.reset(data)
        return c
    }

    @Test
    fun oldCountriesGetTheFourClassicRanksInOrder() {
        val c = migrated(oldCountry())
        assertEquals(listOf(PRESIDENT, "chancellor", "officer", CITIZEN), c.ranks.map { it.id })
        assertEquals(listOf("President", "Chancellor", "Officer", "Citizen"), c.ranks.map { it.name })
        assertEquals(Data.CURRENT, Realm.data.schema)
    }

    @Test
    fun memberRanksAreLowercasedOntoTheNewIds() {
        val c = migrated(oldCountry())
        assertEquals(mapOf("p" to PRESIDENT, "c" to "chancellor", "o" to "officer", "z" to CITIZEN), c.members.mapValues { it.value.rank })
        assertEquals("p", Permissions.president(c))
    }

    @Test
    fun migratedRanksKeepTheOldPowerGradient() {
        val c = migrated(oldCountry())
        assertTrue(Permissions.holds(c, "c", Perm.WITHDRAW))
        assertTrue(Permissions.holds(c, "c", Perm.TAXES))
        assertTrue(Permissions.holds(c, "o", Perm.KICK))
        assertTrue(Permissions.holds(c, "o", Perm.ASSIGN_JOBS))
        assertFalse(Permissions.holds(c, "o", Perm.TAXES))
        assertFalse(Permissions.holds(c, "o", Perm.WITHDRAW))
        assertFalse(Permissions.holds(c, "z", Perm.KICK))
        assertTrue(Permissions.holds(c, "z", Perm.BREAK))
        assertFalse(Permissions.holds(c, "c", Perm.WAR))
        assertTrue(Permissions.holds(c, "p", Perm.WAR))
    }

    @Test
    fun seedingTwiceNeverDuplicatesOrTouchesExistingRanks() {
        val c = migrated(oldCountry())
        c.ranks.first { it.id == "officer" }.name = "Sergeant"
        RankMigration.seed(c)
        Jobs.seed(c)
        assertEquals(4, c.ranks.size)
        assertEquals("Sergeant", c.ranks[2].name)
    }

    @Test
    fun countriesWithRanksAreLeftAlone() {
        val c = Country("Modern")
        c.ranks += Presets.starterRanks(PresetId.COMMUNE)
        RankMigration.seed(c)
        assertEquals(listOf(PRESIDENT, CITIZEN), c.ranks.map { it.id })
    }

    @Test
    fun jobSeedingTurnsConfigDefaultsIntoJobSpecs() {
        val c = migrated(oldCountry())
        assertEquals(Config.s.jobs.keys, c.jobDefs.keys)
        Config.s.jobs.forEach { (id, cfg) ->
            val spec = c.jobDefs.getValue(id)
            assertEquals(cfg.pay, spec.pay, id)
            assertEquals(cfg.quota, spec.quota, id)
            assertEquals(cfg.period, spec.period, id)
            assertEquals(JobScope.TYPE, spec.scope, id)
            assertEquals(cfg.type, spec.scopeRef, id)
            assertEquals(cfg.blocks.joinToString(","), spec.tasks.single().filter, id)
        }
        assertEquals(TaskKind.MINE, c.jobDefs.getValue("miner").tasks.single().kind)
        assertEquals(TaskKind.BUILD, c.jobDefs.getValue("worker").tasks.single().kind)
    }

    @Test
    fun jobSeedingKeepsCustomisedJobs() {
        val c = Country("Custom")
        c.jobDefs["scribe"] = JobSpec("scribe", "Scribe")
        Jobs.seed(c)
        assertEquals(setOf("scribe"), c.jobDefs.keys)
    }
}
