package kami.claims.world

import kami.claims.Config
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.bar
import kami.libs.text.Phrase
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.tags.TagKey
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BonemealableBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.neoforged.bus.api.Event
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.ICancellableEvent
import net.neoforged.fml.ModList
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.event.entity.player.BonemealEvent
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent
import net.neoforged.neoforge.event.level.block.CropGrowEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.function.Consumer


object Sky {
    private val glass: TagKey<Block> by lazy { TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("kami_claims", "sky_transparent")) }


    private fun cover(state: BlockState, plant: Block?): Cover = when {
        state.isAir || state.block is LiquidBlock || state.`is`(glass) -> Cover.CLEAR
        plant != null && state.block === plant -> Cover.SELF
        else -> Cover.SOLID
    }

    fun open(level: LevelAccessor, pos: BlockPos, plant: Block?): Boolean {
        if (!Config.s.skyRule) return true
        val world = level as? Level ?: return true
        if (world.isClientSide || world.dimension().location().toString() in Config.s.skyFreeSet) return true
        val from = pos.y + 1
        val top = minOf(world.getHeight(Heightmap.Types.WORLD_SURFACE, pos.x, pos.z), world.maxBuildHeight)
        if (top <= from) return true
        val at = BlockPos.MutableBlockPos(pos.x, from, pos.z)
        return SkyColumn.clear(from, top) { y -> cover(world.getBlockState(at.setY(y)), plant) }
    }

    private fun growable(state: BlockState): Boolean {
        val b = state.block
        return b is BonemealableBlock && b.type == BonemealableBlock.Type.GROWER && !state.canBeReplaced() && !state.`is`(BlockTags.FLOWERS)
    }

    private fun onCrop(e: CropGrowEvent.Pre) {
        if (!open(e.level, e.pos, e.state.block)) e.result = CropGrowEvent.Pre.Result.DO_NOT_GROW
    }

    private fun onTree(e: BlockGrowFeatureEvent) {
        val level = e.level
        if (!open(level, e.pos, level.getBlockState(e.pos).block)) e.isCanceled = true
    }

    private fun onBonemeal(e: BonemealEvent) {
        if (!growable(e.state) || open(e.level, e.pos, e.state.block)) return
        e.setCanceled(true)
        val p = e.player as? ServerPlayer ?: return
        if (p !is FakePlayer) p.bar(Chat.bar(Tone.BAD, Phrase.of("kami_claims.guard.sky").component()))
    }

    @Suppress("UNCHECKED_CAST")
    private fun dynamicTrees() {
        if (!ModList.get().isLoaded("dynamictrees")) return
        val type = runCatching { Class.forName("com.dtteam.dynamictrees.event.TransitionSaplingToTreeEvent").asSubclass(Event::class.java) }.getOrNull() ?: return
        val level = type.getMethod("getLevel")
        val pos = type.getMethod("getPos")
        val species = type.getMethod("getSpecies")
        FORGE_BUS.addListener(EventPriority.HIGHEST, false, type as Class<Event>, Consumer<Event> { e ->
            val l = level.invoke(e) as? Level ?: return@Consumer
            val p = pos.invoke(e) as? BlockPos ?: return@Consumer
            if (!open(l, p, l.getBlockState(p).block)) (e as ICancellableEvent).isCanceled = true
        })
    }

    fun register() {
        FORGE_BUS.addListener<CropGrowEvent.Pre>(EventPriority.HIGHEST) { onCrop(it) }
        FORGE_BUS.addListener<BlockGrowFeatureEvent>(EventPriority.HIGHEST) { onTree(it) }
        FORGE_BUS.addListener<BonemealEvent>(EventPriority.HIGHEST) { onBonemeal(it) }
        dynamicTrees()
    }
}
