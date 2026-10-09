package kami.claims.world

import kami.claims.world.FrameRule.Kind.ATTACK
import kami.claims.world.FrameRule.Kind.USE
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameRuleTest {
    @Test
    fun plainFrame() {
        assertFalse(FrameRule.allows(USE, false, false, false))
        assertFalse(FrameRule.allows(USE, false, true, true))
        assertTrue(FrameRule.allows(USE, false, false, true))
        assertTrue(FrameRule.allows(ATTACK, false, true, true))
        assertFalse(FrameRule.allows(ATTACK, false, false, true))
    }

    @Test
    fun lootrFrame() {
        assertTrue(FrameRule.allows(USE, true, true, false))
        assertFalse(FrameRule.allows(ATTACK, true, true, true))
    }
}
