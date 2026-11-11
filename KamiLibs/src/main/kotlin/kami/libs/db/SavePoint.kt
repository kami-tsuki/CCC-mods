package kami.libs.db

import kami.libs.log.Log

object SavePoint {
    private val log = Log.of("library")
    private val stores = LinkedHashMap<String, () -> (Session.() -> Unit)?>()

    fun register(name: String, collect: () -> (Session.() -> Unit)?) {
        synchronized(stores) { stores[name] = collect }
    }

    private const val JOURNAL_MARK = "savepoint_journal"
    private const val STATEMENT_MARK = "savepoint_statement"

    private val stampWatermarks: Session.() -> Unit = {
        exec("INSERT INTO libs_meta (key, value) SELECT ?, CAST(COALESCE(MAX(id), 0) AS TEXT) FROM libs_journal WHERE true ON CONFLICT(key) DO UPDATE SET value = excluded.value", JOURNAL_MARK)
        exec("INSERT INTO libs_meta (key, value) SELECT ?, CAST(COALESCE(MAX(id), 0) AS TEXT) FROM libs_statement WHERE true ON CONFLICT(key) DO UPDATE SET value = excluded.value", STATEMENT_MARK)
    }

    fun run() {
        if (!Db.isOpen) return
        val parts = synchronized(stores) { stores.toList() }.mapNotNull { (name, collect) ->
            runCatching(collect).onFailure {
                Writer.invalidate()
                log.error("Save point could not collect {}; synced tables are rewritten in full next time", name, it)
            }.getOrNull()
        }
        Db.writeAll(parts + stampWatermarks, durable = true)
        Db.checkpoint()
    }

    private fun Session.mark(key: String): Long? = one("SELECT value FROM libs_meta WHERE key = ?", key) { it.long("value") }

    fun discardUnsaved() {
        val (journal, statement) = Db.read { mark(JOURNAL_MARK) to mark(STATEMENT_MARK) }
        Db.durable {
            journal?.let { exec("DELETE FROM libs_journal WHERE id > ?", it) }
            statement?.let { exec("DELETE FROM libs_statement WHERE id > ?", it) }
        }
    }

    fun join(parts: List<(Session.() -> Unit)?>): (Session.() -> Unit)? {
        val list = parts.filterNotNull()
        if (list.isEmpty()) return null
        return { list.forEach { it(this) } }
    }
}
