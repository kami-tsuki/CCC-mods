package kami.geology.world

import kami.geology.config.GeneralConfig

object Prospector {
    data class Region(val x0: Int, val z0: Int, val w: Int, val h: Int)

    fun region(general: GeneralConfig, tier: Int, px: Int, pz: Int, chunkX: Int, chunkZ: Int): Region {
        general.scanBlocks[tier.toString()]?.let { size ->
            val half = size / 2
            return Region(px - half, pz - half, size, size)
        }
        val chunks = general.scanChunks[tier.toString()] ?: 1
        val half = chunks / 2
        return Region((chunkX - half) * 16, (chunkZ - half) * 16, chunks * 16, chunks * 16)
    }
}
