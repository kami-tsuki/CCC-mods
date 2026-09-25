package kami.libs.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import net.neoforged.fml.loading.FMLPaths
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

@OptIn(ExperimentalSerializationApi::class)
object Configs {
    const val FOLDER = "kami"
    internal val log: Logger = LoggerFactory.getLogger("kami_libs")

    var base: Path? = null
    val root: Path get() = (base ?: FMLPaths.CONFIGDIR.get()).resolve(FOLDER)

    private val reloaders = LinkedHashMap<String, MutableList<() -> String?>>()

    fun json(build: JsonBuilder.() -> Unit = {}): Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        allowComments = true
        allowTrailingComma = true
        build()
    }

    fun dir(mod: String): Path = root.resolve(mod).also { Files.createDirectories(it) }

    fun path(mod: String, file: String = ""): String = "config/$FOLDER/$mod/$file"

    fun moveLegacy(old: String, mod: String) {
        val from = root.resolveSibling(old)
        val to = root.resolve(mod)
        if (!Files.isDirectory(from) || Files.exists(to)) return
        Files.createDirectories(root)
        Files.move(from, to)
        log.info("Moved config/{} to {}", old, path(mod))
    }

    @Synchronized
    fun onReload(mod: String, action: () -> String?) {
        reloaders.getOrPut(mod) { ArrayList() } += action
    }

    val mods: List<String> @Synchronized get() = reloaders.keys.toList()

    @Synchronized
    fun reload(mod: String? = null): List<Pair<String, Result<String?>>> =
        reloaders.filterKeys { mod == null || it == mod }.flatMap { (name, actions) -> actions.map { name to runCatching(it) } }
}
