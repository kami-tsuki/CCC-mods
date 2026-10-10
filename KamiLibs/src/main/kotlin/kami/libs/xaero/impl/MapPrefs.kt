package kami.libs.xaero.impl

import kami.libs.KamiLibs
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object MapPrefs {
    @Volatile
    var path: Path? = null

    @Volatile
    private var values: Map<String, String>? = null

    private val serializer = MapSerializer(String.serializer(), String.serializer())

    private fun file(): Path = path ?: FMLPaths.CONFIGDIR.get().resolve("kami/libs/maps.json")

    private fun load(): Map<String, String> = values ?: synchronized(this) {
        values ?: read().also { values = it }
    }

    private fun read(): Map<String, String> {
        val f = file()
        return runCatching { if (Files.exists(f)) Json.decodeFromString(serializer, Files.readString(f)) else emptyMap() }.getOrElse {
            runCatching { Files.copy(f, f.resolveSibling("${f.fileName}.bak"), StandardCopyOption.REPLACE_EXISTING) }
            KamiLibs.LOG.warn("Unreadable map settings {}, kept a .bak copy: {}", f, it.toString())
            emptyMap()
        }
    }

    fun get(id: String): String? = load()[id]

    @Synchronized
    fun set(id: String, value: String) {
        val next = HashMap(load())
        next[id] = value
        values = next
        runCatching {
            val f = file()
            Files.createDirectories(f.parent)
            Files.writeString(f, Json.encodeToString(serializer, next.toSortedMap()))
        }
    }

    @Synchronized
    fun reset() {
        values = null
    }
}
