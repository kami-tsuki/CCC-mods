package kami.libs.jade

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

class Look(val level: Level, val pos: BlockPos, val state: BlockState, val player: Player, val data: CompoundTag, val details: Boolean)

class EntityLook(val entity: Entity, val player: Player, val data: CompoundTag, val details: Boolean)
