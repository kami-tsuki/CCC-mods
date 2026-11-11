package kami.libs.mc

import net.minecraft.core.Registry
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey

object Selectors {
    fun matches(spec: String, id: String, tagged: (String) -> Boolean) = if (spec.startsWith('#')) tagged(spec.drop(1)) else spec == id

    fun glob(pattern: String, text: String): Boolean {
        var p = 0
        var t = 0
        var star = -1
        var resume = 0
        while (t < text.length) {
            when {
                p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t]) -> { p++; t++ }
                p < pattern.length && pattern[p] == '*' -> { star = p++; resume = t }
                star >= 0 -> { p = star + 1; t = ++resume }
                else -> return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }

    fun namespace(spec: String) = spec.removePrefix("#").substringBefore(':', "minecraft")
}

object RegistryTags {
    private const val CACHE = 1024
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, ResourceLocation>()

    private fun parse(tag: String): ResourceLocation? {
        parsed[tag]?.let { return it }
        val location = ResourceLocation.tryParse(tag) ?: return null
        if (parsed.size >= CACHE) parsed.clear()
        parsed[tag] = location
        return location
    }

    fun <T : Any> contains(registry: Registry<T>, tag: String, id: String): Boolean {
        val location = ResourceLocation.tryParse(id) ?: return false
        val key = TagKey.create(registry.key(), parse(tag) ?: return false)
        return registry.getHolder(location).map { it.`is`(key) }.orElse(false)
    }
}
