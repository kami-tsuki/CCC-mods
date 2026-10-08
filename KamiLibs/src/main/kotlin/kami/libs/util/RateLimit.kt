package kami.libs.util

class RateLimit<K>(val burst: Int, val windowMs: Long) {
    private val hits = HashMap<K, ArrayDeque<Long>>()
    private var lastPrune = 0L

    @Synchronized
    fun allow(key: K, now: Long = System.currentTimeMillis()): Boolean {
        if (now - lastPrune > windowMs || hits.size > MAX_KEYS) prune(now)
        val q = hits.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
        if (q.size >= burst) return false
        q.addLast(now)
        return true
    }

    @Synchronized
    fun forget(key: K) {
        hits.remove(key)
    }

    private fun prune(now: Long) {
        lastPrune = now
        hits.values.removeIf { q -> q.isEmpty() || now - q.last() >= windowMs }
        if (hits.size > MAX_KEYS) hits.clear()
    }

    private companion object {
        const val MAX_KEYS = 4096
    }
}
