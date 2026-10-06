package kami.libs.db

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

class Migration(
    val module: String,
    val version: Int,
    val name: String,
    val legacy: List<String> = emptyList(),
    val ddl: List<String> = emptyList(),
    val up: Session.(world: Path) -> (Session.() -> Unit) = { {} }
) {
    val checksum: String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((listOf("$module:$version:$name") + ddl).joinToString("\n").toByteArray()))
}

class MigrationFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object Migrations {
    internal class Module(val name: String, val list: List<Migration>, val after: List<String>)

    private val modules = ConcurrentHashMap<String, Module>()

    fun register(module: String, list: List<Migration>, after: List<String> = listOf("libs")) {
        require(list.all { it.module == module }) { "migrations of $module must use module $module" }
        require(list.map { it.version }.toSet().size == list.size && list.all { it.version > 0 }) { "migration versions of $module must be unique and positive" }
        modules[module] = Module(module, list.sortedBy { it.version }, after - module)
    }

    fun signature(module: String): List<String> = modules[module]?.list.orEmpty().map { "${it.version}:${it.name}" }

    internal fun ordered(): List<Module> {
        val left = modules.toSortedMap()
        val out = ArrayList<Module>()
        while (left.isNotEmpty()) {
            val next = left.values.firstOrNull { m -> m.after.none { it in left } }
                ?: throw MigrationFailure("migration modules depend on each other in a cycle: ${left.keys}")
            out += next
            left.remove(next.name)
        }
        return out
    }
}
