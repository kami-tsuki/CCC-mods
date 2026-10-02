package kami.libs.util

class RecentSet<T>(private val capacity: Int) {
    private val entries = object : LinkedHashMap<T, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<T, Unit>) = size > capacity
    }

    fun add(item: T) {
        entries[item] = Unit
    }

    fun remove(item: T) = entries.remove(item) != null

    fun clear() = entries.clear()
}
