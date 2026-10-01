package kami.claims

import kami.claims.research.*
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressXpTest {
    private fun country(): Country {
        val c = Country("alpha", treasury = 1000)
        Realm.data.countries[c.id] = c
        Realm.join(c, "alpha_p", Rank.PRESIDENT)
        return c
    }

    @BeforeTest
    fun setup() {
        Realm.reset(Data())
        Research.defs = Validator.build(
            ResearchSettings(baseline = emptyList()),
            LevelsConfig(xpFromLevel = 0, rules = emptyMap(), sources = mapOf("wage" to XpSource(0.5), "alliance" to XpSource(150.0)), counted = listOf("wage", "alliance")),
            emptyMap(), emptyMap()
        ).defs
        Levels.announce = {}
    }

    @Test
    fun `fractional xp accumulates`() {
        val c = country()
        repeat(3) { Progress.report(c, "wage", "", 1) }
        assertEquals(1L, c.xp)
        assertEquals(3L, c.counters["wage"])
    }

    @Test
    fun `kind xp and once dedupe`() {
        val c = country()
        Progress.report(c, "alliance", "beta", 1, "alliance:beta")
        Progress.report(c, "alliance", "beta", 1, "alliance:beta")
        assertEquals(150L, c.xp)
        Progress.report(c, "alliance", "gamma", 1, "alliance:gamma")
        assertEquals(300L, c.xp)
    }
}
