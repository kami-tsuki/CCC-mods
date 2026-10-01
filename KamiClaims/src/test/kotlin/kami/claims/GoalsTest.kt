package kami.claims

import kami.claims.research.*
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GoalsTest {
    private fun install() {
        val levels = LevelsConfig(
            maxLevel = 5, xpFromLevel = 0,
            rules = mapOf(2 to LevelRule(xp = 100, requires = listOf(MinChunks(2)))),
            capacities = mapOf(Capacity.CHUNKS to 3, Capacity.PLOTS to 4),
            rewards = mapOf(2 to listOf(CapacityUnlock(Capacity.CHUNKS, 5)), 4 to listOf(CapacityUnlock(Capacity.CHUNKS, 7), CapacityUnlock(Capacity.PLOTS, 2)))
        )
        Research.defs = Validator.build(ResearchSettings(baseline = emptyList()), levels, emptyMap(), emptyMap()).defs
    }

    private fun country(): Country {
        val c = Country("alpha", treasury = 1000)
        Realm.data.countries[c.id] = c
        Realm.join(c, "alpha_p", Rank.PRESIDENT)
        return c
    }

    @BeforeTest
    fun setup() {
        Realm.reset(Data())
        install()
    }

    @AfterTest
    fun teardown() {
        Research.defs = ResearchDefs.EMPTY
        Realm.reset(Data())
    }

    @Test
    fun nextRaiseNamesTheLevelAndNewMaximum() {
        val c = country()
        val raise = assertNotNull(Goals.nextRaise(c, Capacity.CHUNKS))
        assertEquals(2, raise.level)
        assertEquals(8, raise.max)
        assertEquals(4, assertNotNull(Goals.nextRaise(c, Capacity.PLOTS)).level)
        assertNull(Goals.nextRaise(c, Capacity.OFFICERS))
    }

    @Test
    fun requirementsComeBeforeExperience() {
        val goals = Goals.of(country())
        assertEquals(2L, goals[0].max)
        assertEquals(100L, goals[1].max)
    }

    @Test
    fun plotsAreALimitedCapacity() {
        val c = country()
        assertNull(Features.limit(c, Capacity.PLOTS, 3))
        assertNotNull(Features.limit(c, Capacity.PLOTS, 4))
    }
}
