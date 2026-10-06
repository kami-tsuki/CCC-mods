package kami.libs.admin

import kami.libs.db.Db
import kami.libs.db.Row
import kotlinx.serialization.Serializable

@Serializable
class Page<T>(val rows: List<T>, val total: Long, val page: Int, val size: Int) {
    companion object {
        const val SIZE = 50

        fun <T> of(rows: List<T>, page: Int, size: Int = SIZE): Page<T> {
            val p = page.coerceAtLeast(0)
            return Page(rows.drop(minOf(p.toLong() * size, rows.size.toLong()).toInt()).take(size), rows.size.toLong(), p, size)
        }
    }
}

internal class Where {
    private val clauses = ArrayList<String>()
    private val params = ArrayList<Any?>()

    fun add(clause: String, vararg values: Any?) {
        clauses += clause
        params.addAll(values)
    }

    fun <T> page(table: String, columns: String, order: String, page: Int, size: Int, row: (Row) -> T): Page<T> {
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        val n = size.coerceIn(1, 100)
        val p = page.coerceAtLeast(0)
        val count = "SELECT COUNT(*) AS n FROM $table$where"
        val select = "SELECT $columns FROM $table$where ORDER BY $order LIMIT ? OFFSET ?"
        return Db.read(fresh = false) {
            val total = one(count, *params.toTypedArray()) { it.long("n") } ?: 0L
            Page(query(select, *params.toTypedArray(), n, p.toLong() * n, row = row), total, p, n)
        }
    }

    companion object {
        fun like(s: String) = s.replace("!", "!!").replace("%", "!%").replace("_", "!_")
    }
}
