package kami.libs.ui.style

import kami.libs.KamiLibs
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager

object ThemeResources {
    private const val FOLDER = "kami_theme"
    private const val ATLAS = "$FOLDER/atlas.json"

    fun load(resources: ResourceManager) {
        val defs = ArrayList<ThemeDef>()
        resources.listResources(FOLDER) { it.path.endsWith(".json") && it.path != ATLAS }.forEach { (id, resource) ->
            runCatching {
                val text = resource.openAsReader().use { it.readText() }
                defs += ThemeLoader.parse(text)
                ThemeLoader.unknownKeys(text).takeIf { it.isNotEmpty() }?.let { KamiLibs.LOG.warn("Theme file {} has unknown color tokens, ignored: {}", id, it) }
            }
                .onFailure { KamiLibs.LOG.warn("Theme file {} is broken, skipped: {}", id, it.message) }
        }
        Theme.install(defs).forEach { KamiLibs.LOG.warn("Theme {} is invalid (cycle, unknown base or missing tokens), skipped", it) }
        Palette.atlas = resources.getResource(ResourceLocation.fromNamespaceAndPath(KamiLibs.ID, ATLAS)).map { resource ->
            runCatching { ThemeLoader.parseOverrides(resource.openAsReader().use { it.readText() }) }
                .onFailure { KamiLibs.LOG.warn("Theme overrides are broken, ignored: {}", it.message) }
                .getOrDefault(emptyMap())
        }.orElse(emptyMap())
    }
}
