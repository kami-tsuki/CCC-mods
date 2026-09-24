package kami.geology.world

import kami.geology.config.Ore
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack

object Prospector {
    /** Region a given tool tier reveals, in block coordinates. Tiers 1-3 are block-radius scans centered on the
     *  player; tiers 4-8 snap to whole chunks so the result never depends on where in the chunk the player stands. */
    data class Region(val x0: Int, val z0: Int, val w: Int, val h: Int)

    private val BLOCK_SIZE = mapOf(1 to 1, 2 to 3, 3 to 5)
    private val CHUNK_SIZE = mapOf(4 to 1, 5 to 3, 6 to 5, 7 to 7, 8 to 9)

    fun region(tier: Int, px: Int, pz: Int, chunkX: Int, chunkZ: Int): Region {
        BLOCK_SIZE[tier]?.let { size ->
            val half = size / 2
            return Region(px - half, pz - half, size, size)
        }
        val chunks = CHUNK_SIZE[tier] ?: 1
        val half = chunks / 2
        return Region((chunkX - half) * 16, (chunkZ - half) * 16, chunks * 16, chunks * 16)
    }

    fun oreOf(world: WorldContext, stack: ItemStack): Ore? {
        if (stack.isEmpty) return null
        val block = (stack.item as? BlockItem)?.block ?: return null
        return world.settings.oreOf(block)
    }
}
