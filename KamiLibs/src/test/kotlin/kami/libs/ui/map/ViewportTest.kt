package kami.libs.ui.map

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Slop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewportTest {
    private fun viewport() = Viewport(1.0, 0.5, 2.5).apply { view = Rect(0, 0, 100, 100) }

    @Test
    fun fitWidensZoomRangeSoFirstWheelStepIsContinuous() {
        val v = viewport()
        v.fit(0.0, 0.0, 1000.0, 500.0)
        assertEquals(10.0, v.unitsPerPx, 1e-9)
        assertEquals(500.0, v.cx, 1e-9)
        assertEquals(250.0, v.cz, 1e-9)
        assertTrue(v.maxUnitsPerPx >= 10.0)
        v.zoomAt(0.8)
        assertEquals(8.0, v.unitsPerPx, 1e-9)
    }

    @Test
    fun fitOfSmallContentLowersMinimum() {
        val v = viewport()
        v.fit(0.0, 0.0, 10.0, 10.0)
        assertEquals(0.1, v.unitsPerPx, 1e-9)
        assertEquals(0.1, v.minUnitsPerPx, 1e-9)
    }

    @Test
    fun boundsClampPanAndZoom() {
        val v = viewport()
        v.limit(0.0, 0.0, 200.0, 100.0)
        v.pan(-5000.0, 5000.0)
        assertEquals(200.0, v.cx, 1e-9)
        assertEquals(0.0, v.cz, 1e-9)
        v.cx = 100.0
        v.cz = 50.0
        v.zoomAt(2.0, 0.0, 0.0)
        assertTrue(v.cx in 0.0..200.0 && v.cz in 0.0..100.0)
    }

    @Test
    fun rangeReplacesZoomLimitsAndClampsScale() {
        val v = viewport()
        v.range(2.0, 4.0)
        assertEquals(2.0, v.unitsPerPx, 1e-9)
        v.zoomAt(100.0)
        assertEquals(4.0, v.unitsPerPx, 1e-9)
    }

    @Test
    fun clampWithoutBoundsKeepsCenter() {
        val v = viewport()
        v.pan(-5000.0, 0.0)
        assertEquals(5000.0, v.cx, 1e-9)
    }

    @Test
    fun slopIgnoresSmallMovesOnly() {
        assertFalse(Slop.exceeded(Slop.PX, -Slop.PX))
        assertTrue(Slop.exceeded(Slop.PX + 1, 0))
        assertTrue(Slop.exceeded(0, -Slop.PX - 1))
    }
}
