package kami.geology.client

import kami.geology.map.Heatmap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal object HeatmapModel {
    private const val MAX_CELL = 256
    private const val MARGIN_CELLS = 2
    private const val EXTRA_CELLS = 5

    class Grid(val x0: Int, val z0: Int, val cell: Int, val w: Int, val h: Int)

    fun grid(cx: Double, cz: Double, viewW: Double, viewH: Double, unitsPerPx: Double, maxCells: Int): Grid {
        var cell = 2
        while (cell < unitsPerPx) cell *= 2
        while (cell < MAX_CELL && (viewW * unitsPerPx / cell + EXTRA_CELLS) * (viewH * unitsPerPx / cell + EXTRA_CELLS) > maxCells) cell *= 2
        val halfW = viewW * unitsPerPx / 2 + cell * MARGIN_CELLS
        val halfH = viewH * unitsPerPx / 2 + cell * MARGIN_CELLS
        val x0 = Math.floorDiv(floor(cx - halfW).toInt(), cell) * cell
        val z0 = Math.floorDiv(floor(cz - halfH).toInt(), cell) * cell
        return Grid(x0, z0, cell, (ceil(cx + halfW).toInt() - x0) / cell + 1, (ceil(cz + halfH).toInt() - z0) / cell + 1)
    }

    fun levels(sum: FloatArray, dominant: ByteArray, floor: Int, enabled: Boolean): IntArray {
        val level = IntArray(sum.size)
        if (!enabled) return level
        for (k in sum.indices) if (dominant[k].toInt() != 0) {
            val v = Heatmap.encode(sum[k] * 256.0)
            if (v >= floor) level[k] = v
        }
        return level
    }

    fun edge(level: IntArray, i: Int, j: Int, w: Int, h: Int): Boolean {
        val k = j * w + i
        return i == 0 || level[k - 1] == 0 || i == w - 1 || level[k + 1] == 0 || j == 0 || level[k - w] == 0 || j == h - 1 || level[k + w] == 0
    }

    fun heat(level: Int, floor: Int): Double = ((level - floor) / max(1, 255 - floor).toDouble()).coerceIn(0.0, 1.0)
}
