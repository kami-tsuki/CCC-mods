package kami.libs.ui.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphModelTest {
    private fun grid(cols: Int, rows: Int, edges: List<Pair<Int, Int>> = emptyList()): GraphModel {
        val n = cols * rows
        return GraphModel(
            DoubleArray(n) { (it % cols) * 100.0 }, DoubleArray(n) { (it / cols) * 50.0 },
            DoubleArray(n) { 40.0 }, DoubleArray(n) { 20.0 },
            IntArray(edges.size) { edges[it].first }, IntArray(edges.size) { edges[it].second }
        )
    }

    @Test
    fun cullingKeepsOnlyNodesInsideWindow() {
        val m = grid(4, 2)
        val out = IntArray(m.size)
        val n = m.visibleNodes(90.0, -5.0, 210.0, 5.0, out)
        assertEquals(listOf(1, 2), out.take(n))
    }

    @Test
    fun cullingIncludesNodesTouchingTheEdge() {
        val m = grid(2, 1)
        val out = IntArray(2)
        assertEquals(2, m.visibleNodes(20.0, -50.0, 80.0, 50.0, out))
        assertEquals(1, m.visibleNodes(-100.0, -50.0, 19.0, 50.0, out))
    }

    @Test
    fun edgeCullingUsesPaddedBox() {
        val m = GraphModel(doubleArrayOf(0.0, 1000.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(40.0, 40.0), doubleArrayOf(20.0, 20.0), intArrayOf(0), intArrayOf(1), edgePad = 30.0)
        val out = IntArray(1)
        assertEquals(1, m.visibleEdges(400.0, -10.0, 600.0, 10.0, out))
        assertEquals(0, m.visibleEdges(400.0, 200.0, 600.0, 300.0, out))
    }

    @Test
    fun explicitEdgeBoxOverridesEndpoints() {
        val m = GraphModel(doubleArrayOf(0.0, 100.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 10.0), doubleArrayOf(10.0, 10.0), intArrayOf(0), intArrayOf(1), edgeBox = doubleArrayOf(0.0, 0.0, 100.0, 500.0))
        assertEquals(1, m.visibleEdges(40.0, 400.0, 60.0, 450.0, IntArray(1)))
    }

    @Test
    fun nearestPicksClosestCentreAmongOverlaps() {
        val m = GraphModel(doubleArrayOf(0.0, 30.0), doubleArrayOf(0.0, 0.0), doubleArrayOf(80.0, 80.0), doubleArrayOf(20.0, 20.0))
        assertEquals(0, m.nearest(10.0, 0.0, 0.0))
        assertEquals(1, m.nearest(20.0, 0.0, 0.0))
        assertEquals(-1, m.nearest(0.0, 40.0, 0.0))
    }

    @Test
    fun slackWidensTheHitArea() {
        val m = grid(1, 1)
        assertEquals(-1, m.nearest(23.0, 0.0, 0.0))
        assertEquals(0, m.nearest(23.0, 0.0, 4.0))
    }

    @Test
    fun neighbourFollowsDirectionAndPrefersAlignment() {
        val m = grid(3, 3)
        assertEquals(5, m.neighbour(4, 1, 0))
        assertEquals(3, m.neighbour(4, -1, 0))
        assertEquals(1, m.neighbour(4, 0, -1))
        assertEquals(7, m.neighbour(4, 0, 1))
        assertEquals(-1, m.neighbour(2, 1, 0))
    }

    @Test
    fun traceMarksAncestorsAndDescendantsOnly() {
        val m = grid(5, 1, listOf(0 to 1, 1 to 2, 1 to 3, 3 to 4))
        val t = m.trace(1)
        assertEquals(listOf(true, true, false, false, false), t.up.toList())
        assertEquals(listOf(false, true, true, true, true), t.down.toList())
        assertTrue(t.lit(0, 1))
        assertTrue(t.lit(3, 4))
        assertFalse(t.lit(0, 2))
    }

    @Test
    fun boundsCoverNodeExtentsAndMargin() {
        val m = GraphModel(doubleArrayOf(0.0, 100.0), doubleArrayOf(0.0, 50.0), doubleArrayOf(40.0, 40.0), doubleArrayOf(20.0, 20.0), margin = 5.0)
        assertEquals(-25.0, m.minX)
        assertEquals(125.0, m.maxX)
        assertEquals(-15.0, m.minY)
        assertEquals(65.0, m.maxY)
    }

    @Test
    fun emptyModelHasZeroBounds() {
        assertEquals(0, GraphModel.EMPTY.size)
        assertEquals(0.0, GraphModel.EMPTY.maxX)
    }
}
