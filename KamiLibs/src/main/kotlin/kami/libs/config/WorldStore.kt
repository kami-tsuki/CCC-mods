package kami.libs.config

import kami.libs.log.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
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

    private fun sibling(path: Path, suffix: String) = path.resolveSibling("${path.fileName}.$suffix")

    private fun decode(path: Path): Result<T> = runCatching { json.decodeFromString(serializer, Files.readString(path)) }

    fun load(path: Path, onBad: (Throwable) -> Unit = {}): T {
        file = path
        val baks = listOf(sibling(path, "bak"), sibling(path, "bak2"))
        dirty = false
        data = default()
        if (Files.exists(path)) {
            val primary = decode(path)
            primary.onSuccess { data = it }
            primary.onFailure {
                onBad(it)
                runCatching { Files.move(path, sibling(path, "bad"), StandardCopyOption.REPLACE_EXISTING) }.onFailure(onBad)
                recoverFromBak(path, baks, onBad)
            }
        } else recoverFromBak(path, baks, onBad)
        return data
    }

    private fun recoverFromBak(path: Path, baks: List<Path>, onBad: (Throwable) -> Unit) {
        for (bak in baks.filter { Files.exists(it) }) {
            val recovered = decode(bak)
            if (recovered.isSuccess) {
                data = recovered.getOrThrow()
                dirty = true
                onBad(IllegalStateException("recovered ${path.fileName} from ${bak.fileName}"))
                return
            }
            recovered.exceptionOrNull()?.let(onBad)
        }
    }

    fun changed() {
        dirty = true
    }

    fun save(force: Boolean = false, rotate: Boolean = true): Boolean {
        val path = file ?: return false
        if (!dirty && !force) return true
        return try {
            val tmp = sibling(path, "tmp")
            Files.writeString(tmp, json.encodeToString(serializer, data))
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
            if (rotate && backup && Files.exists(path)) {
                runCatching { rotate(path) }.onFailure { Log.of("libs").warn("Backup rotation failed for {}: {}", path.fileName, it.toString()) }
            }
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
            }
            dirty = false
            true
        } catch (e: Exception) {
            Log.of("libs").error("Could not save {}: {}", path.fileName, e.toString())
            false
        }
    }

    private fun rotate(path: Path) {
        val bak = sibling(path, "bak")
        if (Files.exists(bak)) Files.copy(bak, sibling(path, "bak2"), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(path, bak, StandardCopyOption.REPLACE_EXISTING)
    }
}
