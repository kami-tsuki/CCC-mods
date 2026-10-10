package kami.libs.compat

import net.neoforged.fml.ModList
import java.util.concurrent.ConcurrentHashMap

object Mods {
    const val TACZ = "tacz"
    const val DYNAMIC_TREES = "dynamictrees"
    const val JEI = "jei"
    const val SOPHISTICATED_CORE = "sophisticatedcore"
    const val XAERO_MINIMAP = "xaerominimap"
    const val XAERO_MINIMAP_FAIR = "xaerominimapfair"
    const val XAERO_WORLD_MAP = "xaeroworldmap"
    const val XAERO_PLUS = "xaeroplus"
    const val NUMISMATICS = "numismatics"
    const val CREATE = "create"

    private val cache = ConcurrentHashMap<String, Boolean>()

    fun loaded(id: String): Boolean = cache[id] ?: runCatching { ModList.get()?.isLoaded(id) }.getOrNull()?.also { cache[id] = it } ?: false
}
