package kami.claims

import kami.claims.economy.Treasury
import kami.claims.research.*
import kami.claims.service.Fail
import kami.claims.service.Naming
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelSpecTest {
    private var chunk = 0

    private fun country(level: Int = 1): Country {
        val c = Country("alpha", treasury = 1000, level = level)
        Realm.data.countries[c.id] = c
        Realm.join(c, "alpha_p", Rank.PRESIDENT)
        return c
    }

    private fun claim(c: Country, type: String, count: Int = 1) = repeat(count) { Realm.add(Claim(c.id, "minecraft:overworld", chunk++, 0, type)) }

    @BeforeTest
    fun setup() {
        Realm.reset(Data())
        chunk = 0
        Research.defs = ResearchDefs(ResearchSettings(), LevelsConfig(), emptyMap(), emptyMap())
        Levels.announce = {}
    }

    @AfterTest
    fun restore() {
        Research.defs = ResearchDefs.EMPTY
    }

    @Test
    fun levelsTwoAndThreeNeedChunksThenOneOfEachResource() {
        val c = country()
        claim(c, "civic", 8)
        Levels.award(c, 0)
        assertEquals(1, c.level)
        claim(c, "civic")
        Levels.award(c, 0)
        assertEquals(2, c.level)
        claim(c, "mining")
        claim(c, "forestry")
        Levels.award(c, 0)
        assertEquals(2, c.level)
        claim(c, "farming")
        Levels.award(c, 0)
        assertEquals(3, c.level)
    }

    @Test
    fun treasuryCapClampsIncomeButNotOutflowOrAdjustments() {
        val c = country()
        val cap = Levels.capacity(c, Capacity.TREASURY).toLong()
        c.treasury = cap - 500
        assertEquals(500, Treasury.move(c, LedgerKind.PLOT_TAX, 800))
        assertEquals(0, Treasury.move(c, LedgerKind.TRIBUTE_IN, 100))
        assertEquals(1_000, Treasury.move(c, LedgerKind.ADJUST, 1_000))
        assertEquals(0, Treasury.room(c))
        assertEquals(-5_000, Treasury.move(c, LedgerKind.WITHDRAW, -5_000))
        assertEquals(4_000, Treasury.room(c))
    }

    @Test
    fun lockedFeaturesNameTheirLevel() {
        val c = country()
        val banish = Research.defs.levels.featureLevel(Features.BANISH)!!
        assertTrue(banish > 1)
        assertTrue(Research.defs.levels.featureLevel(Features.claimType("residential"))!! > 1)
        assertNull(Research.defs.levels.featureLevel(Features.claimType("civic")))
        assertFailsWith<Fail> { Features.require(c, Features.BANISH) }
        assertFailsWith<Fail> { Features.requireRank(c, Rank.OFFICER) }
        c.level = 100
        Features.require(c, Features.BANISH)
        Features.requireRank(c, Rank.CHANCELLOR)
    }

    @Test
    fun renameConsumesTheTokenThenChargesTheTreasury() {
        val c = country()
        Tokens.grant(c, Tokens.RENAME, 1)
        Naming.rename(c, "Gamma", null)
        assertEquals("Gamma", c.name)
        assertEquals(c, Realm.country("alpha"))
        assertEquals(1000, c.treasury)
        assertFailsWith<Fail> { Naming.rename(c, "Delta", null) }
        c.treasury = 60_000
        Naming.rename(c, "Delta", null)
        assertEquals(60_000 - Config.s.renameCost, c.treasury)
    }

    @Test
    fun xpCurveIsConcaveToTheMidgameThenConvex() {
        val levels = LevelsConfig()
        val cost = (0..100).map { levels.xpFor(it) - levels.xpFor(maxOf(it - 1, 0)) }
        assertTrue((11..99).all { cost[it + 1] >= cost[it] })
        assertTrue(cost[21] - cost[11] > cost[41] - cost[31])
        assertTrue(cost[60] - cost[50] < cost[100] - cost[90])
    }
}
