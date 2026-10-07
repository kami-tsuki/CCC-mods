package kami.libs.db

import kami.libs.log.Log
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.util.Properties
import kotlin.io.path.name

internal object Relocate {
    private val log = Log.of("library")

    fun run(old: Path, target: Path) {
        if (old == target) return
        val oldExists = Files.exists(old)
        if (Files.exists(target)) {
            if (oldExists) throw MigrationFailure(
                "found two Kami databases: $target and $old; keep the one with the newest data (normally $target) and delete or rename the other one, then start again"
            )
            return
        }
        val migrated = old.resolveSibling("${old.name}.migrated")
        val temp = target.resolveSibling("${target.name}.relocating")
        if (!oldExists && Files.exists(migrated) && Files.exists(temp)) {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            log.info("Finished the interrupted move of the Kami database to {}", target)
            return
        }
        if (!oldExists) {
            if (Files.exists(migrated)) throw MigrationFailure(
                "$old was already moved to $target (found $migrated), but $target is missing; restore $target from its backup or rename $migrated back to ${old.name} to relocate it again"
            )
            return
        }
        Files.createDirectories(target.parent)
        Files.deleteIfExists(temp)
        try {
            connect(old).use { source ->
                val s = Session(source)
                s.exec("PRAGMA wal_checkpoint(TRUNCATE)")
                s.exec("VACUUM INTO ?", temp.toString())
                connect(temp).use { copy -> verify(s, Session(copy)) }
            }
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            throw MigrationFailure("could not move the Kami database $old to $target; the old database was left untouched", e)
        }
        listOf("-wal", "-shm", "").forEach { suffix ->
            val file = old.resolveSibling(old.name + suffix)
            if (Files.exists(file)) Files.move(file, file.resolveSibling("${file.name}.migrated"), StandardCopyOption.REPLACE_EXISTING)
        }
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        backups(old.resolveSibling("backups"), target.resolveSibling("backups"))
        log.info("Moved the Kami database from {} to {}; the old file was renamed to {}", old, target, migrated.name)
    }

    private fun connect(file: Path): Connection = JDBC.createConnection("jdbc:sqlite:$file", Properties())

    private fun verify(source: Session, copy: Session) {
        val result = copy.query("PRAGMA integrity_check") { it.string("integrity_check") }
        check(result == listOf("ok")) { "integrity check of the copy failed: ${result.take(5).joinToString("; ")}" }
        val tables = source.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { it.string("name") }
        check(tables == copy.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { it.string("name") }) { "table list of the copy differs" }
        tables.forEach { t ->
            val sql = "SELECT COUNT(*) AS n FROM \"$t\""
            Verify.equal("rows of $t", source.one(sql) { it.long("n") }, copy.one(sql) { it.long("n") })
        }
    }

    private fun backups(from: Path, to: Path) {
        if (!Files.isDirectory(from)) return
        Files.createDirectories(to)
        runCatching {
            Files.list(from).use { s -> s.toList() }.forEach { f ->
                runCatching { Files.move(f, to.resolve(f.name)) }.onFailure { log.error("Could not move backup {}: {}", f, it.toString()) }
            }
            Files.deleteIfExists(from)
        }.onFailure { log.error("Could not move the old backups from {}: {}", from, it.toString()) }
    }
}
