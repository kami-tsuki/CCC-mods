package kami.libs.db

import kami.libs.log.Log
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Properties

object Db {
    private class Open(val writer: Writer, val reader: Connection) {
        val session = Session(reader)
    }

    private val log = Log.of("library")
    private val pragmas = listOf(
        "PRAGMA auto_vacuum=INCREMENTAL",
        "PRAGMA journal_mode=WAL",
        "PRAGMA synchronous=NORMAL",
        "PRAGMA foreign_keys=ON",
        "PRAGMA busy_timeout=5000",
        "PRAGMA temp_store=MEMORY"
    )

    @Volatile
    private var state: Open? = null

    val json = Json { encodeDefaults = true }

    fun open(server: MinecraftServer) {
        val native = server.serverDirectory.resolve("kami").resolve("native").toAbsolutePath()
        Files.createDirectories(native)
        System.setProperty("org.sqlite.tmpdir", native.toString())
        open(server.getWorldPath(LevelResource.ROOT).resolve("kami").resolve("kami.db"))
    }

    @Synchronized
    fun open(file: Path) {
        close()
        LibsMigrations.register()
        val path = file.toAbsolutePath().normalize()
        val dir = path.parent
        Files.createDirectories(dir)
        try {
            connect(path).use { Migrator.run(Session(it), path, dir.parent) }
            state = Open(Writer(connect(path)), connect(path).also { c -> c.createStatement().use { it.execute("PRAGMA query_only=ON") } })
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

    fun write(block: Session.() -> Unit) = current().writer.submit(block)

    fun durable(block: Session.() -> Unit) = current().writer.submit(block, durable = true)

    fun flush() = current().writer.submit({}, wait = true)

    internal fun checkpoint() = current().writer.checkpoint()

    internal val isOpen: Boolean get() = state != null

    private fun current() = state ?: throw IllegalStateException("database is not open")

    private fun connect(file: Path): Connection = JDBC.createConnection("jdbc:sqlite:$file", Properties()).also { c ->
        c.createStatement().use { s -> pragmas.forEach { s.execute(it) } }
    }
}
