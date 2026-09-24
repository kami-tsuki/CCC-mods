package kami.geology.map

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object Workers {
    private val counter = AtomicInteger()

    val pool: ExecutorService = Executors.newFixedThreadPool((Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)) { task ->
        Thread(task, "kami-geology-map-${counter.incrementAndGet()}").also {
            it.isDaemon = true
            it.priority = Thread.NORM_PRIORITY - 1
        }
    }
}
