package kami.libs.config

import kami.libs.log.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class ConfigFolder(val root: Path, private val hint: String, private val json: Json = Configs.json(), private val log: Log = Log.of("config")) {
    private val found = ArrayList<String>()
    val problems: List<String> get() = found
    private val lastGood = HashMap<Path, Any>()

    fun startLoad() {
        found.clear()
        Files.createDirectories(root)
    }

    fun <T : Any> file(relative: String, serializer: KSerializer<T>, default: T, docs: Map<String, String>, title: String): T {
        val path = root.resolve(relative)
        if (!Files.exists(path)) write(path, serializer, default, docs, title)
        return read(path, serializer) ?: default
    }

    fun <T : Any> files(subfolder: String, serializer: KSerializer<T>, defaults: Map<String, T>, docs: Map<String, String>, title: String): Map<String, T> {
        val dir = Files.createDirectories(root.resolve(subfolder))
        defaults.forEach { (name, value) ->
            val path = dir.resolve("$name.json")
            if (!Files.exists(path)) write(path, serializer, value, docs, title)
        }
        val paths = Files.list(dir).use { stream -> stream.filter { it.extension == "json" }.sorted().toList() }
        return paths.mapNotNull { path -> read(path, serializer)?.let { path.nameWithoutExtension to it } }.toMap(LinkedHashMap())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> read(path: Path, serializer: KSerializer<T>): T? = try {
        json.decodeFromString(serializer, Files.readString(path)).also { lastGood[path] = it }
    } catch (e: Exception) {
        val reason = Jsonc.reason(e)
        log.error("Ignoring {}: {}", path.fileName, reason)
        found += "${root.relativize(path).joinToString("/")}: $reason"
        lastGood[path] as T?
    }

    private fun <T : Any> write(path: Path, serializer: KSerializer<T>, value: T, docs: Map<String, String>, title: String) {
        Files.createDirectories(path.parent)
        Jsonc.write(path, Jsonc.annotate(json.encodeToString(serializer, value), docs, listOf(title, hint)))
    }
}
