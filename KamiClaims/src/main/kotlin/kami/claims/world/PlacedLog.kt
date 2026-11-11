package kami.claims.world

object PlacedLog {
    private const val CAPACITY = 65536
    private const val UNKNOWN = ""

    private class Mark(var placer: String, var seq: Long)

    private val marks = object : LinkedHashMap<Pair<String, Long>, Mark>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Long>, Mark>) = size > CAPACITY
    }
    private var next = 1L

    var rev = 0L
        private set

    fun mark(dim: String, pos: Long) {
        val spot = dim to pos
        marks[spot] = Mark(marks[spot]?.placer ?: UNKNOWN, next++)
        rev++
    }

    fun mark(dim: String, pos: Long, by: String) {
        marks[dim to pos] = Mark(by, next++)
        rev++
    }

    fun takeBy(dim: String, pos: Long, by: String): Boolean {
        val mark = marks[dim to pos]?.takeIf { it.placer == by } ?: return false
        mark.placer = UNKNOWN
        rev++
        return true
    }

    fun contains(dim: String, pos: Long) = marks[dim to pos] != null

    fun remove(dim: String, pos: Long) = (marks.remove(dim to pos) != null).also { if (it) rev++ }

    fun rows(): List<List<Any?>> = marks.map { (spot, mark) -> listOf(spot.first, spot.second, mark.placer, mark.seq) }

    fun restore(dim: String, pos: Long, placer: String, seq: Long) {
        marks[dim to pos] = Mark(placer, seq)
        next = maxOf(next, seq + 1)
        rev++
    }

    fun reset() {
        marks.clear()
        next = 1L
        rev++
    }
}
