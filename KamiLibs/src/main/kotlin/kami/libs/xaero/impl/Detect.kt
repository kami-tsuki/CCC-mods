package kami.libs.xaero.impl

import net.neoforged.fml.ModList

object Detect {
    private fun loaded(vararg ids: String) = runCatching { ModList.get()?.let { m -> ids.any(m::isLoaded) } == true }.getOrDefault(false)

    val minimap: Boolean by lazy { loaded("xaerominimap", "xaerominimapfair") }
    val worldMap: Boolean by lazy { loaded("xaeroworldmap") }
    val plus: Boolean by lazy { loaded("xaeroplus") }
    val any: Boolean get() = minimap || worldMap || plus
}
