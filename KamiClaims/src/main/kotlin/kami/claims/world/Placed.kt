package kami.claims.world

import kami.libs.util.RecentSet
import net.minecraft.core.BlockPos

object Placed {
    private const val CAPACITY = 65536
    private val spots = RecentSet<Pair<String, Long>>(CAPACITY)
    private val placers = RecentSet<Triple<String, Long, String>>(CAPACITY)

    fun mark(dim: String, pos: BlockPos) = spots.add(dim to pos.asLong())

    fun mark(dim: String, pos: BlockPos, by: String) {
        mark(dim, pos)
        placers.add(Triple(dim, pos.asLong(), by))
    }

    fun takeBy(dim: String, pos: BlockPos, by: String) = placers.remove(Triple(dim, pos.asLong(), by))

    fun contains(dim: String, pos: BlockPos): Boolean {
        val spot = dim to pos.asLong()
        return spots.remove(spot).also { if (it) spots.add(spot) }
    }

    fun remove(dim: String, pos: BlockPos) = spots.remove(dim to pos.asLong())

    fun reset() {
        spots.clear()
        placers.clear()
    }
}
