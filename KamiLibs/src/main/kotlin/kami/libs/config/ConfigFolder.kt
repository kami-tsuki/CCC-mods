package kami.libs.config

import kami.libs.log.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

class ConfigFolder(
    val root: Path, private val hint: String, private val json: Json = Configs.json(), private val log: Log = Log.of("config"), private val prepare: (Path) -> Unit = {},
) {
    private val found = ArrayList<String>()
    val problems: List<String> get() = found
    private val lastGood = HashMap<Path, Any>()

    fun startLoad() {
        found.clear()
        Files.createDirectories(root)
    }

    fun <T : Any> file(
        relative: String, serializer: KSerializer<T>, default: T, docs: Map<String, String>, title: String,
        migrations: List<ConfigMigration> = emptyList(),
    ): T {
        val path = root.resolve(relative)
        if (!Files.exists(path)) write(path, serializer, default, docs, title, migrations.latest(), migrations.isNotEmpty())
        return read(path, serializer, docs, title, migrations) ?: default
    }

    fun <T : Any> files(
        subfolder: String, serializer: KSerializer<T>, defaults: Map<String, T>, docs: Map<String, String>, title: String,
        migrations: List<ConfigMigration> = emptyList(),
    ): Map<String, T> {
        val dir = Files.createDirectories(root.resolve(subfolder))
        defaults.forEach { (name, value) ->
            val path = dir.resolve("$name.json")
            if (!Files.exists(path)) write(path, serializer, value, docs, title, migrations.latest(), migrations.isNotEmpty())
        }
        val paths = Files.list(dir).use { stream -> stream.filter { it.extension == "json" }.sorted().toList() }
        return paths.mapNotNull { path -> read(path, serializer, docs, title, migrations)?.let { path.nameWithoutExtension to it } }.toMap(LinkedHashMap())
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> read(path: Path, serializer: KSerializer<T>, docs: Map<String, String>, title: String, migrations: List<ConfigMigration>): T? = try {
        val raw = json.parseToJsonElement(Jsonc.read(path)).jsonObject
        val versioned = migrations.isNotEmpty()
        val stored = if (versioned) raw.storedVersion() else 0
        val latest = migrations.latest()
        val data = JsonObject(if (versioned) raw - VERSION_KEY else raw)
        val upgraded = if (versioned && stored < latest) migrations.upgrade(data, stored) else data
        val value = json.decodeFromJsonElement(serializer, upgraded)
        if (versioned && stored > latest) {
            log.warn("{} has config version {} but this version only knows {}, it will not be rewritten", path.fileName, stored, latest)
        } else if (versioned && stored < latest) {
            backupConfig(path, stored)
            val kept = upgraded.filterKeys { it !in serializer.descriptor.elementNames }
            write(path, serializer, value, docs, title, latest, true, kept)
            log.info("Upgraded {} from config version {} to {}", path.fileName, stored, latest)
        }
        value.also { lastGood[path] = it }
    } catch (e: Exception) {
        val reason = Jsonc.reason(e)
        log.error("Ignoring {}: {}", path.fileName, reason)
        found += "${root.relativize(path).joinToString("/")}: $reason"
        lastGood[path] as T?
    }

    private fun <T : Any> write(
        path: Path, serializer: KSerializer<T>, value: T, docs: Map<String, String>, title: String, version: Int, versioned: Boolean,
        kept: Map<String, JsonElement> = emptyMap(),
    ) {
        Files.createDirectories(path.parent)
        val head = VersionHead(versioned, version)
        val all = JsonObject(head.entries + json.encodeToJsonElement(serializer, value).jsonObject + kept)
        val text = json.encodeToString(JsonObject.serializer(), all)
        Jsonc.write(path, Jsonc.annotate(text, head.docs(docs), listOf(title, hint)), prepare)
    }
}
