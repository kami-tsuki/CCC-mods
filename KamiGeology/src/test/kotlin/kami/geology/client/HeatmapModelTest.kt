package kami.geology.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeatmapModelTest {
    @Test
    fun gridCoversViewAndAlignsToCell() {
        val g = HeatmapModel.grid(1000.0, -500.0, 400.0, 300.0, 4.0, 150_000)
        assertEquals(0, g.x0 % g.cell)
        assertEquals(0, g.z0 % g.cell)
        assertTrue(g.x0 <= 1000 - 800 && g.x0 + g.w * g.cell >= 1000 + 800)
        assertTrue(g.z0 <= -500 - 600 && g.z0 + g.h * g.cell >= -500 + 600)
    }

    @Test
    fun gridCoarsensToStayUnderCellBudget() {
        val fine = HeatmapModel.grid(0.0, 0.0, 800.0, 600.0, 1.0, 150_000)
        val tight = HeatmapModel.grid(0.0, 0.0, 800.0, 600.0, 1.0, 1_000)
        assertTrue(tight.cell > fine.cell)
        assertTrue(tight.w * tight.h < fine.w * fine.h)
    }

    @Test
    fun levelsRespectFloorDominanceAndSwitch() {
        val sum = floatArrayOf(0f, 1f, 1f, 1f)
        val dominant = byteArrayOf(0, 1, 1, 0)
        val on = HeatmapModel.levels(sum, dominant, 1, true)
        assertEquals(0, on[0])
        assertTrue(on[1] > 0)
        assertEquals(0, on[3])
        assertEquals(0, HeatmapModel.levels(sum, dominant, 255, true)[1])
        assertTrue(HeatmapModel.levels(sum, dominant, 1, false).all { it == 0 })
    }

    @Test
    fun edgeDetectsBorderOfFilledArea() {
        val level = IntArray(25) { if (it % 5 in 1..3 && it / 5 in 1..3) 9 else 0 }
        assertTrue(HeatmapModel.edge(level, 1, 1, 5, 5))
        assertFalse(HeatmapModel.edge(level, 2, 2, 5, 5))
    }

    @Test
    fun heatIsClampedToUnitRange() {
        assertEquals(0.0, HeatmapModel.heat(10, 50))
        assertEquals(1.0, HeatmapModel.heat(255, 80))
        assertTrue(HeatmapModel.heat(150, 50) in 0.0..1.0)
    }
}
