package kami.libs.discord

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.neoforged.fml.ModList
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

object DiscordText {
    const val FALLBACK = "en_us"
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    fun get(modId: String, language: String, key: String, vararg args: Any): String {
        val template = table(modId, language)[key] ?: table(modId, FALLBACK)[key] ?: return key
        return if (args.isEmpty()) template else runCatching { template.format(*args) }.getOrDefault(template)
    }

    private fun table(modId: String, language: String): Map<String, String> = cache.getOrPut("$modId/$language") {
        runCatching {
            ModList.get().getModFileById(modId)?.file?.findResource("assets", modId, "lang", "$language.json")?.takeIf(Files::exists)?.let { path ->
                (Json.parseToJsonElement(Files.readString(path)) as JsonObject).mapValues { it.value.jsonPrimitive.content }
            }
        }.getOrNull() ?: emptyMap()
    }
}
