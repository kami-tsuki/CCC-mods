package kami.claims

import kami.claims.research.*
import kami.libs.stats.Stats
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LevelsStatsTest : WithDb() {
    private lateinit var country: Country

    @BeforeTest
    fun setup() {
        Realm.reset(Data())
        country = Country("alpha", treasury = 1000)
        Realm.data.countries[country.id] = country
        Research.defs = Validator.build(
            ResearchSettings(baseline = emptyList()),
            LevelsConfig(xpFromLevel = 0, rules = emptyMap(), sources = mapOf("wage" to XpSource(1.0, dailyCap = 10))),
            emptyMap(), emptyMap()
        ).defs
        Levels.announce = {}
        Stats.load()
    }

    private fun total(obj: String): Long {
        Stats.flush()
        kami.libs.db.Db.flush()
        return Stats.total("xp.gain", null, "c:alpha", obj)
    }

    @Test
    fun `grant records capped amount`() {
        Levels.grant(country, "wage", 8)
        Levels.grant(country, "wage", 8)
        assertEquals(10L, total("wage"))
        assertEquals(10L, country.xp)
    }

    @Test
    fun `admin award recorded once`() {
        Levels.award(country, 25)
        assertEquals(25L, total("admin"))
        assertEquals(0L, total("wage"))
        assertEquals(25L, country.xp)
    }
}
