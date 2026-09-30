package kami.libs.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class WorldStore<T : Any>(
    private val serializer: KSerializer<T>,
    private val default: () -> T,
    private val json: Json = Configs.json(),
    private val backup: Boolean = true
) {
    var data: T = default()
    var dirty = false
    private var file: Path? = null

    fun load(path: Path, onBad: (Throwable) -> Unit = {}): T {
        file = path
        val bak = path.resolveSibling("${path.fileName}.bak")
        if (Files.exists(path)) {
            val primary = runCatching { json.decodeFromString(serializer, Files.readString(path)) }
            if (primary.isSuccess) {
                data = primary.getOrThrow()
                dirty = false
            } else {
                onBad(primary.exceptionOrNull()!!)
                Files.move(path, path.resolveSibling("${path.fileName}.bad"), StandardCopyOption.REPLACE_EXISTING)
                val recovered = if (Files.exists(bak)) runCatching { json.decodeFromString(serializer, Files.readString(bak)) } else null
                if (recovered != null && recovered.isSuccess) {
                    data = recovered.getOrThrow()
                    dirty = true
                    onBad(IllegalStateException("recovered ${path.fileName} from ${bak.fileName}"))
                } else {
                    recovered?.exceptionOrNull()?.let(onBad)
                    data = default()
                    dirty = false
                }
            }
        } else if (Files.exists(bak)) {
            val recovered = runCatching { json.decodeFromString(serializer, Files.readString(bak)) }
            if (recovered.isSuccess) {
                data = recovered.getOrThrow()
                dirty = true
                onBad(IllegalStateException("recovered ${path.fileName} from ${bak.fileName}"))
            } else {
                recovered.exceptionOrNull()?.let(onBad)
                data = default()
                dirty = false
            }
        } else {
            data = default()
            dirty = false
        }
        return data
    }

    fun changed() {
        dirty = true
    }

    fun save(force: Boolean = false) {
        val path = file ?: return
        if (!dirty && !force) return
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(serializer, data))
        FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
        if (backup && Files.exists(path)) Files.copy(path, path.resolveSibling("${path.fileName}.bak"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        dirty = false
    }
}
