package kami.libs.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import net.neoforged.fml.loading.FMLPaths
import kami.libs.log.Log
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalSerializationApi::class)
object Configs {
    private val reloaders = LinkedHashMap<String, MutableList<() -> String?>>()

    var base: Path? = null
    val root: Path get() = (base ?: FMLPaths.CONFIGDIR.get()).resolve("kami")
    val mods: List<String> @Synchronized get() = reloaders.keys.toList()

    fun json(build: JsonBuilder.() -> Unit = {}): Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        allowComments = true
        allowTrailingComma = true
        build()
    }

    fun dir(mod: String): Path = Files.createDirectories(root.resolve(mod))

    fun path(mod: String, file: String = "") = "config/kami/$mod/$file"

    fun moveLegacy(old: String, mod: String) {
        val from = root.resolveSibling(old)
        if (!Files.isDirectory(from) || Files.exists(root.resolve(mod))) return
        Files.move(from, Files.createDirectories(root).resolve(mod))
        Log.of(mod).info("Moved config/{} to {}", old, path(mod))
    }

    @Synchronized
    fun onReload(mod: String, action: () -> String?) {
        reloaders.getOrPut(mod) { ArrayList() } += action
    }

    @Synchronized
    fun reload(mod: String? = null): List<Pair<String, Result<String?>>> =
        reloaders.filterKeys { mod == null || it == mod }.flatMap { (name, actions) -> actions.map { name to runCatching(it) } }
}
