package kami.libs.sophisticated

import kami.libs.compat.Mods
import kami.libs.sophisticated.impl.CoreAdapter
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

object Sophisticated {
    fun guard(block: (Player, BlockPos) -> Boolean, entity: (Player, Entity) -> Boolean) {
        if (Mods.loaded(Mods.SOPHISTICATED_CORE)) CoreAdapter.register(block, entity)
    }
}
