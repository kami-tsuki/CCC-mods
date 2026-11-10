package kami.libs.discord

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.neoforged.fml.ModList
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object DiscordText {
    const val FALLBACK = "en_us"
    private val cache = ConcurrentHashMap<String, Map<String, String>>()

    fun get(modId: String, language: String, key: String, vararg args: Any): String {
        val template = table(modId, language)[key] ?: table(modId, FALLBACK)[key] ?: return key
        return if (args.isEmpty()) template else runCatching { String.format(Locale.ROOT, template, *args) }.getOrDefault(template)
    }

    fun phrase(p: kami.libs.text.Phrase, language: String = FALLBACK): String {
        val out = when {
            p.key.isEmpty() -> p.text
            p.fallback != null && lookup(p.key, language) == null -> phrase(p.fallback, language)
            p.text.isNotEmpty() -> lookup(p.key, language) ?: p.text
            else -> get(p.key.substringBefore('.'), language, p.key, *p.args.map { phrase(it, language) }.toTypedArray())
        }
        return if (p.value) Templates.code(out) else out
    }

    private fun lookup(key: String, language: String) = key.substringBefore('.').let { mod -> table(mod, language)[key] ?: table(mod, FALLBACK)[key] }

    private fun table(modId: String, language: String): Map<String, String> = cache.getOrPut("$modId/$language") {
        runCatching {
            ModList.get().getModFileById(modId)?.file?.findResource("assets", modId, "lang", "$language.json")?.takeIf(Files::exists)?.let { path ->
                (Json.parseToJsonElement(Files.readString(path)) as JsonObject).mapValues { it.value.jsonPrimitive.content }
            }
        }.getOrNull() ?: emptyMap()
    }
}
