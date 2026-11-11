package kami.libs.ui.graph

enum class Lod { OVERVIEW, MID, DETAIL }

private const val HYSTERESIS = 1.08

class GraphLod(private val mid: Double, val detail: Double) {
    fun at(zoom: Double, current: Lod): Lod {
        val midAt = if (current == Lod.OVERVIEW) mid * HYSTERESIS else mid / HYSTERESIS
        val detailAt = if (current == Lod.DETAIL) detail / HYSTERESIS else detail * HYSTERESIS
        return if (zoom >= detailAt) Lod.DETAIL else if (zoom >= midAt) Lod.MID else Lod.OVERVIEW
    }
}
