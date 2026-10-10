package kami.claims.client.compat.xaero

import kami.claims.service.View
import kami.libs.xaero.LineSource
import kami.libs.xaero.MapLabel
import kami.libs.xaero.MapLine

object BorderRuns {
    private data class Key(val horizontal: Boolean, val at: Int, val color: Int)

    fun lines(entries: Collection<View.Entry>, at: (Int, Int) -> View.Entry?, color: (View.Entry) -> Int): List<MapLine> {
        val runs = HashMap<Key, ArrayList<Int>>()
        fun edge(e: View.Entry, n: View.Entry?, key: Key, along: Int) {
            if (n == null || (n.country != e.country && e.country < n.country)) runs.getOrPut(key) { ArrayList() } += along
        }
        for (e in entries) {
            val c = argb(color(e), 0xFF)
            edge(e, at(e.x, e.z - 1), Key(true, e.z * 16, c), e.x)
            edge(e, at(e.x, e.z + 1), Key(true, e.z * 16 + 16, c), e.x)
            edge(e, at(e.x - 1, e.z), Key(false, e.x * 16, c), e.z)
            edge(e, at(e.x + 1, e.z), Key(false, e.x * 16 + 16, c), e.z)
        }
        val out = ArrayList<MapLine>()
        for ((key, starts) in runs) {
            starts.sort()
            var from = starts[0]
            var prev = from
            fun flush() {
                if (key.horizontal) out += MapLine(from * 16, key.at, prev * 16 + 16, key.at, key.color)
                else out += MapLine(key.at, from * 16, key.at, prev * 16 + 16, key.color)
            }
            for (i in 1 until starts.size) {
                if (starts[i] == prev) continue
                if (starts[i] != prev + 1) { flush(); from = starts[i] }
                prev = starts[i]
            }
            flush()
        }
        return out
    }

    fun labels(entries: Collection<View.Entry>, name: (Int) -> String?, color: (View.Entry) -> Int): List<MapLabel> {
        class Sum(var x: Long = 0, var z: Long = 0, var n: Int = 0, val color: Int = 0)
        val sums = HashMap<Int, Sum>()
        for (e in entries) sums.getOrPut(e.country) { Sum(color = color(e)) }.apply { x += e.x; z += e.z; n++ }
        return sums.mapNotNull { (country, s) ->
            name(country)?.let { MapLabel(it, (s.x / s.n).toInt() * 16 + 8, (s.z / s.n).toInt() * 16 + 8, argb(s.color, 0xFF)) }
        }
    }
}

class ClaimBorders(private val data: ClaimData, private val style: ClaimStyle) : LineSource {
    override val id = "claims.borders"
    override val toggle = "claims.plus"

    private class Built(val rev: Int, val mode: String, val dim: String, val lines: List<MapLine>, val labels: List<MapLabel>)

    @Volatile
    private var built: Built? = null

    private fun color(e: View.Entry) = style.base(data, e)

    private fun get(dim: String): Built {
        val rev = data.rev
        val mode = style.mode()
        built?.takeIf { it.rev == rev && it.mode == mode && it.dim == dim }?.let { return it }
        val view = data.view(dim)
        val entries = view?.values.orEmpty().filter { shown(it) && !foreign(it) }
        val names = HashMap<Int, String>()
        entries.forEach { e -> data.country(e)?.let { names.putIfAbsent(e.country, it.name) } }
        val b = Built(rev, mode, dim, BorderRuns.lines(entries, { x, z -> view?.get(chunkKey(x, z))?.takeIf { shown(it) && !foreign(it) } }, ::color), BorderRuns.labels(entries, names::get, ::color))
        built = b
        return b
    }

    override fun lines(dim: String): List<MapLine> = if (data.enabled) get(dim).lines else emptyList()

    override fun labels(dim: String): List<MapLabel> = if (data.enabled) get(dim).labels else emptyList()
}
