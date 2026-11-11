package kami.claims.client.map

import kami.claims.client.ClientClaims
import kami.claims.client.store.ClaimsStore
import kami.claims.gov.SectorRules
import kami.claims.net.Info
import kami.claims.net.JobSpecLine
import kami.claims.net.SectorLine
import kami.claims.war.ChunkRef

object SectorShapes {
    class Shape(val line: SectorLine, val cells: Set<Long>, val edges: IntArray, val inset: Int, val anchor: Long)

    private var revision = -1
    private var cachedDim = ""
    private var shapes = emptyList<Shape>()

    fun of(dim: String): List<Shape> {
        if (revision != ClaimsStore.revision || cachedDim != dim) {
            revision = ClaimsStore.revision
            cachedDim = dim
            shapes = build(dim)
        }
        return shapes
    }

    fun at(dim: String, x: Int, z: Int): List<Shape> {
        val key = ClientClaims.key(x, z)
        return of(dim).filter { key in it.cells }
    }

    fun covering(info: Info, type: String, sectors: List<Shape>): List<JobSpecLine> = info.jobSpecs.filter { spec ->
        spec.active && when (spec.scope) {
            "country" -> type in SectorRules.workTypes
            "type" -> spec.scopeRef == type
            "sector" -> sectors.any { it.line.id == spec.scopeRef && !it.line.fragmented }
            else -> false
        }
    }

    private fun build(dim: String): List<Shape> {
        val lines = ClaimsStore.info?.sectors.orEmpty()
        val cellSets = lines.map { cellsOf(it, dim) }
        return lines.indices.mapNotNull { i ->
            val cells = cellSets[i].takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val inset = (0 until i).count { j -> cellSets[j].any { it in cells } }
            Shape(lines[i], cells, edges(cells), inset, anchor(cells))
        }
    }

    private fun cellsOf(line: SectorLine, dim: String): Set<Long> = line.chunks.mapNotNullTo(HashSet()) { wire ->
        ChunkRef.parse(wire)?.takeIf { it.dim == dim }?.let { ClientClaims.key(it.x, it.z) }
    }

    private fun edges(cells: Set<Long>): IntArray {
        val out = ArrayList<Int>()
        cells.forEach { k ->
            val (x, z) = ChunkCells.cell(k)
            ChunkCells.SIDES.forEachIndexed { side, (dx, dz) ->
                if (ClientClaims.key(x + dx, z + dz) !in cells) { out += x; out += z; out += side }
            }
        }
        return out.toIntArray()
    }

    private fun anchor(cells: Set<Long>): Long {
        val points = cells.map(ChunkCells::cell)
        val mx = points.sumOf { it.first }.toDouble() / points.size
        val mz = points.sumOf { it.second }.toDouble() / points.size
        return cells.minBy { k -> ChunkCells.cell(k).let { (x, z) -> (x - mx) * (x - mx) + (z - mz) * (z - mz) } }
    }
}
