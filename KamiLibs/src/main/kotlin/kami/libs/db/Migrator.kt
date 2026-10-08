package kami.libs.db

import kami.libs.log.Log
import kotlinx.serialization.encodeToString
import net.neoforged.fml.ModList
import org.sqlite.JDBC
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name

internal object Migrator {
    private class Applied(val module: String, val version: Int, val name: String, val checksum: String)

    private val log = Log.of("library")
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private const val KEEP = 10
    private const val DAILY_KEEP = 7
    private const val REUSE_MS = 3_600_000L

    fun run(db: Session, file: Path, world: Path) {
        quickCheck(db)
        db.exec("CREATE TABLE IF NOT EXISTS libs_migration (module TEXT NOT NULL, version INTEGER NOT NULL, name TEXT NOT NULL, checksum TEXT NOT NULL, applied_at INTEGER NOT NULL, PRIMARY KEY (module, version))")
        db.exec("CREATE TABLE IF NOT EXISTS libs_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        val applied = db.query("SELECT module, version, name, checksum FROM libs_migration") {
            Applied(it.string("module"), it.int("version"), it.string("name"), it.string("checksum"))
        }
        val modules = Migrations.ordered()
        guard(db, applied, modules)
        imported(applied, modules, file, world)
        val done = applied.map { it.module to it.version }.toSet()
        val pending = modules.flatMap { it.list }.filter { (it.module to it.version) !in done }
        if (pending.isEmpty()) {
            versions(db)
            return
        }
        val backups = file.resolveSibling("backups").also { Files.createDirectories(it) }
        val time = LocalDateTime.now().format(stamp)
        val key = Integer.toHexString(pending.map { "${it.module}:${it.version}:${it.checksum}" }.hashCode())
        val pre = if (applied.isEmpty()) null else kept(backups, "kami-", "-$key-pre.db", verify = true) ?: backups.resolve("kami-$time-$key-pre.db").also { copy(db, it) }
        val legacy = pending.flatMap { m -> m.legacy.flatMap { listOf(it, "$it.bak", "$it.bak2", "$it.wal") } }
            .distinct().map { world.resolve(it) }.filter { Files.exists(it) }
        val zip = if (legacy.isEmpty()) null else kept(backups, "legacy-", "-$key.zip") ?: backups.resolve("legacy-$time-$key.zip").also { zip(it, world, legacy) }
        prune(backups, "kami-", "-pre.db", KEEP)
        prune(backups, "legacy-", ".zip", KEEP)
        var current: Migration? = null
        try {
            db.exec("BEGIN IMMEDIATE")
            val checks = pending.map { m ->
                current = m
                m.ddl.forEach { db.exec(it) }
                m to m.up(db, world)
            }
            checks.forEach { (m, verify) -> current = m; verify(db) }
            current = null
            val broken = db.query("PRAGMA foreign_key_check") { "${it.string("table")} -> ${it.string("parent")}" }.distinct()
            if (broken.isNotEmpty()) throw MigrationFailure("foreign key check failed: ${broken.joinToString()}")
            quickCheck(db)
            val now = System.currentTimeMillis()
            db.batch("INSERT INTO libs_migration (module, version, name, checksum, applied_at) VALUES (?, ?, ?, ?, ?)", pending.map { listOf(it.module, it.version, it.name, it.checksum, now) })
            versions(db)
            db.exec("COMMIT")
        } catch (e: Throwable) {
            runCatching { db.exec("ROLLBACK") }
            throw MigrationFailure(report(current, e, file, pre, zip), e)
        }
        legacy.forEach { p ->
            runCatching { Files.move(p, p.resolveSibling("${p.name}.migrated")) }
                .onFailure { log.error("Could not rename migrated legacy file {}: {}", p, it.toString()) }
        }
        log.info("Applied {} database migrations: {}", pending.size, pending.joinToString { "${it.module} v${it.version} ${it.name}" })
    }

    fun quickCheck(db: Session) {
        val result = db.query("PRAGMA quick_check") { it.string("quick_check") }
        if (result != listOf("ok")) throw MigrationFailure("database integrity check failed: ${result.take(5).joinToString("; ")}")
    }

    private fun guard(db: Session, applied: List<Applied>, modules: List<Migrations.Module>) {
        val registered = modules.associateBy { it.name }
        applied.groupBy { it.module }.forEach { (module, rows) ->
            val known = registered[module]?.list ?: return@forEach
            val byVersion = known.associateBy { it.version }
            val top = known.maxOfOrNull { it.version } ?: 0
            rows.sortedByDescending { it.version }.forEach { row ->
                val reg = byVersion[row.version]
                if (row.version > top) throw MigrationFailure(
                    "world was opened by a newer Kami version ${stored(db) ?: "unknown"}; restore the backup taken before that update or update the mods ($module is at v${row.version}, this build knows v$top)"
                )
                if (reg == null || reg.name != row.name || reg.checksum != row.checksum) throw MigrationFailure("migration $module v${row.version} was changed after release")
            }
        }
    }

    private fun imported(applied: List<Applied>, modules: List<Migrations.Module>, file: Path, world: Path) {
        val known = applied.map { it.module }.toSet()
        modules.filter { it.name !in known }.forEach { m ->
            m.list.flatMap { it.legacy }.distinct().firstOrNull { !Files.exists(world.resolve(it)) && Files.exists(world.resolve("$it.migrated")) }?.let {
                throw MigrationFailure(
                    "$it was already imported into a Kami database (found $it.migrated), but $file has no record of ${m.name}; " +
                        "restore kami.db from kami/backups or rename the .migrated files back to import them again"
                )
            }
        }
    }

    private fun stored(db: Session) = db.one("SELECT value FROM libs_meta WHERE key = ?", "versions") { it.string("value") }

    private fun versions(db: Session) {
        val mods = runCatching { ModList.get()?.mods }.getOrNull() ?: return
        val value = Db.json.encodeToString(mods.filter { it.modId.startsWith("kami") }.associate { it.modId to it.version.toString() })
        db.exec("INSERT INTO libs_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", "versions", value)
    }

    private fun zip(target: Path, world: Path, files: List<Path>) {
        val partial = partial(target)
        ZipOutputStream(Files.newOutputStream(partial)).use { out ->
            files.forEach { f ->
                out.putNextEntry(ZipEntry((if (f.startsWith(world)) world.relativize(f).toString() else "external/${f.name}").replace('\\', '/')))
                Files.copy(f, out)
                out.closeEntry()
            }
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun partial(target: Path) = target.resolveSibling("${target.name}.tmp").also { Files.deleteIfExists(it) }

    private fun copy(db: Session, target: Path) {
        val partial = partial(target)
        try {
            db.exec("VACUUM INTO ?", partial.toString())
            check(verified(partial)) { "backup copy failed its integrity check" }
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Throwable) {
            runCatching { Files.deleteIfExists(partial) }
            throw e
        }
    }

    private fun verified(file: Path) = runCatching {
        JDBC.createConnection("jdbc:sqlite:$file", Properties()).use { c -> Session(c).query("PRAGMA quick_check") { it.string("quick_check") } == listOf("ok") }
    }.getOrDefault(false)

    fun daily(db: Session, file: Path): Boolean {
        val backups = file.resolveSibling("backups").also { Files.createDirectories(it) }
        val target = backups.resolve("kami-daily-${LocalDate.now()}.db")
        if (Files.exists(target)) return true
        listed(backups, "kami-", ".tmp").forEach { runCatching { Files.delete(it) } }
        val done = runCatching { copy(db, target) }.onFailure { log.warn("Could not write the daily database backup: {}", it.toString()) }.isSuccess
        if (done) prune(backups, "kami-daily-", ".db", DAILY_KEEP)
        return done
    }

    private fun listed(backups: Path, prefix: String, suffix: String) =
        Files.list(backups).use { s -> s.filter { it.name.startsWith(prefix) && it.name.endsWith(suffix) }.sorted().toList() }

    private fun kept(backups: Path, prefix: String, suffix: String, verify: Boolean = false) =
        listed(backups, prefix, suffix).lastOrNull()?.takeIf { System.currentTimeMillis() - Files.getLastModifiedTime(it).toMillis() < REUSE_MS && (!verify || verified(it)) }

    private fun prune(backups: Path, prefix: String, suffix: String, keep: Int) =
        listed(backups, prefix, suffix).dropLast(keep).forEach { runCatching { Files.delete(it) } }

    private fun report(failed: Migration?, cause: Throwable, file: Path, pre: Path?, zip: Path?) = buildString {
        appendLine("Kami database migration failed; the server will not start. The database was rolled back unchanged.")
        appendLine("  failed: ${failed?.let { "${it.module} v${it.version} ${it.name}" } ?: "checks after all migrations"}")
        appendLine("  cause: $cause")
        appendLine("  database: $file")
        appendLine("  backup: ${pre ?: "none (new database)"}")
        appendLine("  legacy files: ${zip ?: "none"}")
        append("  restore: stop the server, copy ${pre ?: "the backup"} over $file and delete $file-wal and $file-shm")
    }
}
