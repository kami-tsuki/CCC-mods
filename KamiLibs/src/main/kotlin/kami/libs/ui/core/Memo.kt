package kami.libs.ui.core

class Memo {
    private var inputs: Array<out Any?>? = null
    private var value: Any? = null
    private var seen = epoch

    @Suppress("UNCHECKED_CAST")
    fun <T> of(vararg now: Any?, build: () -> T): T {
        val last = inputs
        if (seen != epoch || last == null || last.size != now.size || now.indices.any { !same(last[it], now[it]) }) {
            seen = epoch
            inputs = now
            value = build()
        }
        return value as T
    }

    companion object {
        @Volatile
        private var epoch = 0

        fun invalidate() { epoch++ }

        private fun same(a: Any?, b: Any?): Boolean = when {
            a === b -> true
            a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
            else -> a == b
        }
    }
}
