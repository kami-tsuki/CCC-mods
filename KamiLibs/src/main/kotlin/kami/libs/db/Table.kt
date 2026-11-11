package kami.libs.db

import kami.libs.log.Log

class Table<T>(private val name: String, keys: List<String>, columns: List<String>, private val rows: (T) -> List<List<Any?>>) {
    companion object {
        val ident = Regex("[a-z_][a-z0-9_]*|\"[a-z_][a-z0-9_]*\"")
    }

    private val width = keys.size
    private val insert = "INSERT INTO $name (${(keys + columns).joinToString()}) VALUES (${List(keys.size + columns.size) { "?" }.joinToString()})"
    private val diff = Rows<List<Any?>>(name, keys, columns)

    private val log = Log.of("library")
    private var warned = false

    private fun map(value: T): Map<List<Any?>, List<Any?>> {
        val list = rows(value)
        return list.associateBy { it.take(width) }.also { if (it.size != list.size && !warned) { warned = true; log.warn("Table {} has {} rows with duplicate keys; the last of each key is kept", name, list.size - it.size) } }
    }

    fun insert(session: Session, value: T) = session.batch(insert, rows(value))

    fun prime(value: T) = diff.prime(map(value))

    fun sync(value: T): (Session.() -> Unit)? = diff.diff(map(value))
}
