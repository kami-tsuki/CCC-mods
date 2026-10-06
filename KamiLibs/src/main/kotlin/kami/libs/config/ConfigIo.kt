package kami.libs.config

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class ConfigMigration(val version: Int, val up: (JsonObject) -> JsonObject)

internal const val VERSION_KEY = "configVersion"

internal class VersionHead(on: Boolean, version: Int) {
    val entries: Map<String, JsonElement> = if (on) mapOf(VERSION_KEY to JsonPrimitive(version)) else emptyMap()

    fun docs(docs: Map<String, String>) = if (entries.isEmpty()) docs else docs + (VERSION_KEY to "Config version, do not edit.")
}

internal fun List<ConfigMigration>.latest() = maxOfOrNull { it.version } ?: 0

internal fun List<ConfigMigration>.upgrade(data: JsonObject, from: Int) =
    filter { it.version > from }.sortedBy { it.version }.fold(data) { o, m -> m.up(o) }

internal fun JsonObject.storedVersion() = (this[VERSION_KEY] as? JsonPrimitive)?.intOrNull ?: 0

internal fun backupConfig(path: Path, version: Int) {
    val dir = Files.createDirectories(path.resolveSibling(".backup"))
    Files.copy(path, dir.resolve("${path.fileName}.v$version.json"), StandardCopyOption.REPLACE_EXISTING)
}

fun writeAtomic(path: Path, text: String, prepare: (Path) -> Unit = {}) {
    val tmp = path.resolveSibling("${path.fileName}.tmp")
    Files.writeString(tmp, text)
    prepare(tmp)
    FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
    try {
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    }
}
