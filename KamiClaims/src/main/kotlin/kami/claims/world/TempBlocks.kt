package kami.claims.world

import kami.claims.Config
import kami.claims.Realm
import kami.claims.TempBlock
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState

object TempBlocks {
    private val tag: TagKey<Block> by lazy { TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("kami_claims", "temp_blocks")) }

    fun fits(state: BlockState) = state.`is`(tag)

    fun at(dim: String, pos: BlockPos): Boolean {
        val at = pos.asLong()
        return Realm.data.temp.any { it.pos == at && it.dim == dim }
    }

    fun seconds(level: ServerLevel, pos: BlockPos, state: BlockState): Double =
        maxOf(Config.s.tempBlockMinSeconds, state.getDestroySpeed(level, pos) * Config.s.tempBlockSecondsPerHardness)

    fun place(level: ServerLevel, pos: BlockPos, state: BlockState) {
        val dim = level.dimension().location().toString()
        val at = pos.asLong()
        val now = level.gameTime
        Realm.data.temp.removeAll { it.pos == at && it.dim == dim }
        Realm.data.temp += TempBlock(dim, at, now, now + (seconds(level, pos, state) * 20).toLong())
        Realm.dirty = true
    }

    private fun breaker(pos: BlockPos) = -1 - (pos.hashCode() and 0xFFFFFF)

    fun tick(server: MinecraftServer) {
        if (Realm.data.temp.isEmpty()) return
        val gone = Realm.data.temp.removeIf { t ->
            val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.dim))) ?: return@removeIf true
            val pos = BlockPos.of(t.pos)
            if (!level.isLoaded(pos)) return@removeIf false
            val present = fits(level.getBlockState(pos))
            val done = !present || level.gameTime >= t.due
            if (done) {
                level.destroyBlockProgress(breaker(pos), pos, -1)
                if (present) level.destroyBlock(pos, false)
            } else level.destroyBlockProgress(breaker(pos), pos, ((level.gameTime - t.start) * 10 / maxOf(1, t.due - t.start)).toInt().coerceIn(0, 9))
            done
        }
        if (gone) Realm.dirty = true
    }
}
