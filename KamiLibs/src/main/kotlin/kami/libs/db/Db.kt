package kami.libs.db

import kami.libs.log.Log
import kami.libs.util.Time.HOUR_MS
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import org.sqlite.JDBC
import org.sqlite.SQLiteConnection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.time.LocalDate
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

object Db {
    private class Open(val file: Path, val writer: Writer, val reader: Connection, val scanner: Connection, val viewer: Connection, val probe: Connection) {
        val session = Session(reader)
    }

    class ViewThread(task: Runnable, name: String) : Thread(task, name)

    private val log = Log.of("library")
    private val watchdog by lazy { Executors.newSingleThreadScheduledExecutor { Thread(it, "kami-db-watchdog").apply { isDaemon = true } } }
    private const val PROBE_MS = 8_000L
    private val pragmas = listOf(
        "PRAGMA journal_mode=WAL",
        "PRAGMA synchronous=NORMAL",
        "PRAGMA foreign_keys=ON",
        "PRAGMA busy_timeout=5000",
        "PRAGMA temp_store=MEMORY"
    )

    @Volatile
    private var state: Open? = null
    private val backingUp = AtomicBoolean()

    @Volatile
    private var backedUp: LocalDate? = null

    @Volatile
    private var retryAt = 0L

    fun <T> encode(serializer: SerializationStrategy<T>, value: T) = json.encodeToJsonElement(serializer, value)

    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun open(server: MinecraftServer) {
        val native = server.serverDirectory.resolve("kami").resolve("native").toAbsolutePath()
        Files.createDirectories(native)
        System.setProperty("org.sqlite.tmpdir", native.toString())
        val world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        val local = world.resolve("kami").resolve("kami.db")
        if (!server.isDedicatedServer) return open(local, world)
        val file = native.parent.resolve("kami.db").normalize()
        try {
            Relocate.run(local, file)
        } catch (e: Throwable) {
            log.error("{}", e.message, e)
            throw e
        }
        open(file, world)
    }

    @Synchronized
    fun open(file: Path, world: Path = file.toAbsolutePath().normalize().parent.parent) {
        close()
        LibsMigrations.register()
        val path = file.toAbsolutePath().normalize()
        Files.createDirectories(path.parent)
        try {
            connect(path).use { c -> Migrator.run(Session(c), path, world) }
            val readers = listOf(reading(path), reading(path), reading(path), reading(path, "PRAGMA temp_store=FILE"))
            state = Open(path, Writer(connect(path)), readers[0], readers[1], readers[2], readers[3])
            SavePoint.discardUnsaved()
        } catch (e: Throwable) {
            log.error("{}", e.message, e)
            throw e
        }
    }

    @Synchronized
    fun close() {
        val s = state ?: return
        state = null
        listOf(s.reader, s.scanner, s.viewer).forEach { reader -> synchronized(reader) { runCatching { reader.close() } } }
        interrupt(s.probe)
        synchronized(s.probe) { runCatching { s.probe.close() } }
        s.writer.close()
    }

    fun <T> read(fresh: Boolean = false, block: Session.() -> T): T {
        val s = current()
        if (fresh && s.writer.busy) flush()
        return synchronized(s.reader) { s.session.block() }
    }

    fun <T> scan(block: Session.() -> T): T {
        val s = current()
        val c = if (Thread.currentThread() is ViewThread) s.viewer else s.scanner
        return synchronized(c) { Session(c).block() }
    }

    fun <T> probe(limitMs: Long = PROBE_MS, block: Session.() -> T): T {
        val c = current().probe
        return synchronized(c) {
            val expired = AtomicBoolean()
            val timer = watchdog.schedule({ expired.set(true); interrupt(c) }, limitMs, TimeUnit.MILLISECONDS)
            try {
                Session(c).block()
            } catch (e: SQLException) {
                if (expired.get()) throw TimeoutException("query took longer than $limitMs ms") else throw e
            } finally {
                timer.cancel(false)
            }
        }
    }

    private fun interrupt(c: Connection) {
        runCatching { (c as SQLiteConnection).database.interrupt() }
    }

    fun backup() {
        val s = state ?: return
        val today = LocalDate.now()
        if (backedUp == today || System.currentTimeMillis() < retryAt || !backingUp.compareAndSet(false, true)) return
        Thread({
            try {
                if (connect(s.file).use { Migrator.daily(Session(it), s.file) }) backedUp = today else retryAt = System.currentTimeMillis() + HOUR_MS
            } catch (e: Throwable) {
                retryAt = System.currentTimeMillis() + HOUR_MS
                log.warn("Could not write the daily database backup: {}", e.toString())
            } finally {
                backingUp.set(false)
            }
        }, "kami-db-backup").apply { isDaemon = true }.start()
    }

    val failure: String? get() = Writer.failure

    val lastDropAt: Long get() = Writer.lastDropAt

    val dropCount: Long get() = Writer.dropCount

    fun write(block: Session.() -> Unit) = current().writer.submit(block)

    fun writeAll(blocks: List<Session.() -> Unit>, durable: Boolean = false) = current().writer.submitAll(blocks, durable, wait = false)

    fun durable(block: Session.() -> Unit) = current().writer.submit(block, durable = true)

    fun durableAsync(block: Session.() -> Unit) = current().writer.submit(block, durable = true, wait = false)

    fun flush() = current().writer.submit({}, wait = true)

    internal fun checkpoint() = current().writer.checkpoint()

    internal val isOpen: Boolean get() = state != null

    private fun current() = state ?: throw IllegalStateException("database is not open")

    private fun reading(file: Path, vararg extra: String): Connection = connect(file).also { c -> c.createStatement().use { s -> (listOf("PRAGMA query_only=ON") + extra).forEach(s::execute) } }

    private fun connect(file: Path): Connection = JDBC.createConnection("jdbc:sqlite:$file", Properties()).also { c ->
        c.createStatement().use { s -> pragmas.forEach { s.execute(it) } }
    }
}
