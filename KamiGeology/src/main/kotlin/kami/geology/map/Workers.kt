package kami.geology.map

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object Workers {
    private val counter = AtomicInteger()

    @Volatile
    private var current: ExecutorService? = null

    val pool: ExecutorService
        get() = live() ?: synchronized(this) { live() ?: create().also { current = it } }

    private fun live() = current?.takeUnless { it.isShutdown }

    private fun create(): ExecutorService = Executors.newFixedThreadPool((Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)) { task ->
        Thread(task, "kami-geology-map-${counter.incrementAndGet()}").also {
            it.isDaemon = true
            it.priority = Thread.NORM_PRIORITY - 1
        }
    }

    fun shutdown() {
        synchronized(this) {
            current?.shutdownNow()
            current = null
        }
    }
}
