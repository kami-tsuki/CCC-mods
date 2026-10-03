package kami.claims.world

import kami.claims.Config
import kami.claims.Data
import kami.claims.Key
import kami.claims.Realm
import kami.claims.TempBlock
import kami.libs.log.Log
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
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
    private val index = HashMap<Pair<String, Long>, TempBlock>()
    private var indexed: Data? = null

    private fun live(): HashMap<Pair<String, Long>, TempBlock> {
        if (indexed !== Realm.data) {
            index.clear()
            Realm.data.temp.associateByTo(index) { it.dim to it.pos }
            Realm.data.temp.removeAll { index[it.dim to it.pos] !== it }
            indexed = Realm.data
        }
        return index
    }

    fun fits(state: BlockState) = state.`is`(tag)

    fun at(dim: String, pos: BlockPos) = (dim to pos.asLong()) in live()

    fun seconds(level: ServerLevel, pos: BlockPos, state: BlockState): Double =
        maxOf(Config.s.tempBlockMinSeconds, state.getDestroySpeed(level, pos) * Config.s.tempBlockSecondsPerHardness)

    fun place(level: ServerLevel, pos: BlockPos, state: BlockState, replaced: BlockState): Boolean {
        val spot = level.dimension().location().toString() to pos.asLong()
        val old = live()[spot]
        if (old == null && index.size >= Config.s.tempBlockLimit) return false
        old?.let { Realm.data.temp.remove(it) }
        val fluid = old?.fluid ?: replaced.fluidState.takeIf { it.isSource }?.let { BuiltInRegistries.FLUID.getKey(it.type).toString() } ?: ""
        val now = level.gameTime
        val entry = TempBlock(spot.first, spot.second, now, now + (seconds(level, pos, state) * 20).toLong(), fluid)
        Realm.data.temp += entry
        index[spot] = entry
        Realm.dirty = true
        return true
    }

    private fun breaker(pos: BlockPos) = -1 - (pos.hashCode() and 0xFFFFFF)

    fun tick(server: MinecraftServer) {
        if (Realm.data.temp.isEmpty()) return
        val done = HashSet<TempBlock>()
        for (t in Realm.data.temp.toList()) {
            if (runCatching { step(server, t) }.getOrElse { Log.of("claims").warn("Temp block at {} {} dropped: {}", t.dim, BlockPos.of(t.pos), it.toString()); true }) done += t
        }
        if (done.isEmpty()) return
        Realm.data.temp.removeAll(done)
        done.forEach { live().remove(it.dim to it.pos) }
        Realm.dirty = true
    }

    private fun step(server: MinecraftServer, t: TempBlock): Boolean {
        val id = ResourceLocation.tryParse(t.dim) ?: return true
        val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, id)) ?: return true
        val pos = BlockPos.of(t.pos)
        if (!level.isLoaded(pos)) return false
        val present = fits(level.getBlockState(pos))
        val claimed = Realm.index[Key(t.dim, pos.x shr 4, pos.z shr 4)] != null
        if (present && !claimed && level.gameTime < t.due) {
            val stage = ((level.gameTime - t.start) * 10 / maxOf(1, t.due - t.start)).toInt().coerceIn(0, 9)
            if (stage != t.stage) {
                t.stage = stage
                level.destroyBlockProgress(breaker(pos), pos, stage)
            }
            return false
        }
        level.destroyBlockProgress(breaker(pos), pos, -1)
        if (claimed) return true
        if (present) level.destroyBlock(pos, false)
        if (t.fluid.isNotEmpty() && level.getBlockState(pos).isAir) ResourceLocation.tryParse(t.fluid)?.let { level.setBlockAndUpdate(pos, BuiltInRegistries.FLUID.get(it).defaultFluidState().createLegacyBlock()) }
        return true
    }
}
