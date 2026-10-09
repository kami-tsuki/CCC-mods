package kami.claims.world

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PvpRulesTest {
    @Test
    fun warOverridesClaimRules() {
        assertTrue(PvpRules.allowed(true, true, false, false))
        assertFalse(PvpRules.allowed(false, true, true, true))
    }

    @Test
    fun claimAndWilderness() {
        assertTrue(PvpRules.allowed(null, true, true, false))
        assertFalse(PvpRules.allowed(null, true, false, true))
        assertTrue(PvpRules.allowed(null, false, false, true))
        assertFalse(PvpRules.allowed(null, false, true, false))
    }

    @Test
    fun trainingTargets() {
        assertTrue(PvpRules.trainingTarget(setOf(PvpRules.TARGET_CLASS), false))
        assertTrue(PvpRules.trainingTarget(setOf("x", PvpRules.TARGET_INTERFACE), false))
        assertTrue(PvpRules.trainingTarget(emptySet(), true))
        assertFalse(PvpRules.trainingTarget(setOf("net.minecraft.world.entity.vehicle.Minecart"), false))
    }
}
