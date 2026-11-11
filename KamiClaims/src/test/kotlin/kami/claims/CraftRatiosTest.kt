package kami.claims

import kami.claims.service.CraftRatios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CraftRatiosTest {
    @Test
    fun planksYieldFourPerInputAndCostAFullInput() {
        assertEquals(4, CraftRatios.allowed("minecraft:oak_planks", 8, 1.0))
        assertEquals(1.0, CraftRatios.cost("minecraft:oak_planks", 4))
    }

    @Test
    fun sticksYieldEightPerInput() {
        assertEquals(8, CraftRatios.allowed("minecraft:stick", 8, 1.0))
        assertEquals(0, CraftRatios.allowed("minecraft:stick", 8, 0.0))
        assertEquals(1.0, CraftRatios.cost("minecraft:stick", 8))
    }

    @Test
    fun unknownItemsAreAllowedUpToTheRequestedCount() {
        assertEquals(5, CraftRatios.allowed("minecraft:diamond_pickaxe", 5, 0.0))
        assertNull(CraftRatios.cost("minecraft:diamond_pickaxe", 5))
    }
}
