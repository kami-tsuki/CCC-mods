package kami.libs.ui.graph

import kotlin.test.Test
import kotlin.test.assertEquals

class GraphLodTest {
    private val lod = GraphLod(0.4, 1.0)

    @Test
    fun zoomPicksLevel() {
        assertEquals(Lod.OVERVIEW, lod.at(0.1, Lod.OVERVIEW))
        assertEquals(Lod.MID, lod.at(0.6, Lod.OVERVIEW))
        assertEquals(Lod.DETAIL, lod.at(2.0, Lod.OVERVIEW))
    }

    @Test
    fun hysteresisHoldsTheLevelNearTheThreshold() {
        assertEquals(Lod.OVERVIEW, lod.at(0.41, Lod.OVERVIEW))
        assertEquals(Lod.MID, lod.at(0.39, Lod.MID))
        assertEquals(Lod.OVERVIEW, lod.at(0.3, Lod.MID))
        assertEquals(Lod.MID, lod.at(1.02, Lod.MID))
        assertEquals(Lod.DETAIL, lod.at(0.97, Lod.DETAIL))
        assertEquals(Lod.MID, lod.at(0.9, Lod.DETAIL))
    }

    @Test
    fun eraIsTheLastStartAtOrBelowTheLevel() {
        val starts = intArrayOf(1, 11, 31)
        assertEquals(1, TechLayout.era(1, starts))
        assertEquals(1, TechLayout.era(10, starts))
        assertEquals(11, TechLayout.era(11, starts))
        assertEquals(31, TechLayout.era(400, starts))
        assertEquals(1, TechLayout.era(0, starts))
    }

    @Test
    fun erasBoundTreeWidthComparedToPerLevelBands() {
        val levels = List(40) { it + 1 }
        val starts = intArrayOf(1, 21)
        val wide = List(40) { emptyList<Int>() }
        val spread = TechLayout.place(wide, bandOf = levels).cells.maxOf { it.col }
        val grouped = TechLayout.place(wide, bandOf = levels.map { TechLayout.era(it, starts) }).cells.maxOf { it.col }
        assertEquals(39, spread)
        assertEquals(1, grouped)
    }
}
