package kami.libs.ui.map

import net.minecraft.client.Minecraft
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.level.LevelEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object TerrainScanner {
    private val queue = LinkedHashSet<Long>()
    private var ticks = 0
    private var ring = 0

    fun init() {
        FORGE_BUS.addListener<ChunkEvent.Load> { e ->
            val level = e.level
            if (level.isClientSide && TerrainCache.enabled) queue += e.chunk.pos.toLong()
        }
        FORGE_BUS.addListener<LevelEvent.Unload> { if (it.level.isClientSide) { queue.clear(); TerrainCache.flush() } }
        FORGE_BUS.addListener<ClientTickEvent.Post> { tick() }
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        if (!TerrainCache.enabled) return
        ticks++
        if (ticks % 100 == 0) rescanAround(player.chunkPosition())
        if (ticks % 600 == 0) TerrainCache.flush(4)
        if (ticks % 12_000 == 0) TerrainCache.trimDisk()
        val dim = level.dimension().location().toString()
        var budget = 6
        val iterator = queue.iterator()
        while (budget > 0 && iterator.hasNext()) {
            val pos = ChunkPos(iterator.next())
            iterator.remove()
            val chunk = level.chunkSource.getChunk(pos.x, pos.z, false) ?: continue
            if (chunk.isEmpty) continue
            TerrainCache.put(dim, pos.x, pos.z, TerrainSampler.sample(level, chunk))
            budget--
        }
    }

    private fun rescanAround(center: ChunkPos) {
        val radius = 4
        val side = radius * 2 + 1
        repeat(6) {
            val i = ring++ % (side * side)
            queue += ChunkPos.asLong(center.x - radius + i % side, center.z - radius + i / side)
        }
    }
}
