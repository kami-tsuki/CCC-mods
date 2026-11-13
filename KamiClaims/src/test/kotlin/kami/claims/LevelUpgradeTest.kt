package kami.claims

import kami.claims.research.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LevelUpgradeTest {
    private fun legacy() = LevelsConfig(
        capacities = LevelDefaults.capacities - Capacity.JOB_SLOTS + (Capacity.PLOTS to 4),
        rewards = LevelDefaults.rewards
            .mapValues { (_, list) -> list.filterNot { (it is FeatureUnlock && it.id.startsWith("plots:")) || (it is CapacityUnlock && it.key == Capacity.JOB_SLOTS) } }
            .filterValues { it.isNotEmpty() }
    )

    private fun jobSlots(config: LevelsConfig, level: Int) =
        config.rewardedCapacity(level, Capacity.JOB_SLOTS)

    @Test
    fun legacyFileGainsPlotFeaturesPlotCeilingAndJobSlots() {
        val old = legacy()
        assertNull(old.featureLevel(Features.PLOTS_FAMILY))
        val upgraded = LevelDefaults.upgrade(old)
        assertEquals(LevelDefaults.VERSION, upgraded.version)
        assertEquals(13, upgraded.featureLevel(Features.PLOTS_FAMILY))
        assertEquals(23, upgraded.featureLevel(Features.PLOTS_ALLIES))
        assertEquals(27, upgraded.featureLevel(Features.PLOTS_PUBLIC))
        assertEquals(25, upgraded.capacity(Capacity.PLOTS))
        assertEquals(1, upgraded.capacity(Capacity.JOB_SLOTS))
        assertEquals(listOf(0, 1, 1, 2, 3), listOf(15, 16, 60, 61, 125).map { jobSlots(upgraded, it) })
    }

    @Test
    fun editedValuesSurviveTheUpgrade() {
        val edited = LevelsConfig(
            capacities = mapOf(Capacity.PLOTS to 6),
            rewards = mapOf(5 to listOf(FeatureUnlock(Features.PLOTS_FAMILY)), 9 to listOf(CapacityUnlock(Capacity.JOB_SLOTS, 2)))
        )
        val upgraded = LevelDefaults.upgrade(edited)
        assertEquals(5, upgraded.featureLevel(Features.PLOTS_FAMILY))
        assertEquals(23, upgraded.featureLevel(Features.PLOTS_ALLIES))
        assertEquals(6, upgraded.capacity(Capacity.PLOTS))
        assertEquals(2, jobSlots(upgraded, 1000))
    }

    @Test
    fun currentFilesAreLeftAlone() {
        val current = legacy().copy(version = LevelDefaults.VERSION)
        assertSame(current, LevelDefaults.upgrade(current))
        assertNull(LevelDefaults.upgrade(current).featureLevel(Features.PLOTS_FAMILY))
    }

    @Test
    fun freshDefaultsAlreadyCarryTheNewRewards() {
        val defaults = LevelsConfig(version = LevelDefaults.VERSION)
        assertEquals(27, defaults.featureLevel(Features.PLOTS_PUBLIC))
        assertEquals(25, defaults.capacity(Capacity.PLOTS))
    }

    private fun oldRewards() = LevelDefaults.rewards
        .mapValues { (_, list) -> list.filterNot { it is CapacityUnlock && it.key == Capacity.RESEARCH_SLOTS } }
        .let { base -> base + listOf(10, 30, 100, 500).associateWith { base[it].orEmpty() + CapacityUnlock(Capacity.RESEARCH_SLOTS, 1) } }

    private fun slots(config: LevelsConfig, vararg levels: Int) = levels.map { config.rewardedCapacity(it, Capacity.RESEARCH_SLOTS) }

    @Test
    fun curveStopsGrowingAtFlatFrom() {
        val curve = XpCurve()
        assertEquals(0.15, curve.tail)
        assertTrue(curve.cost(250) > curve.cost(249))
        assertEquals(curve.cost(250), curve.cost(251))
        assertEquals(curve.cost(250), curve.cost(900))
        assertTrue(XpCurve(flatFrom = 0).cost(900) > curve.cost(900))
        assertEquals(XpCurve(flatFrom = 0).cost(250), curve.cost(250))
    }

    @Test
    fun stockV2FilesGetTheNewCurveSourcesAndResearchSlots() {
        val old = LevelsConfig(
            curve = XpCurve(tail = 1.5, flatFrom = 0),
            sources = LevelDefaults.sources + ("tasks" to XpSource(20.0, 300)) + ("playtime" to XpSource(15.0, 300)),
            rewards = oldRewards(),
            version = 2
        )
        val upgraded = LevelDefaults.upgrade(old)
        assertEquals(LevelDefaults.VERSION, upgraded.version)
        assertEquals(0.15, upgraded.curve.tail)
        assertEquals(250, upgraded.curve.flatFrom)
        assertEquals(30.0, upgraded.sources.getValue("tasks").rate)
        assertEquals(450L, upgraded.sources.getValue("tasks").dailyCap)
        assertEquals(20.0, upgraded.sources.getValue("playtime").rate)
        assertEquals(480L, upgraded.sources.getValue("playtime").dailyCap)
        assertEquals(listOf(1, 2, 3, 4, 5, 5), slots(upgraded, 10, 20, 30, 100, 150, 500))
    }

    @Test
    fun handTunedV2FilesKeepTheirValues() {
        val edited = LevelsConfig(
            curve = XpCurve(tail = 3.0, flatFrom = 0),
            sources = LevelDefaults.sources + ("tasks" to XpSource(25.0, 300)),
            rewards = oldRewards() + (60 to listOf(CapacityUnlock(Capacity.RESEARCH_SLOTS, 1))),
            version = 2
        )
        val upgraded = LevelDefaults.upgrade(edited)
        assertEquals(3.0, upgraded.curve.tail)
        assertEquals(0, upgraded.curve.flatFrom)
        assertEquals(25.0, upgraded.sources.getValue("tasks").rate)
        assertEquals(20.0, upgraded.sources.getValue("playtime").rate)
        assertEquals(slots(edited, 10, 20, 60, 500), slots(upgraded, 10, 20, 60, 500))
    }

    @Test
    fun stockV3CurveGetsTheSlowerTailAndCustomOnesStay() {
        val stock = LevelsConfig(curve = XpCurve(tail = 0.2), version = 3)
        val upgraded = LevelDefaults.upgrade(stock)
        assertEquals(LevelDefaults.VERSION, upgraded.version)
        assertEquals(0.15, upgraded.curve.tail)
        assertEquals(250, upgraded.curve.flatFrom)
        val custom = LevelDefaults.upgrade(LevelsConfig(curve = XpCurve(tail = 0.3), version = 3))
        assertEquals(0.3, custom.curve.tail)
        val flat = LevelDefaults.upgrade(LevelsConfig(curve = XpCurve(tail = 0.2, flatFrom = 0), version = 3))
        assertEquals(0.2, flat.curve.tail)
    }

    @Test
    fun defaultCurvePacesLevel250WithinReachOfResearchXp() {
        val curve = XpCurve()
        val total = (11..250).sumOf { curve.cost(it) }
        assertTrue(total in 500_000L..580_000L, total.toString())
    }

    @Test
    fun researchXpIsCappedDailyAndFlattensAboveLevel30() {
        assertEquals(400L, LevelDefaults.sources.getValue("research").dailyCap)
        val config = LevelsConfig()
        assertEquals(1000L, config.nodeXpFor(10))
        assertEquals(3000L, config.nodeXpFor(30))
        assertEquals(3000L + 20 * 20, config.nodeXpFor(50))
        val stock = LevelDefaults.upgrade(LevelsConfig(sources = LevelDefaults.sources + ("research" to XpSource(0.1)), version = 4))
        assertEquals(400L, stock.sources.getValue("research").dailyCap)
        val edited = LevelDefaults.upgrade(LevelsConfig(sources = LevelDefaults.sources + ("research" to XpSource(0.2)), version = 4))
        assertEquals(0L, edited.sources.getValue("research").dailyCap)
    }

    @Test
    fun losslessProcessingIsNotCredited() {
        assertTrue(!Processing.credits("create:filling", "minecraft:glass_bottle", "minecraft:honey_bottle"))
        assertTrue(!Processing.credits("create:emptying", "minecraft:honey_bottle", "minecraft:glass_bottle"))
        assertTrue(!Processing.credits("create:pressing", "minecraft:iron_ingot", "minecraft:iron_ingot"))
        assertTrue(Processing.credits("create:pressing", "minecraft:iron_ingot", "create:iron_sheet"))
    }
}
