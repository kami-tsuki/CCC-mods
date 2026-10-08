package kami.libs.db

import kami.libs.log.Log
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

internal class Writer(private val connection: Connection) {
    private class Group(val blocks: List<Session.() -> Unit>, val durable: Boolean, val done: CompletableFuture<Unit>?)

    private val session = Session(connection)
    private val queue = ArrayDeque<Group>()
    private var queued = 0
    private val gate = ReentrantLock()
    private var closed = false
    private var crowded = false
    private var stalled = false
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
            queue.addLast(Group(blocks, durable, done))
            queued += blocks.size
            if (queued > HIGH_WATER && !crowded) {
                crowded = true
                log.warn("The database write queue holds {} writes; the disk is not keeping up", queued)
            }
            if (done != null || durable || queued >= BATCH) thread.execute(::commit)
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
        gate.lock()
        try {
            drain()
        } catch (e: Throwable) {
            log.error("Database commit failed; the queued writes stay queued and are tried again", e)
        } finally {
            gate.unlock()
        }
    }

    private fun drain() {
        while (true) {
            val slice = synchronized(queue) { slice() }
            if (slice.isEmpty() || !settle(slice)) return
        }
    }

    private fun slice(): List<Group> {
        val out = ArrayList<Group>()
        var n = 0
        for (g in queue) {
            if (out.isNotEmpty() && n + g.blocks.size > BATCH) break
            out += g
            n += g.blocks.size
            if (n >= BATCH) break
        }
        return out
    }

    private fun settle(slice: List<Group>): Boolean {
        val failed = run(slice)
        if (failed == null) {
            slice.forEach { finish(it, null) }
            return true
        }
        if (isBusy(failed)) return stall(failed)
        log.warn("Database commit of {} queued write groups failed, retrying them one by one: {}", slice.size, failed.toString())
        for (g in slice) {
            val e = run(listOf(g))
            if (e != null && isBusy(e)) return stall(e)
            finish(g, e)
        }
        return true
    }

    private fun stall(e: Throwable): Boolean {
        failure = e.toString()
        if (!stalled) {
            stalled = true
            log.warn("The database is busy; queued writes stay queued and are tried again: {}", e.toString())
        }
        return false
    }

    private fun finish(group: Group, error: Throwable?) {
        synchronized(queue) {
            queue.removeFirst()
            queued -= group.blocks.size
            if (queued < BATCH) crowded = false
        }
        if (error == null) {
            if (stalled) {
                stalled = false
                log.info("Database writes succeed again")
            }
            group.done?.complete(Unit)
            return
        }
        invalidate()
        drop(error, group.blocks.size)
        group.done?.completeExceptionally(error)
    }

    private fun isBusy(e: Throwable) = e is SQLException && (e.errorCode and 0xFF).let { it == SQLITE_BUSY || it == SQLITE_LOCKED }

    private fun run(groups: List<Group>): Throwable? {
        val full = groups.any { it.durable }
        return try {
            if (full) session.exec("PRAGMA synchronous=FULL")
            session.exec("BEGIN IMMEDIATE")
            groups.forEach { g -> g.blocks.forEach { it(session) } }
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
            log.error("Database writer did not stop within a minute; committing the {} remaining queued writes now", synchronized(queue) { queued })
            thread.shutdownNow()
        }
        if (!gate.tryLock(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.error("Database writer is still committing after {} seconds; skipping the final commit", GATE_TIMEOUT_SECONDS)
            return
        }
        try {
            repeat(BUSY_RETRIES) { attempt ->
                if (attempt > 0 && busy) Thread.sleep(BACKOFF_MS)
                drain()
            }
            abandon()
            runCatching { session.exec("PRAGMA wal_checkpoint(TRUNCATE)") }.onFailure { log.warn("Database checkpoint failed: {}", it.toString()) }
            connection.close()
        } finally {
            gate.unlock()
        }
    }

    private fun abandon() {
        val left = synchronized(queue) { queue.toList().also { queue.clear(); queued = 0 } }
        if (left.isEmpty()) return
        log.error("Dropped {} database writes at shutdown because the database stayed busy", left.sumOf { it.blocks.size })
        val e = IllegalStateException("database closed while busy")
        left.forEach { it.done?.completeExceptionally(e) }
    }

    companion object {
        private const val BATCH = 500
        private const val HIGH_WATER = 10_000
        private const val BUSY_RETRIES = 3
        private const val BACKOFF_MS = 200L
        private const val GATE_TIMEOUT_SECONDS = 30L
        private const val SQLITE_BUSY = 5
        private const val SQLITE_LOCKED = 6
        private const val THREAD = "kami-db"
        private val epoch = AtomicLong()
        private val dropped = AtomicLong()
        private val log = Log.of("library")

        @Volatile
        var failure: String? = null
            private set

        @Volatile
        var lastDropAt = 0L
            private set

        val dropCount: Long get() = dropped.get()

        private fun drop(e: Throwable, count: Int) {
            failure = e.toString()
            lastDropAt = System.currentTimeMillis()
            if (dropped.incrementAndGet() == 1L) log.error("Dropped {} database writes that failed; synced tables are rewritten in full on their next sync", count, e)
            else log.warn("Dropped {} more database writes: {}", count, e.toString())
        }

        val drops: Long get() = epoch.get()

        fun invalidate() {
            epoch.incrementAndGet()
        }
    }
}
