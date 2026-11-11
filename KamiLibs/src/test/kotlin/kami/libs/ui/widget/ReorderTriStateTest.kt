package kami.libs.ui.widget

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReorderTriStateTest {
    @Test
    fun reorderRespectsLocksAndRange() {
        val locked = { i: Int -> i == 0 }
        assertEquals(2, reorderTarget(1, 2, 4, locked) { _, _ -> true })
        assertNull(reorderTarget(1, 0, 4, locked) { _, _ -> true })
        assertNull(reorderTarget(3, 4, 4, locked) { _, _ -> true })
        assertNull(reorderTarget(1, 2, 4, locked) { _, to -> to < 2 })
    }

    @Test
    fun dropIndexClamps() {
        assertEquals(0, dropIndex(-30, 10, 20, 5))
        assertEquals(4, dropIndex(500, 10, 20, 5))
    }

    @Test
    fun contrastRatioMatchesWcag() {
        assertEquals(21.0, contrastRatio(0xFFFFFF, 0x000000), 0.01)
        assertEquals(1.0, contrastRatio(0x336699, 0x336699), 0.001)
        assertTrue(contrastRatio(0x777777, 0x888888) < 3.0)
        assertEquals(0x1E88E5, parseHex("#1e88e5"))
        assertNull(parseHex("#12"))
    }

    @Test
    fun triStateFromCounts() {
        assertEquals(TriState.OFF, triState(0, 5))
        assertEquals(TriState.PARTIAL, triState(2, 5))
        assertEquals(TriState.ON, triState(5, 5))
        assertEquals(TriState.OFF, triState(0, 0))
    }
}
