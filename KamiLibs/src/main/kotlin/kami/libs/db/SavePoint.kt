package kami.libs.db

import kami.libs.log.Log

object SavePoint {
    private val log = Log.of("library")
    private val stores = LinkedHashMap<String, () -> (Session.() -> Unit)?>()

    fun register(name: String, collect: () -> (Session.() -> Unit)?) {
        synchronized(stores) { stores[name] = collect }
    }

    fun run() {
        if (!Db.isOpen) return
        val parts = synchronized(stores) { stores.toList() }.mapNotNull { (name, collect) ->
            runCatching(collect).onFailure {
                Writer.invalidate()
                log.error("Save point could not collect {}; synced tables are rewritten in full next time", name, it)
            }.getOrNull()
        }
        Db.writeAll(parts, durable = true)
        Db.checkpoint()
    }

    fun join(parts: List<(Session.() -> Unit)?>): (Session.() -> Unit)? {
        val list = parts.filterNotNull()
        if (list.isEmpty()) return null
        return { list.forEach { it(this) } }
    }
}
