package kami.libs.jade

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import java.util.concurrent.CopyOnWriteArrayList

object KamiJade {
    internal class Lines<L>(val id: ResourceLocation, val type: Class<out Entity>?, val lines: (L) -> List<Component>)
    internal class Data<W, A>(val id: ResourceLocation, val type: Class<out Entity>?, val wants: (W) -> Boolean, val data: A)

    internal val blocks = CopyOnWriteArrayList<Lines<Look>>()
    internal val entities = CopyOnWriteArrayList<Lines<EntityLook>>()
    internal val blockData = CopyOnWriteArrayList<Data<Look, (ServerPlayer, BlockPos) -> CompoundTag?>>()
    internal val entityData = CopyOnWriteArrayList<Data<EntityLook, (ServerPlayer, Entity) -> CompoundTag?>>()

    fun block(id: ResourceLocation, lines: (Look) -> List<Component>) {
        blocks += Lines(id, null, lines)
    }

    fun entity(id: ResourceLocation, type: Class<out Entity>, lines: (EntityLook) -> List<Component>) {
        entities += Lines(id, type, lines)
    }

    fun blockData(id: ResourceLocation, wants: (Look) -> Boolean, data: (ServerPlayer, BlockPos) -> CompoundTag?) {
        blockData += Data(id, null, wants, data)
    }

    fun entityData(id: ResourceLocation, type: Class<out Entity>, wants: (EntityLook) -> Boolean, data: (ServerPlayer, Entity) -> CompoundTag?) {
        entityData += Data(id, type, wants, data)
    }
}
