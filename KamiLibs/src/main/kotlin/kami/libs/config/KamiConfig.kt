package kami.libs.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class Section(val file: String, val title: String, val docs: Map<String, String>) {
    val keys: Set<String> = docs.keys.mapTo(LinkedHashSet()) { it.substringBefore('.') }
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
    private val known = (0 until serializer.descriptor.elementsCount).mapTo(HashSet()) { serializer.descriptor.getElementName(it) }

    @Volatile
    var value: T = default

    var problem: String? = null
        private set

    init {
        require(sections.isNotEmpty()) { "Config '$mod' needs at least one file" }
        if (reloadable) Configs.onReload(mod) { if (load()) null else error(problem ?: "unknown error") }
    }

    @Synchronized
    fun load(): Boolean {
        val dir = Configs.dir(mod)
        migrate(dir)
        val merged = LinkedHashMap<String, JsonElement>()
        val origin = HashMap<String, String>()
        val errors = sections.mapNotNull { s ->
            val path = dir.resolve(s.file)
            if (!Files.exists(path)) return@mapNotNull null
            runCatching { json.parseToJsonElement(Files.readString(path)).jsonObject }
                .onSuccess { it.forEach { (k, v) -> merged[k] = v; origin[k] = s.file } }
                .exceptionOrNull()?.let { "${s.file}: ${Jsonc.reason(it)}" }
        }
        if (errors.isNotEmpty()) return fail(errors.joinToString("; "))
        merged.keys.filter { it !in known }.forEach {
            Configs.log.warn("Unknown setting '{}' in {}, it will be removed", it, Configs.path(mod, origin.getValue(it)))
        }
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
        val hint = if (reloadable) "Save, then run /kami reload $mod to apply." else "Changes apply on the next start."
        sections.forEachIndexed { i, s ->
            val part = all.filterKeys { it in s.keys || (i == 0 && sections.none { o -> it in o.keys }) }
            Jsonc.write(dir.resolve(s.file), Jsonc.annotate(json.encodeToString(JsonObject.serializer(), JsonObject(part)), s.docs, listOf(s.title, hint)))
        }
    }

    private fun fail(message: String): Boolean {
        problem = message
        Configs.log.error("Could not load {}, keeping the current values: {}", Configs.path(mod), message)
        return false
    }

    private fun migrate(dir: Path) {
        val old = legacy?.let { Configs.root.resolveSibling(it) }?.takeIf { Files.isRegularFile(it) } ?: return
        if (sections.none { Files.exists(dir.resolve(it.file)) }) runCatching {
            val kept = json.parseToJsonElement(Files.readString(old)).jsonObject.filterKeys { it in known }
            Files.writeString(dir.resolve(sections.first().file), json.encodeToString(JsonObject.serializer(), JsonObject(kept)))
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
