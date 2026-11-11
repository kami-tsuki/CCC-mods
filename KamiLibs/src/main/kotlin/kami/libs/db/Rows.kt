package kami.libs.db

class Rows<K>(table: String, private val keys: List<String>, columns: List<String>) {
    init {
        (keys + columns + table).forEach { require(Table.ident.matches(it)) { "Bad SQL identifier: $it" } }
    }

    private val width = keys.size + columns.size
    private val upsert = "INSERT INTO $table (${(keys + columns).joinToString()}) VALUES (${List(width) { "?" }.joinToString()}) ON CONFLICT(${keys.joinToString()}) DO " +
        if (columns.isEmpty()) "NOTHING" else "UPDATE SET " + columns.joinToString { "$it = excluded.$it" }
    private val delete = "DELETE FROM $table WHERE " + keys.joinToString(" AND ") { "$it = ?" }
    private val stored = "SELECT ${keys.joinToString()} FROM $table"
    private var last: Map<K, List<Any?>> = emptyMap()
    private var seen = Writer.drops
    private var owned = false

    fun prime(rows: Map<K, List<Any?>>) {
        last = rows.toMap()
        owned = false
        seen = Writer.drops
    }

    fun diff(rows: Map<K, List<Any?>>): (Session.() -> Unit)? {
        require(rows.values.all { it.size == width }) { "rows must hold the key columns followed by the value columns" }
        val epoch = Writer.drops
        val full = epoch != seen
        seen = epoch
        val all = rows.values.toList()
        val changed = if (full) all else rows.filter { (k, v) -> !same(last[k], v) }.values.toList()
        val gone = last.filterKeys { it !in rows }.values.map { it.take(keys.size) }
        last = rows
        owned = false
        if (full) return {
            batch(delete, stale(all))
            batch(upsert, changed)
        }
        if (changed.isEmpty() && gone.isEmpty()) return null
        return {
            batch(delete, gone)
            batch(upsert, changed)
        }
    }

    fun delta(rows: Map<K, List<Any?>>, removed: Collection<K>, full: () -> Map<K, List<Any?>>): (Session.() -> Unit)? {
        if (Writer.drops != seen) return diff(full())
        require(rows.values.all { it.size == width }) { "rows must hold the key columns followed by the value columns" }
        val changed = rows.filter { (k, v) -> !same(last[k], v) }
        val gone = removed.filter { it in last && it !in rows }
        if (changed.isEmpty() && gone.isEmpty()) return null
        val goneKeys = gone.map { last.getValue(it).take(keys.size) }
        if (!owned) {
            last = HashMap(last)
            owned = true
        }
        val own = last as MutableMap<K, List<Any?>>
        gone.forEach { own.remove(it) }
        own.putAll(changed)
        return {
            batch(delete, goneKeys)
            batch(upsert, changed.values.toList())
        }
    }

    fun sync(rows: Map<K, List<Any?>>) {
        diff(rows)?.let(Db::write)
    }

    private fun Session.stale(rows: List<List<Any?>>): List<List<Any?>> {
        val want = rows.mapTo(HashSet()) { r -> r.take(keys.size).map(::norm) }
        return query(stored) { r -> keys.map { r.value(it) } }.filter { k -> k.map(::norm) !in want }
    }

    private fun norm(v: Any?): Any? = when (v) {
        null -> null
        is Boolean -> if (v) 1L else 0L
        is Number -> v.toLong()
        else -> v.toString()
    }

    private fun same(a: List<Any?>?, b: List<Any?>) = a != null && a.size == b.size && a.indices.all {
        val x = a[it]
        val y = b[it]
        if (x is ByteArray && y is ByteArray) x.contentEquals(y) else x == y
    }
}
