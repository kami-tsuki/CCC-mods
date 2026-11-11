package kami.libs.ui

import kami.libs.ui.core.Tip
import kami.libs.ui.widget.ScrollMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputLogicTest {
    @Test
    fun detailedTooltipsAddExtraLines() {
        val tip = Tip(title = "t", lines = listOf("a" to 1), keys = "K", more = listOf("b" to 2))
        assertEquals(2, tip.shown(true).lines.size)
        assertEquals(1, tip.shown(false).lines.size)
        assertNull(tip.shown(false).keys)
    }

    @Test
    fun scrollMathStaysInsideTrack() {
        val thumb = ScrollMath.thumbLength(100, 50, 400)
        assertTrue(thumb in 14..100)
        assertEquals(100 - thumb, ScrollMath.thumbPos(100, thumb, 350.0, 350))
        assertEquals(0.0, ScrollMath.offsetAt(-30.0, 100, thumb, 350))
        assertEquals(350.0, ScrollMath.offsetAt(500.0, 100, thumb, 350))
        assertTrue(ScrollMath.wheelStep(10000, 200) <= 64)
    }
}
