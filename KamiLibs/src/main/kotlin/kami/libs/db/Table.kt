package kami.libs.db

class Table<T>(name: String, keys: List<String>, columns: List<String>, private val rows: (T) -> List<List<Any?>>) {
    private val width = keys.size
    private val insert = "INSERT INTO $name (${(keys + columns).joinToString()}) VALUES (${List(keys.size + columns.size) { "?" }.joinToString()})"
    private val diff = Rows<List<Any?>>(name, keys, columns)

    private fun map(value: T) = rows(value).associateBy { it.take(width) }

    fun insert(session: Session, value: T) = session.batch(insert, rows(value))

    fun prime(value: T) = diff.prime(map(value))

    fun sync(value: T): (Session.() -> Unit)? = diff.diff(map(value))
}
