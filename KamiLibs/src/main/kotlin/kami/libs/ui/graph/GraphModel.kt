package kami.libs.ui.graph

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Trace(val up: BooleanArray, val down: BooleanArray) {
    fun lit(parent: Int, child: Int) = (up[parent] && up[child]) || (down[parent] && down[child])
}

class GraphModel(
    val cx: DoubleArray,
    val cy: DoubleArray,
    val w: DoubleArray,
    val h: DoubleArray,
    val from: IntArray = IntArray(0),
    val to: IntArray = IntArray(0),
    edgeBox: DoubleArray? = null,
    edgePad: Double = 0.0,
    margin: Double = 0.0
) {
    val size get() = cx.size
    val edgeCount get() = from.size
    val minX = (cx.indices.minOfOrNull { cx[it] - w[it] / 2 } ?: 0.0) - margin
    val minY = (cx.indices.minOfOrNull { cy[it] - h[it] / 2 } ?: 0.0) - margin
    val maxX = (cx.indices.maxOfOrNull { cx[it] + w[it] / 2 } ?: 0.0) + margin
    val maxY = (cx.indices.maxOfOrNull { cy[it] + h[it] / 2 } ?: 0.0) + margin
    private val parents: Array<IntArray>
    private val children: Array<IntArray>
    private val box = edgeBox ?: DoubleArray(from.size * 4).also { b ->
        for (e in from.indices) {
            val a = from[e]
            val c = to[e]
            b[4 * e] = min(cx[a] - w[a] / 2, cx[c] - w[c] / 2) - edgePad
            b[4 * e + 1] = min(cy[a] - h[a] / 2, cy[c] - h[c] / 2) - edgePad
            b[4 * e + 2] = max(cx[a] + w[a] / 2, cx[c] + w[c] / 2) + edgePad
            b[4 * e + 3] = max(cy[a] + h[a] / 2, cy[c] + h[c] / 2) + edgePad
        }
    }

    init {
        val up = List(size) { ArrayList<Int>() }
        val down = List(size) { ArrayList<Int>() }
        for (e in from.indices) { up[to[e]] += from[e]; down[from[e]] += to[e] }
        parents = Array(size) { up[it].toIntArray() }
        children = Array(size) { down[it].toIntArray() }
    }

    fun visibleNodes(x0: Double, y0: Double, x1: Double, y1: Double, out: IntArray): Int {
        var n = 0
        for (i in 0 until size) {
            if (cx[i] + w[i] / 2 >= x0 && cx[i] - w[i] / 2 <= x1 && cy[i] + h[i] / 2 >= y0 && cy[i] - h[i] / 2 <= y1) out[n++] = i
        }
        return n
    }

    fun visibleEdges(x0: Double, y0: Double, x1: Double, y1: Double, out: IntArray): Int {
        var n = 0
        for (e in from.indices) if (box[4 * e + 2] >= x0 && box[4 * e] <= x1 && box[4 * e + 3] >= y0 && box[4 * e + 1] <= y1) out[n++] = e
        return n
    }

    fun nearest(x: Double, y: Double, slack: Double): Int {
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in 0 until size) {
            val dx = x - cx[i]
            val dy = y - cy[i]
            if (abs(dx) > w[i] / 2 + slack || abs(dy) > h[i] / 2 + slack) continue
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun neighbour(origin: Int, dx: Int, dy: Int): Int {
        var best = -1
        var bestScore = Double.MAX_VALUE
        for (i in 0 until size) {
            if (i == origin) continue
            val ddx = cx[i] - cx[origin]
            val ddy = cy[i] - cy[origin]
            val along = ddx * dx + ddy * dy
            if (along <= 0.0) continue
            val score = along + 2 * (if (dx != 0) abs(ddy) else abs(ddx))
            if (score < bestScore) { bestScore = score; best = i }
        }
        return best
    }

    fun trace(focus: Int) = Trace(reach(focus, parents), reach(focus, children))

    private fun reach(start: Int, next: Array<IntArray>): BooleanArray {
        val seen = BooleanArray(size)
        val pending = ArrayDeque<Int>()
        seen[start] = true
        pending.add(start)
        while (pending.isNotEmpty()) next[pending.removeLast()].forEach { if (!seen[it]) { seen[it] = true; pending.add(it) } }
        return seen
    }

    companion object {
        val EMPTY = GraphModel(DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))
    }
}
