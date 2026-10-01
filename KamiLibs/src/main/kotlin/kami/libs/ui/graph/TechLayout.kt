package kami.libs.ui.graph

import kotlin.math.max

data class Cell(val col: Int, val row: Int)

data class Band(val id: Int, val firstCol: Int, val cols: Int)

class Placement(val cells: List<Cell>, val bands: List<Band>)

object TechLayout {
    fun depths(parents: List<List<Int>>, bandOf: List<Int> = emptyList()): IntArray {
        fun band(node: Int) = bandOf.getOrElse(node) { 0 }
        val depth = IntArray(parents.size) { -1 }
        fun of(node: Int, visiting: Set<Int>): Int {
            if (depth[node] >= 0) return depth[node]
            val above = parents[node].filter { it in parents.indices && it !in visiting && it != node && band(it) == band(node) }
            depth[node] = if (above.isEmpty()) 0 else above.maxOf { of(it, visiting + node) } + 1
            return depth[node]
        }
        parents.indices.forEach { of(it, emptySet()) }
        return depth
    }

    fun primaries(parents: List<List<Int>>, hidden: List<Set<Int>> = emptyList(), groups: List<Int> = emptyList(), bandOf: List<Int> = emptyList()): IntArray {
        val primary = IntArray(parents.size) { -1 }
        val depth = depths(parents, bandOf)
        fun band(node: Int) = bandOf.getOrElse(node) { 0 }
        fun group(node: Int) = groups.getOrElse(node) { 0 }
        fun reaches(from: Int, target: Int): Boolean {
            var at = from
            while (at >= 0) {
                if (at == target) return true
                at = primary[at]
            }
            return false
        }
        parents.indices.forEach { node ->
            val options = parents[node].filter { it in parents.indices && it != node && !reaches(it, node) }
            val hiddenHere = hidden.getOrNull(node).orEmpty()
            val shown = options.filter { it !in hiddenHere }.ifEmpty { options }
            val same = shown.filter { group(it) == group(node) }
            primary[node] = same.firstOrNull() ?: shown.minWithOrNull(compareBy({ -band(it) }, { -depth[it] })) ?: -1
        }
        return primary
    }

    private class Shape(val ids: IntArray, val cols: IntArray, val rel: IntArray)

    private fun fit(shape: Shape, sky: Map<Int, Int>, floor: Int): Int {
        var off = floor
        for (i in shape.ids.indices) sky[shape.cols[i]]?.let { off = max(off, it + 1 - shape.rel[i]) }
        return off
    }

    fun place(
        parents: List<List<Int>>,
        pinned: List<Cell?> = emptyList(),
        bandOf: List<Int> = emptyList(),
        hidden: List<Set<Int>> = emptyList(),
        groups: List<Int> = emptyList()
    ): Placement {
        val n = parents.size
        val depth = depths(parents, bandOf)
        val bandIds = parents.indices.map { bandOf.getOrElse(it) { 0 } }
        val bands = ArrayList<Band>()
        val firstCol = HashMap<Int, Int>()
        var cursor = 0
        bandIds.distinct().sorted().forEach { id ->
            val cols = parents.indices.filter { bandIds[it] == id }.maxOf { depth[it] } + 1
            bands += Band(id, cursor, cols)
            firstCol[id] = cursor
            cursor += cols
        }
        val primary = primaries(parents, hidden, groups, bandOf)
        val colOf = IntArray(n) { firstCol.getValue(bandIds[it]) + depth[it] }
        val kids = List(n) { mutableListOf<Int>() }
        primary.forEachIndexed { child, parent -> if (parent >= 0) kids[parent] += child }
        val roots = parents.indices.filter { primary[it] < 0 }
        val members = arrayOfNulls<List<Int>>(n)
        fun collect(node: Int): List<Int> = members[node] ?: (listOf(node) + kids[node].flatMap { collect(it) }).also { members[node] = it }
        val stamp = IntArray(n)
        var tick = 0
        fun group(node: Int) = groups.getOrElse(node) { 0 }
        fun key(child: Int, prev: IntArray): Double {
            tick++
            val own = collect(child)
            own.forEach { stamp[it] = tick }
            var sum = 0.0
            var count = 0
            for (m in own) for (p in parents[m]) {
                if (p !in 0 until n || p == primary[m] || stamp[p] == tick || p in hidden.getOrNull(m).orEmpty()) continue
                sum += prev[p]
                count++
            }
            return if (count == 0) prev[child].toDouble() else sum / count
        }
        fun ordered(list: List<Int>, prev: IntArray?): List<Int> {
            if (prev == null || list.size < 2) return list.sortedWith(compareBy({ group(it) }, { it }))
            val keys = HashMap<Int, Double>()
            list.forEach { keys[it] = key(it, prev) }
            return list.sortedWith(compareBy({ keys.getValue(it) }, { group(it) }, { it }))
        }
        fun run(prev: IntArray?): IntArray {
            val rows = IntArray(n)
            fun shape(node: Int): Shape {
                val ids = arrayListOf(node)
                val cols = arrayListOf(colOf[node])
                val rel = arrayListOf(0)
                val sky = HashMap<Int, Int>()
                sky[colOf[node]] = 0
                ordered(kids[node], prev).forEachIndexed { k, child ->
                    val sub = shape(child)
                    val off = fit(sub, sky, if (k == 0) 0 else 1)
                    for (i in sub.ids.indices) {
                        val row = sub.rel[i] + off
                        ids.add(sub.ids[i])
                        cols.add(sub.cols[i])
                        rel.add(row)
                        sky[sub.cols[i]] = max(sky[sub.cols[i]] ?: row, row)
                    }
                }
                return Shape(ids.toIntArray(), cols.toIntArray(), rel.toIntArray())
            }
            val sky = HashMap<Int, Int>()
            ordered(roots, prev).forEach { root ->
                val sub = shape(root)
                val off = fit(sub, sky, 0)
                for (i in sub.ids.indices) {
                    val row = sub.rel[i] + off
                    rows[sub.ids[i]] = row
                    sky[sub.cols[i]] = max(sky[sub.cols[i]] ?: row, row)
                }
            }
            return rows
        }
        var rows = run(null)
        repeat(2) { rows = run(rows) }
        val taken = HashSet<Cell>()
        val cells = arrayOfNulls<Cell>(n)
        pinned.forEachIndexed { node, cell -> if (cell != null && node < n) { cells[node] = cell; taken += cell } }
        parents.indices.filter { cells[it] == null }.sortedWith(compareBy({ colOf[it] }, { rows[it] })).forEach { node ->
            var row = rows[node]
            while (Cell(colOf[node], row) in taken) row++
            cells[node] = Cell(colOf[node], row).also { taken += it }
        }
        return Placement(cells.map { it ?: Cell(0, 0) }, bands)
    }
}
