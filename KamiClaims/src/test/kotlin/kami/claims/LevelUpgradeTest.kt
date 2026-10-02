package kami.claims

import kami.claims.research.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

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
}
