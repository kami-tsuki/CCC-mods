package kami.libs.db

import kami.libs.log.Log
import java.sql.Connection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class Writer(private val connection: Connection) {
    private class Job(val block: Session.() -> Unit, val durable: Boolean, val done: CompletableFuture<Unit>?)

    private val log = Log.of("library")
    private val session = Session(connection)
    private val queue = ArrayDeque<Job>()
    private var closed = false
    private val thread = Executors.newSingleThreadScheduledExecutor { Thread(it, THREAD).apply { isDaemon = true } }

    init {
        thread.scheduleWithFixedDelay(::commit, 1, 1, TimeUnit.SECONDS)
    }

    val busy: Boolean get() = synchronized(queue) { queue.isNotEmpty() }

    fun submit(block: Session.() -> Unit, durable: Boolean = false, wait: Boolean = durable) = submitAll(listOf(block), durable, wait)

    fun submitAll(blocks: List<Session.() -> Unit>, durable: Boolean = false, wait: Boolean = durable) {
        if (blocks.isEmpty()) return
        check(!wait || Thread.currentThread().name != THREAD) { "cannot wait for a database write from the writer thread" }
        val done = if (wait) CompletableFuture<Unit>() else null
        synchronized(queue) {
            check(!closed) { "database is closed" }
            blocks.forEachIndexed { i, block -> queue.addLast(Job(block, durable, done.takeIf { i == blocks.lastIndex })) }
            if (done != null || queue.size >= BATCH) thread.execute(::commit)
        }
        try {
            done?.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
    }

    fun checkpoint() {
        synchronized(queue) {
            if (closed) return
            thread.execute {
                commit()
                runCatching { session.exec("PRAGMA wal_checkpoint(PASSIVE)") }.onFailure { log.warn("Database checkpoint failed: {}", it.toString()) }
            }
        }
    }

    private fun commit() {
        val batch = synchronized(queue) { queue.toList() }
        if (batch.isEmpty()) return
        val errors = run(batch)?.let { e ->
            log.warn("Database commit of {} queued writes failed, retrying them one by one: {}", batch.size, e.toString())
            batch.map { run(listOf(it)) }
        } ?: List(batch.size) { null }
        synchronized(queue) { repeat(batch.size) { queue.removeFirst() } }
        batch.zip(errors).forEach { (job, e) ->
            if (e == null) job.done?.complete(Unit)
            else {
                invalidate()
                log.error("Dropped a database write that also failed on its own; synced tables are rewritten in full on their next sync", e)
                job.done?.completeExceptionally(e)
            }
        }
    }

    private fun run(jobs: List<Job>): Throwable? {
        val full = jobs.any { it.durable }
        return try {
            if (full) session.exec("PRAGMA synchronous=FULL")
            session.exec("BEGIN IMMEDIATE")
            jobs.forEach { it.block(session) }
            session.exec("COMMIT")
            null
        } catch (e: Throwable) {
            runCatching { session.exec("ROLLBACK") }
            e
        } finally {
            if (full) runCatching { session.exec("PRAGMA synchronous=NORMAL") }
        }
    }

    fun close() {
        synchronized(queue) { closed = true }
        thread.shutdown()
        if (!thread.awaitTermination(1, TimeUnit.MINUTES)) {
            log.error("Database writer did not stop within a minute; {} queued writes are left uncommitted", synchronized(queue) { queue.size })
            return
        }
        commit()
        runCatching { session.exec("PRAGMA wal_checkpoint(TRUNCATE)") }.onFailure { log.warn("Database checkpoint failed: {}", it.toString()) }
        connection.close()
    }

    companion object {
        private const val BATCH = 500
        private const val THREAD = "kami-db"
        private val epoch = AtomicLong()

        val drops: Long get() = epoch.get()

        fun invalidate() {
            epoch.incrementAndGet()
        }
    }
}
