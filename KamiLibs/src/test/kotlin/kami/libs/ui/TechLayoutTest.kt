package kami.libs.ui

import kami.libs.ui.graph.Band
import kami.libs.ui.graph.Cell
import kami.libs.ui.graph.TechLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TechLayoutTest {
    @Test
    fun depthIsLongestPathFromRoots() {
        val parents = listOf(emptyList(), listOf(0), listOf(0, 1), listOf(2))
        assertEquals(listOf(0, 1, 2, 3), TechLayout.depths(parents).toList())
    }

    @Test
    fun cyclesDoNotHang() {
        val parents = listOf(listOf(1), listOf(0))
        assertEquals(2, TechLayout.place(parents).cells.size)
    }

    @Test
    fun cellsNeverOverlap() {
        val parents = listOf(emptyList(), emptyList(), listOf(0), listOf(0, 1), listOf(1), listOf(2, 3, 4), listOf(5))
        val cells = TechLayout.place(parents).cells
        assertEquals(cells.size, cells.toSet().size)
        assertEquals(listOf(0, 0, 1, 1, 1, 2, 3), cells.map { it.col })
    }

    @Test
    fun parallelLinesWithBranchesDoNotInterleave() {
        val parents = listOf(emptyList(), emptyList(), listOf(0), listOf(1), listOf(2), listOf(2), listOf(3), listOf(3))
        val cells = TechLayout.place(parents).cells
        val gold = listOf(0, 2, 4, 5).map { cells[it].row }
        val lead = listOf(1, 3, 6, 7).map { cells[it].row }
        assertTrue(gold.max() < lead.min())
        assertEquals(cells[2].row + 1, cells[5].row)
        assertEquals(cells[4].col, cells[5].col)
        assertEquals(cells[0].row, cells[4].row)
    }

    @Test
    fun pinnedCellsAreKeptAndAvoided() {
        val parents = listOf(emptyList(), listOf(0), listOf(0))
        val cells = TechLayout.place(parents, listOf(null, Cell(1, 0), null)).cells
        assertEquals(Cell(1, 0), cells[1])
        assertNotEquals(cells[1], cells[2])
        assertEquals(1, cells[2].col)
    }

    @Test
    fun emptyInput() {
        assertTrue(TechLayout.place(emptyList()).cells.isEmpty())
    }

    @Test
    fun bandsGroupColumnsByBand() {
        val parents = listOf(emptyList(), listOf(0), listOf(1), emptyList(), emptyList())
        val placement = TechLayout.place(parents, bandOf = listOf(1, 2, 3, 1, 2))
        assertEquals(listOf(Band(1, 0, 1), Band(2, 1, 1), Band(3, 2, 1)), placement.bands)
        assertEquals(listOf(0, 1, 2, 0, 1), placement.cells.map { it.col })
    }

    @Test
    fun depthRestartsInsideBand() {
        val parents = listOf(emptyList(), listOf(0), listOf(1), listOf(2))
        assertEquals(listOf(0, 0, 1, 2), TechLayout.depths(parents, listOf(1, 2, 2, 2)).toList())
        val placement = TechLayout.place(parents, bandOf = listOf(1, 2, 2, 2))
        assertEquals(listOf(Band(1, 0, 1), Band(2, 1, 3)), placement.bands)
        assertEquals(listOf(0, 1, 2, 3), placement.cells.map { it.col })
    }

    @Test
    fun loneNodeInBandKeepsRowOfParent() {
        val parents = listOf(emptyList(), emptyList(), listOf(1), listOf(2))
        val cells = TechLayout.place(parents, bandOf = listOf(1, 1, 2, 3)).cells
        assertEquals(cells[1].row, cells[2].row)
        assertEquals(cells[2].row, cells[3].row)
        assertEquals(cells.size, cells.toSet().size)
    }

    @Test
    fun pinsWinOverBands() {
        val cells = TechLayout.place(listOf(emptyList(), listOf(0)), listOf(null, Cell(7, 3)), listOf(1, 2)).cells
        assertEquals(Cell(7, 3), cells[1])
    }

    @Test
    fun chainContinuesStraight() {
        val parents = listOf(emptyList(), listOf(0), listOf(1), listOf(2), listOf(3))
        assertEquals(setOf(0), TechLayout.place(parents).cells.map { it.row }.toSet())
    }

    @Test
    fun linesWithConnectingSecondaryEdgesAreAdjacent() {
        val parents = listOf(emptyList(), emptyList(), emptyList(), listOf(0), listOf(1), listOf(2, 0))
        val cells = TechLayout.place(parents).cells
        assertEquals(cells[0].row + 1, cells[2].row)
        assertEquals(cells[2].row, cells[5].row)
        assertEquals(cells[0].row, cells[3].row)
        assertTrue(cells[1].row > cells[2].row)
        assertEquals(cells[1].row, cells[4].row)
    }
}
