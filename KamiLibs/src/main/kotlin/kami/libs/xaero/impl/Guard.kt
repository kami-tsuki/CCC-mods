package kami.libs.xaero.impl

import kami.libs.KamiLibs
import java.lang.reflect.InvocationTargetException

class Guard(private val name: String) {
    @Volatile
    var disabled = false
        private set
    @Volatile
    private var warned = false

    fun run(block: () -> Unit) {
        call(Unit) { block() }
    }

    fun <T> call(fallback: T, block: () -> T): T {
        if (disabled) return fallback
        return try {
            block()
        } catch (e: Exception) {
            report(e)
            fallback
        } catch (e: LinkageError) {
            report(e)
            fallback
        }
    }
    fun report(raw: Throwable) {
        if (disabled) return
        val e = (raw as? InvocationTargetException)?.targetException ?: raw
        if (e is LinkageError || raw !is InvocationTargetException && e is ReflectiveOperationException) {
            disabled = true
            KamiLibs.LOG.warn("Xaero {} bridge disabled: {}", name, e.toString())
        } else warn(e)
    }

    fun <T> soft(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        warn(e)
        fallback
    } catch (e: LinkageError) {
        warn(e)
        fallback
    }

    private fun warn(e: Throwable) {
        if (warned) return
        warned = true
        KamiLibs.LOG.warn("Xaero {} bridge error: {}", name, e.toString(), e)
    }
}
