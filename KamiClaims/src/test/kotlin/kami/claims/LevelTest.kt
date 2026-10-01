package kami.claims

import kami.claims.research.*
import kami.claims.service.Planner
import kami.libs.config.Configs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelTest {
    private var day = 1L

    private fun config(capacities: Map<Capacity, Int> = emptyMap(), rewards: Map<Int, List<Unlock>> = emptyMap()) =
        LevelsConfig(curve = XpCurve(from = 2, base = 100.0, rise = 0.0), maxLevel = 20, xpFromLevel = 0, rules = emptyMap(), capacities = capacities, rewards = rewards)

    private fun install(nodes: String = "", levels: LevelsConfig = config(capacities = mapOf(Capacity.CHUNKS to 3, Capacity.CITIZENS to 2, Capacity.QUEUE_SLOTS to 1))) {
        val tree = Configs.json().decodeFromString(TreeFile.serializer(), """{"categories":[{"id":"c"}],"nodes":[$nodes]}""")
        Research.defs = Validator.build(ResearchSettings(baseline = emptyList()), levels, mapOf("g" to Group()), mapOf("t" to tree)).defs
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
        Levels.day = { day }
        Levels.announce = {}
    }

    @AfterTest
    fun restore() {
        Levels.day = ::today
        Research.defs = ResearchDefs.EMPTY
    }

    @Test
    fun dailyCapLimitsASourceAndResetsNextDay() {
        val c = country()
        Levels.grant(c, "tasks", 200)
        Levels.grant(c, "tasks", 200)
        assertEquals(300L, c.xp)
        assertEquals(300L, c.xpToday["tasks"])
        day++
        Levels.grant(c, "tasks", 50)
        assertEquals(350L, c.xp)
        assertEquals(50L, c.xpToday["tasks"])
    }

    @Test
    fun rateAppliesToUnitsAndUnknownSourcesGiveNothing() {
        val c = country()
        Levels.add(c, "citizen", 2.0)
        assertEquals(50L, c.xp)
        Levels.add(c, "nothing", 5.0)
        assertEquals(50L, c.xp)
    }

    @Test
    fun levelRisesWithXpAndAdminAwardIgnoresCaps() {
        val c = country()
        assertEquals(1, Research.level(c))
        Levels.award(c, 100)
        assertEquals(2, Research.level(c))
        Levels.award(c, 1_000_000)
        assertEquals(20, Research.level(c))
    }

    @Test
    fun capacityIsBasePlusDoneCapacityUnlocks() {
        install("""{"id":"n","category":"c","unlocks":[{"type":"capacity","key":"chunks","add":5}]}""")
        val c = country()
        assertEquals(3, Levels.capacity(c, Capacity.CHUNKS))
        c.research.done["t:n"] = 1
        assertEquals(8, Levels.capacity(c, Capacity.CHUNKS))
        assertEquals(2, Levels.capacity(c, Capacity.CITIZENS))
    }

    @Test
    fun levelRewardsPayMoneyOnceAndRaiseCapacity() {
        install(levels = config(
            capacities = mapOf(Capacity.CHUNKS to 3),
            rewards = mapOf(2 to listOf(MoneyReward(100)), 3 to listOf(MoneyReward(50), CapacityUnlock(Capacity.CHUNKS, 4)))
        ))
        val c = country()
        assertEquals(3, Levels.capacity(c, Capacity.CHUNKS))
        Levels.award(c, 100)
        assertEquals(1100L, c.treasury)
        assertEquals(3, Levels.capacity(c, Capacity.CHUNKS))
        Levels.award(c, 300)
        assertEquals(1150L, c.treasury)
        assertEquals(7, Levels.capacity(c, Capacity.CHUNKS))
        Levels.settleRewards(c)
        Levels.award(c, 1)
        assertEquals(1150L, c.treasury)
        assertEquals(listOf(LedgerKind.LEVEL_REWARD, LedgerKind.LEVEL_REWARD), c.ledger.map { it.kind })
    }

    @Test
    fun moneyRewardWaitsForTreasuryRoomThenPaysOnce() {
        install(levels = config(rewards = mapOf(2 to listOf(MoneyReward(100)))))
        val c = country()
        c.treasury = Levels.capacity(c, Capacity.TREASURY) - 50L
        val full = c.treasury
        Levels.award(c, 100)
        assertEquals(2, Research.level(c))
        assertEquals(full, c.treasury)
        assertEquals(1, c.rewardedLevel)
        c.treasury = 0
        Levels.settleRewards(c)
        assertEquals(100L, c.treasury)
        assertEquals(2, c.rewardedLevel)
        Levels.settleRewards(c)
        assertEquals(100L, c.treasury)
    }

    @Test
    fun existingCountriesAreRewardedOnceOnFirstLoad() {
        install(levels = config(rewards = mapOf(2 to listOf(MoneyReward(100)), 3 to listOf(MoneyReward(50)))))
        val c = country()
        c.xp = 400
        assertEquals(0, c.rewardedLevel)
        Levels.advanceAll()
        Levels.advanceAll()
        assertEquals(1150L, c.treasury)
        assertEquals(5, c.rewardedLevel)
    }

    @Test
    fun overCapacityCountriesKeepTheirLandButCannotGrow() {
        val c = country()
        repeat(4) { Realm.add(Claim(c.id, "minecraft:overworld", it, 0, "civic")) }
        val owned = Realm.claims(c.id).map { it.key }.toSet()
        val next = Key("minecraft:overworld", 4, 0)
        assertNotNull(Planner.blockReason(c, next, "civic", owned, 1000, 4))
        assertEquals(4, Realm.claims(c.id).size)
        assertTrue(4 >= Levels.capacity(c, Capacity.CHUNKS))
        install("""{"id":"n","category":"c","unlocks":[{"type":"capacity","key":"chunks","add":2}]}""")
        c.research.done["t:n"] = 1
        assertNull(Planner.blockReason(c, next, "civic", owned, 1000, 4))
    }
}
