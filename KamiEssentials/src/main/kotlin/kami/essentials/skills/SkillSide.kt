package kami.essentials.skills

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

object SkillSide {
    fun value(p: Player, key: String): Double =
        if (p.level().isClientSide) SkillClient.value(key) else (p as? ServerPlayer)?.let { SkillServer.value(it, key) } ?: 0.0
}
