package kami.libs.xaero.impl

import kami.libs.KamiLibs
import java.lang.reflect.InvocationTargetException

class Guard(private val name: String) {
    @Volatile
    var disabled = false
        private set
    private var warned = false

    fun run(block: () -> Unit) {
        call(Unit) { block() }
    }

    fun <T> call(fallback: T, block: () -> T): T {
        if (disabled) return fallback
        return try {
            block()
        } catch (e: Exception) {
            fail(e)
            fallback
        } catch (e: LinkageError) {
            fail(e)
            fallback
        }
    }

    private fun fail(raw: Throwable) {
        val e = (raw as? InvocationTargetException)?.targetException ?: raw
        if (e is LinkageError || raw !is InvocationTargetException && e is ReflectiveOperationException) {
            disabled = true
            KamiLibs.LOG.warn("Xaero {} bridge disabled: {}", name, e.toString())
        } else if (!warned) {
            warned = true
            KamiLibs.LOG.warn("Xaero {} bridge error: {}", name, e.toString(), e)
        }
    }
}
