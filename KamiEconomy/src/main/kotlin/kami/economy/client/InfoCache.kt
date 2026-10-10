package kami.economy.client

class InfoCache<V : Any>(private val ttl: Long, private val timeout: Long, private val max: Int = 256) {
    private val values = HashMap<String, Pair<V, Long>>()
    private val pending = HashMap<String, Long>()

    fun get(key: String): V? = values[key]?.first

    fun request(key: String, now: Long): Boolean {
        val stamp = values[key]?.second
        if (stamp != null && now - stamp < ttl) return false
        if (pending[key]?.let { now - it < timeout } == true) return false
        if (pending.size >= PENDING_MAX) pending.values.removeIf { now - it >= timeout }
        pending[key] = now
        return true
    }

    fun put(key: String, value: V, now: Long) {
        if (values.size >= max) values.clear()
        values[key] = value to now
        pending.remove(key)
    }

    fun clear() {
        values.clear()
        pending.clear()
    }

    private companion object {
        const val PENDING_MAX = 32
    }
}
