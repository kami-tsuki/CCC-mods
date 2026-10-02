package kami.claims.world

import kami.libs.util.RecentSet
import net.minecraft.core.BlockPos

/** Positions recently filled by players, shared by job crediting (Guard) and research progress (Listeners) to stop place/break farming. */
object Placed {
    private val spots = RecentSet<Pair<String, Long>>(8192)

    fun mark(dim: String, pos: BlockPos) = spots.add(dim to pos.asLong())

    fun contains(dim: String, pos: BlockPos): Boolean {
        val spot = dim to pos.asLong()
        return spots.remove(spot).also { if (it) spots.add(spot) }
    }

    fun remove(dim: String, pos: BlockPos) = spots.remove(dim to pos.asLong())

    fun reset() = spots.clear()
}
