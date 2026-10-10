package kami.claims.compat

import kami.claims.world.Sky
import kami.libs.compat.Mods
import kami.libs.compat.Refl
import kami.libs.compat.Soft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.ICancellableEvent

object DynamicTrees {
    fun register() {
        Soft.event(Mods.DYNAMIC_TREES, "com.dtteam.dynamictrees.event.TransitionSaplingToTreeEvent", EventPriority.HIGHEST) { ev ->
            val level = Refl.call(ev, "getLevel") as? Level ?: return@event
            val pos = Refl.call(ev, "getPos") as? BlockPos ?: return@event
            if (!Sky.plant(level, pos, level.getBlockState(pos).block)) (ev as ICancellableEvent).isCanceled = true
        }
    }
}
