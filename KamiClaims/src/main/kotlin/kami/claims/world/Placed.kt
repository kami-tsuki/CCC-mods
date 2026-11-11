package kami.claims.world

import net.minecraft.core.BlockPos

object Placed {
    fun mark(dim: String, pos: BlockPos) = PlacedLog.mark(dim, pos.asLong())

    fun mark(dim: String, pos: BlockPos, by: String) = PlacedLog.mark(dim, pos.asLong(), by)

    fun takeBy(dim: String, pos: BlockPos, by: String) = PlacedLog.takeBy(dim, pos.asLong(), by)

    fun contains(dim: String, pos: BlockPos) = PlacedLog.contains(dim, pos.asLong())

    fun remove(dim: String, pos: BlockPos) = PlacedLog.remove(dim, pos.asLong())

    fun reset() = PlacedLog.reset()
}
