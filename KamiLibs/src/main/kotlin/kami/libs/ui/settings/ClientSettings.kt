package kami.libs.ui.settings

import kami.libs.config.Configs
import kami.libs.config.Jsonc
import kami.libs.config.writeAtomic
import kami.libs.log.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class SettingsSection<T : Any> internal constructor(val name: String, internal val serializer: KSerializer<T>, @Volatile var value: T)

class SettingsStore(private val file: Path) {
    private val json = Configs.json()
    private val log = Log.of("library")
    private val stored = LinkedHashMap<String, JsonElement>()
    private val sections = LinkedHashMap<String, SettingsSection<*>>()

    init {
        if (Files.exists(file)) {
            runCatching { json.parseToJsonElement(Jsonc.read(file)).jsonObject }
                .onSuccess { o -> o.forEach { (k, v) -> if (k != VERSION_KEY) stored[k] = v } }
                .onFailure {
                    runCatching { Files.copy(file, file.resolveSibling("${file.fileName}.bak"), StandardCopyOption.REPLACE_EXISTING) }
                    log.warn("Unreadable client settings {}, kept a .bak copy: {}", file, Jsonc.reason(it))
                }
        }
    }

    @Synchronized
    fun <T : Any> register(name: String, serializer: KSerializer<T>, default: T, legacy: Path? = null): SettingsSection<T> {
        require(name !in sections) { "Settings section '$name' is already registered" }
        val imported = legacy?.takeIf { name !in stored && Files.exists(it) }?.let { import(it, serializer) }
        val value = stored[name]?.let { runCatching { json.decodeFromJsonElement(serializer, it) }.getOrNull() } ?: imported ?: default
        return SettingsSection(name, serializer, value).also {
            sections[name] = it
            if (imported != null) save()
        }
    }

    private fun <T : Any> import(legacy: Path, serializer: KSerializer<T>): T? =
        runCatching { json.decodeFromJsonElement(serializer, json.parseToJsonElement(Jsonc.read(legacy)).jsonObject) }
            .onSuccess { runCatching { Files.move(legacy, legacy.resolveSibling("${legacy.fileName}.bak"), StandardCopyOption.REPLACE_EXISTING) } }
            .onFailure { log.warn("Legacy settings {} could not be read, keeping it untouched: {}", legacy, Jsonc.reason(it)) }
            .getOrNull()

    @Synchronized
    fun save() {
        val out = LinkedHashMap<String, JsonElement>()
        out[VERSION_KEY] = JsonPrimitive(VERSION)
        out.putAll(stored)
        sections.values.forEach { out[it.name] = encode(it) }
        runCatching { writeAtomic(file, json.encodeToString(JsonObject.serializer(), JsonObject(out))) }
            .onFailure { log.warn("Could not save {}: {}", file, it.message) }
    }

    private fun <T : Any> encode(s: SettingsSection<T>): JsonElement = json.encodeToJsonElement(s.serializer, s.value)

    companion object {
        const val VERSION = 2
        const val VERSION_KEY = "version"
    }
}

object ClientSettings {
    val store: SettingsStore by lazy { SettingsStore(Configs.dir("library").resolve("settings.json")) }

    fun <T : Any> register(name: String, serializer: KSerializer<T>, default: T, legacy: Path? = null) = store.register(name, serializer, default, legacy)

    fun save() = store.save()
}
