package kami.libs.db

import kami.libs.log.Log
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

private val log = Log.of("library")

class Session internal constructor(private val connection: Connection) {
    fun exec(sql: String, vararg params: Any?) {
        prepared(sql, params.asList()) { it.execute() }
    }

    fun <T> query(sql: String, vararg params: Any?, row: (Row) -> T): List<T> = prepared(sql, params.asList()) { s ->
        s.executeQuery().use { rs ->
            val r = Row(rs)
            buildList { while (rs.next()) add(row(r)) }
        }
    }

    fun <T> one(sql: String, vararg params: Any?, row: (Row) -> T): T? = query(sql, *params, row = row).firstOrNull()

    fun batch(sql: String, rows: List<List<Any?>>) {
        if (rows.isEmpty()) return
        connection.prepareStatement(sql).use { s ->
            rows.forEach { bind(s, it); s.addBatch() }
            s.executeBatch()
        }
    }

    private fun <R> prepared(sql: String, params: List<Any?>, run: (PreparedStatement) -> R): R =
        connection.prepareStatement(sql).use { bind(it, params); run(it) }

    private fun bind(s: PreparedStatement, values: List<Any?>) = values.forEachIndexed { i, v ->
        val bad = v is Double && !v.isFinite() || v is Float && !v.isFinite()
        if (bad) log.warn("Non-finite number bound as 0 in {}", s)
        s.setObject(i + 1, if (bad) 0.0 else v)
    }
}

class Row internal constructor(private val rs: ResultSet) {
    fun string(col: String): String = need(col, stringOrNull(col))
    fun long(col: String): Long = need(col, longOrNull(col))
    fun int(col: String): Int = need(col, intOrNull(col))
    fun double(col: String): Double = need(col, doubleOrNull(col))
    fun bool(col: String): Boolean = need(col, boolOrNull(col))

    fun stringOrNull(col: String): String? = rs.getString(col)
    fun longOrNull(col: String): Long? = rs.getLong(col).takeUnless { rs.wasNull() }
    fun intOrNull(col: String): Int? = rs.getInt(col).takeUnless { rs.wasNull() }
    fun doubleOrNull(col: String): Double? = rs.getDouble(col).takeUnless { rs.wasNull() }
    fun boolOrNull(col: String): Boolean? = rs.getBoolean(col).takeUnless { rs.wasNull() }

    internal fun value(col: String): Any? = rs.getObject(col)

    private fun <T> need(col: String, value: T?): T = value ?: throw IllegalStateException("column $col is null")
}
