package kami.libs.admin

import java.util.concurrent.ConcurrentHashMap

object AdminMetrics {
    private val sources = ConcurrentHashMap<String, () -> Long>()

    fun register(key: String, value: () -> Long) {
        sources[key] = value
    }

    fun snapshot(): Map<String, Long> = sources.toSortedMap().mapValues { runCatching { it.value() }.getOrDefault(0L) }
}
