package kami.libs.xaero.impl

import kami.libs.compat.Mods

object Detect {
    val minimap: Boolean get() = Mods.loaded(Mods.XAERO_MINIMAP) || Mods.loaded(Mods.XAERO_MINIMAP_FAIR)
    val worldMap: Boolean get() = Mods.loaded(Mods.XAERO_WORLD_MAP)
    val plus: Boolean get() = Mods.loaded(Mods.XAERO_PLUS)
    val any: Boolean get() = minimap || worldMap || plus
}
