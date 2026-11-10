package kami.economy.villager

import kami.economy.VillagerSettings
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VillagerMathTest {
    private val s = VillagerSettings()

    @Test
    fun spreadIsBoundedAndStablePerEpoch() {
        val id = UUID.randomUUID()
        val all = (0 until 200).map { VillagerMath.spread(id, it, 3, 0.05) }
        assertTrue(all.all { it in -0.05..0.05 })
        assertTrue(all.any { it < -0.03 } && all.any { it > 0.03 })
        assertEquals(VillagerMath.spread(id, 4, 3, 0.05), VillagerMath.spread(id, 4, 3, 0.05))
        assertNotEquals(VillagerMath.spread(id, 4, 3, 0.05), VillagerMath.spread(id, 4, 4, 0.05))
    }

    @Test
    fun demandOnlyWorsensTheDealAndIsCapped() {
        assertEquals(1.0, VillagerMath.demand(-20, 0.05f, 0.5))
        assertEquals(1.25, VillagerMath.demand(5, 0.05f, 0.5), 1e-6)
        assertEquals(1.5, VillagerMath.demand(500, 0.2f, 0.5))
        assertEquals(125, VillagerMath.price(100.0, Side.SELL, 0.0, 1.25, 1))
        assertEquals(80, VillagerMath.price(100.0, Side.BUY, 0.0, 1.25, 1))
        assertEquals(1, VillagerMath.price(0.2, Side.BUY, -0.05, 1.0, 1))
    }

    @Test
    fun roundTripsBetweenVillagersLoseMoney() {
        val buy = VillagerMath.price(1000.0, Side.SELL, -0.05, 1.0, 1)
        val sell = VillagerMath.price(1000.0, Side.BUY, 0.05, 1.0, 1)
        assertTrue(sell - VillagerMath.tax(sell, 10) < buy)
    }

    @Test
    fun seedsFollowTheChainAndSide() {
        assertEquals(10.0 * 1.25 * 3, VillagerMath.seed(10.0, 5.0, Side.SELL, s))
        assertEquals(5.0 * 0.5, VillagerMath.seed(null, 5.0, Side.BUY, s))
        assertNull(VillagerMath.seed(null, null, Side.SELL, s))
    }

    @Test
    fun enchantSeedFollowsTheLibrarian() {
        assertEquals(12.0, VillagerMath.enchantEmeralds(false))
        assertEquals(24.0, VillagerMath.enchantEmeralds(true))
    }

    @Test
    fun decayStopsAtTheFloor() {
        val day = VillagerMath.DAY_MS
        assertEquals(96.0, VillagerMath.decayed(100.0, 10.0, 0, day, 0.04), 1e-9)
        assertEquals(10.0, VillagerMath.decayed(100.0, 10.0, 0, day * 500, 0.04))
        assertEquals(5.0, VillagerMath.decayed(5.0, 10.0, 0, day * 5, 0.04))
    }

    @Test
    fun pullIsClippedAndWeighted() {
        assertEquals(100.0 + 0.25 / 32.25 * 5, VillagerMath.pull(100.0, 200.0, 0.25, 32, 0.05), 1e-9)
        assertEquals(7.0, VillagerMath.pull(0.0, 7.0, 1.0, 32, 0.05))
        assertEquals(2.5, VillagerMath.median(listOf(4.0, 1.0, 2.0, 3.0)))
    }
}
