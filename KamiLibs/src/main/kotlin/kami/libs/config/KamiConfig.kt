package kami.libs.config

import kami.libs.log.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
    private val migrations: List<ConfigMigration> = emptyList(),
) {
    private val json = Configs.json()
    private val log = Log.of(mod)
    private val known = serializer.descriptor.elementNames.toSet()
    private val hint = if (reloadable) "Save, then run /$mod reload to apply." else "Changes apply on the next start."
    private val versioned = migrations.isNotEmpty()
    private val extras = HashMap<String, Pair<String, JsonElement>>()
    private val warned = HashSet<String>()
    private var locked = false

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
            runCatching { json.parseToJsonElement(Jsonc.read(dir.resolve(s.file))).jsonObject }
                .onSuccess { it.forEach { (k, v) -> merged[k] = v; origin[k] = s.file } }
                .exceptionOrNull()?.let { "${s.file}: ${Jsonc.reason(it)}" }
        }
        if (errors.isNotEmpty()) return fail(errors.joinToString("; "))
        val stored = if (versioned) (merged.remove(VERSION_KEY) as? JsonPrimitive)?.intOrNull ?: 0 else 0
        val latest = migrations.latest()
        val existing = sections.map { dir.resolve(it.file) }.filter { Files.exists(it) }
        locked = versioned && stored > latest
        var data = JsonObject(merged)
        if (locked) log.warn("{} has config version {} but this version only knows {}, it will not be rewritten", Configs.path(mod), stored, latest)
        else if (versioned && stored < latest && existing.isNotEmpty()) {
            existing.forEach { backupConfig(it, stored) }
            data = migrations.upgrade(data, stored)
            log.info("Upgraded {} from config version {} to {}", Configs.path(mod), stored, latest)
        }
        extras.clear()
        (data.keys - known).forEach {
            extras[it] = (origin[it] ?: sections.first().file) to data.getValue(it)
            if (warned.add(it)) log.warn("Unknown setting '{}' in {}, it is kept but ignored", it, Configs.path(mod, extras.getValue(it).first))
        }
        val decoded = runCatching { json.decodeFromJsonElement(serializer, data) }.getOrElse { return fail(Jsonc.reason(it)) }
        val fixed = sane(decoded)
        if (fixed != decoded) log.warn("Some values in {} were out of range and got corrected", Configs.path(mod))
        problem = null
        if (!locked && drops(JsonObject(data.filterKeys { it in known }), json.encodeToJsonElement(serializer, fixed))) existing.forEach(::backupOnce)
        save(fixed)
        return true
    }

    private fun drops(old: JsonElement, new: JsonElement): Boolean =
        old is JsonObject && (new !is JsonObject || old.any { (k, v) -> k !in new || drops(v, new.getValue(k)) })

    private fun backupOnce(path: Path) {
        val bak = path.resolveSibling("${path.fileName}.bak")
        if (!Files.exists(bak)) runCatching { Files.copy(path, bak) }.onSuccess { log.warn("Some settings in {} no longer exist, a copy is kept as {}", path, bak.fileName) }
    }

    val loaded: T by lazy { load(); value }

    @Synchronized
    fun save(v: T = value) {
        value = v
        if (locked) return
        if (problem != null) return log.warn("{} is not saved while its file is broken: {}", Configs.path(mod), problem)
        val all = json.encodeToJsonElement(serializer, v).jsonObject
        val dir = Configs.dir(mod)
        sections.forEachIndexed { i, s ->
            val part = all.filterKeys { it in s.keys || (i == 0 && sections.none { o -> it in o.keys }) }
            val head = VersionHead(versioned && i == 0, migrations.latest())
            val kept = extras.filterValues { it.first == s.file }.mapValues { it.value.second }
            Jsonc.write(dir.resolve(s.file), Jsonc.annotate(encode(head.entries + part + kept), head.docs(s.docs), listOf(s.title, hint)))
        }
    }

    private fun encode(entries: Map<String, JsonElement>) = json.encodeToString(JsonObject.serializer(), JsonObject(entries))

    private fun fail(message: String): Boolean {
        problem = message
        log.error("Could not load {}, keeping the current values: {}", Configs.path(mod), message)
        return false
    }

    private fun migrate(dir: Path) {
        val old = legacy?.let { Configs.root.resolveSibling(it) }?.takeIf { Files.isRegularFile(it) } ?: return
        if (sections.none { Files.exists(dir.resolve(it.file)) }) runCatching {
            Files.writeString(dir.resolve(sections.first().file), encode(json.parseToJsonElement(Jsonc.read(old)).jsonObject.filterKeys { it in known }))
        }.onFailure { log.warn("Could not read config/{}, starting from defaults: {}", legacy, Jsonc.reason(it)) }
        Files.move(old, dir.resolve("$legacy.old"), StandardCopyOption.REPLACE_EXISTING)
        log.info("Moved config/{} to {}, a copy is kept as {}.old", legacy, Configs.path(mod), legacy)
    }

    companion object {
        inline operator fun <reified T : Any> invoke(
            mod: String, default: T, sections: List<Section>, legacy: String? = null,
            reloadable: Boolean = true, noinline sane: (T) -> T = { it }, migrations: List<ConfigMigration> = emptyList(),
        ) = KamiConfig(mod, serializer<T>(), default, sections, legacy, reloadable, sane, migrations)
    }
}
