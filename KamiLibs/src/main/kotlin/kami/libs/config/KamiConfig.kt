package kami.libs.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class Section(val file: String, val title: String, val docs: Map<String, String>) {
    val keys: Set<String> = docs.keys.mapTo(HashSet()) { it.substringBefore('.') }
}

class KamiConfig<T : Any>(
    val mod: String,
    private val serializer: KSerializer<T>,
    default: T,
    private val sections: List<Section>,
    private val legacy: String? = null,
    private val reloadable: Boolean = true,
    private val sane: (T) -> T = { it },
) {
    private val json = Configs.json()
    private val known = serializer.descriptor.elementNames.toSet()
    private val hint = if (reloadable) "Save, then run /$mod reload to apply." else "Changes apply on the next start."

    @Volatile
    var value: T = default

    var problem: String? = null
        private set

    init {
        require(sections.isNotEmpty()) { "Config '$mod' needs at least one file" }
        if (reloadable) Configs.onReload(mod) { if (load()) null else error(problem.orEmpty()) }
    }

    @Synchronized
    fun load(): Boolean {
        val dir = Configs.dir(mod)
        migrate(dir)
        val merged = LinkedHashMap<String, JsonElement>()
        val origin = HashMap<String, String>()
        val errors = sections.filter { Files.exists(dir.resolve(it.file)) }.mapNotNull { s ->
            runCatching { json.parseToJsonElement(Files.readString(dir.resolve(s.file))).jsonObject }
                .onSuccess { it.forEach { (k, v) -> merged[k] = v; origin[k] = s.file } }
                .exceptionOrNull()?.let { "${s.file}: ${Jsonc.reason(it)}" }
        }
        if (errors.isNotEmpty()) return fail(errors.joinToString("; "))
        (merged.keys - known).forEach { Configs.log.warn("Unknown setting '{}' in {}, it will be removed", it, Configs.path(mod, origin.getValue(it))) }
        val decoded = runCatching { json.decodeFromJsonElement(serializer, JsonObject(merged)) }.getOrElse { return fail(Jsonc.reason(it)) }
        val fixed = sane(decoded)
        if (fixed != decoded) Configs.log.warn("Some values in {} were out of range and got corrected", Configs.path(mod))
        problem = null
        save(fixed)
        return true
    }

    @Synchronized
    fun save(v: T = value) {
        value = v
        val all = json.encodeToJsonElement(serializer, v).jsonObject
        val dir = Configs.dir(mod)
        sections.forEachIndexed { i, s ->
            val part = all.filterKeys { it in s.keys || (i == 0 && sections.none { o -> it in o.keys }) }
            Jsonc.write(dir.resolve(s.file), Jsonc.annotate(encode(part), s.docs, listOf(s.title, hint)))
        }
    }

    private fun encode(entries: Map<String, JsonElement>) = json.encodeToString(JsonObject.serializer(), JsonObject(entries))

    private fun fail(message: String): Boolean {
        problem = message
        Configs.log.error("Could not load {}, keeping the current values: {}", Configs.path(mod), message)
        return false
    }

    private fun migrate(dir: Path) {
        val old = legacy?.let { Configs.root.resolveSibling(it) }?.takeIf { Files.isRegularFile(it) } ?: return
        if (sections.none { Files.exists(dir.resolve(it.file)) }) runCatching {
            Files.writeString(dir.resolve(sections.first().file), encode(json.parseToJsonElement(Files.readString(old)).jsonObject.filterKeys { it in known }))
        }.onFailure { Configs.log.warn("Could not read config/{}, starting from defaults: {}", legacy, Jsonc.reason(it)) }
        Files.move(old, dir.resolve("$legacy.old"), StandardCopyOption.REPLACE_EXISTING)
        Configs.log.info("Moved config/{} to {}, a copy is kept as {}.old", legacy, Configs.path(mod), legacy)
    }

    companion object {
        inline operator fun <reified T : Any> invoke(
            mod: String, default: T, sections: List<Section>, legacy: String? = null,
            reloadable: Boolean = true, noinline sane: (T) -> T = { it },
        ) = KamiConfig(mod, serializer<T>(), default, sections, legacy, reloadable, sane)
    }
}
