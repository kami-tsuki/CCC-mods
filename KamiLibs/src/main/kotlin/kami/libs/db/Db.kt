package kami.libs.db

import kami.libs.log.Log
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.LocalDate
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

object Db {
    private class Open(val file: Path, val writer: Writer, val reader: Connection) {
        val session = Session(reader)
    }

    private val log = Log.of("library")
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
            state = Open(path, Writer(connect(path)), connect(path).also { c -> c.createStatement().use { it.execute("PRAGMA query_only=ON") } })
        } catch (e: Throwable) {
            log.error("{}", e.message, e)
            throw e
        }
    }

    @Synchronized
    fun close() {
        val s = state ?: return
        state = null
        runCatching { s.reader.close() }
        s.writer.close()
    }

    fun <T> read(fresh: Boolean = true, block: Session.() -> T): T {
        val s = current()
        if (fresh && s.writer.busy) flush()
        return synchronized(s.reader) { s.session.block() }
    }

    fun <T> scan(block: Session.() -> T): T {
        val s = current()
        return connect(s.file).use { c ->
            c.createStatement().use { it.execute("PRAGMA query_only=ON") }
            Session(c).block()
        }
    }

    fun backup() {
        val s = state ?: return
        val today = LocalDate.now()
        if (backedUp == today || !backingUp.compareAndSet(false, true)) return
        Thread({
            try {
                if (connect(s.file).use { Migrator.daily(Session(it), s.file) }) backedUp = today
            } catch (e: Throwable) {
                log.warn("Could not write the daily database backup: {}", e.toString())
            } finally {
                backingUp.set(false)
            }
        }, "kami-db-backup").apply { isDaemon = true }.start()
    }

    fun write(block: Session.() -> Unit) = current().writer.submit(block)

    fun writeAll(blocks: List<Session.() -> Unit>, durable: Boolean = false) = current().writer.submitAll(blocks, durable, wait = false)

    fun durable(block: Session.() -> Unit) = current().writer.submit(block, durable = true)

    fun flush() = current().writer.submit({}, wait = true)

    internal fun checkpoint() = current().writer.checkpoint()

    internal val isOpen: Boolean get() = state != null

    private fun current() = state ?: throw IllegalStateException("database is not open")

    private fun connect(file: Path): Connection = JDBC.createConnection("jdbc:sqlite:$file", Properties()).also { c ->
        c.createStatement().use { s -> pragmas.forEach { s.execute(it) } }
    }
}
