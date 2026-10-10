package kami.essentials.skills

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import kami.essentials.KamiEssentials
import kami.libs.claims.ClaimsApi
import net.minecraft.core.BlockPos
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.FluidTags
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.shapes.CollisionContext
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.event.level.BlockDropsEvent
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import java.util.function.Supplier

object LavaWalk {
    private val tracked = HashMap<ResourceKey<Level>, HashMap<Long, Long>>()
    private val attachments = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, KamiEssentials.ID)
    private val persisted = attachments.register("lava", Supplier { AttachmentType.builder(Supplier { LongOpenHashSet() }).serialize(SkillEvents.codec) { it.isNotEmpty() }.build() })

    fun register() {
        attachments.register(MOD_BUS)
        FORGE_BUS.addListener<ServerTickEvent.Post> { tick(it.server) }
        FORGE_BUS.addListener<BlockDropsEvent>(EventPriority.HIGHEST) { e ->
            if (tracked[e.level.dimension()]?.remove(e.pos.asLong()) != null) {
                untrack(e.level, e.pos)
                e.drops.clear()
                e.droppedExperience = 0
                e.isCanceled = true
            }
        }
        FORGE_BUS.addListener<ChunkEvent.Load> { load(it) }
        FORGE_BUS.addListener<ChunkEvent.Unload> { unload(it) }
        FORGE_BUS.addListener<ServerStoppingEvent> { stop(it.server) }
    }

    private fun tick(server: MinecraftServer) {
        val t = server.tickCount
        if (t % 3 == 0) for (p in server.playerList.players) if (!p.isSpectator) walk(p)
        if (t % 5 == 0 && tracked.isNotEmpty()) expire(server)
    }

    private fun walk(p: ServerPlayer) {
        val level = p.serverLevel()
        val below = p.blockPosition().below()
        if (!p.onGround() && !level.getFluidState(below).`is`(FluidTags.LAVA)) return
        if (SkillServer.value(p, Effects.LAVA_WALKER) <= 0) return
        val dim = level.dimension().location().toString()
        val obsidian = Blocks.OBSIDIAN.defaultBlockState()
        for (dx in -2..2) for (dz in -2..2) {
            if (dx * dx + dz * dz > 5) continue
            val pos = below.offset(dx, 0, dz)
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos).`is`(Blocks.LAVA) || !level.getFluidState(pos).isSource) continue
            if (!level.getBlockState(pos.above()).isAir || !level.isUnobstructed(obsidian, pos, CollisionContext.empty())) continue
            if (!ClaimsApi.canBuild(p.uuid, dim, pos.x, pos.y, pos.z)) continue
            level.setBlock(pos, obsidian, 3)
            tracked.getOrPut(level.dimension()) { HashMap() }[pos.asLong()] = level.gameTime + 120 + level.random.nextInt(80)
            level.getChunkAt(pos).let { it.getData(persisted).add(pos.asLong()); it.setUnsaved(true) }
        }
    }

    private fun untrack(level: ServerLevel, pos: BlockPos) {
        if (!level.hasChunkAt(pos)) return
        val chunk = level.getChunkAt(pos)
        if (chunk.getExistingDataOrNull(persisted)?.remove(pos.asLong()) == true) chunk.setUnsaved(true)
    }

    private fun expire(server: MinecraftServer) {
        for ((dim, map) in tracked) {
            val level = server.getLevel(dim) ?: continue
            val now = level.gameTime
            val it = map.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (entry.value > now) continue
                val pos = BlockPos.of(entry.key)
                if (!level.hasChunkAt(pos) || !level.getBlockState(pos).`is`(Blocks.OBSIDIAN)) {
                    it.remove()
                    untrack(level, pos)
                    continue
                }
                if (level.getEntitiesOfClass(Player::class.java, AABB(pos.x.toDouble(), pos.y + 1.0, pos.z.toDouble(), pos.x + 1.0, pos.y + 1.5, pos.z + 1.0)).isNotEmpty()) {
                    entry.setValue(now + 20 + level.random.nextInt(20))
                    continue
                }
                level.setBlock(pos, Blocks.LAVA.defaultBlockState(), 3)
                it.remove()
                untrack(level, pos)
            }
        }
    }

    private fun load(e: ChunkEvent.Load) {
        if (e.level.isClientSide) return
        val chunk = e.chunk
        val keys = chunk.getExistingDataOrNull(persisted)?.toLongArray() ?: return
        revert(chunk, keys.asList())
    }

    private fun unload(e: ChunkEvent.Unload) {
        val chunk = e.chunk as? LevelChunk ?: return
        val level = chunk.level as? ServerLevel ?: return
        val map = tracked[level.dimension()]
        val keys = map?.keys?.filter { BlockPos.getX(it) shr 4 == chunk.pos.x && BlockPos.getZ(it) shr 4 == chunk.pos.z }.orEmpty()
        map?.keys?.removeAll(keys.toSet())
        revert(chunk, keys + (chunk.getExistingDataOrNull(persisted)?.toLongArray()?.asList() ?: emptyList()))
    }

    private fun revert(chunk: ChunkAccess, keys: Collection<Long>) {
        var changed = false
        for (key in keys) {
            val pos = BlockPos.of(key)
            val section = chunk.getSection(chunk.getSectionIndex(pos.y))
            if (!section.getBlockState(pos.x and 15, pos.y and 15, pos.z and 15).`is`(Blocks.OBSIDIAN)) continue
            section.setBlockState(pos.x and 15, pos.y and 15, pos.z and 15, Blocks.LAVA.defaultBlockState())
            changed = true
        }
        chunk.getExistingDataOrNull(persisted)?.takeIf { it.isNotEmpty() }?.let { it.clear(); changed = true }
        if (changed) chunk.setUnsaved(true)
    }

    private fun stop(server: MinecraftServer) {
        for ((dim, map) in tracked) {
            val level: ServerLevel = server.getLevel(dim) ?: continue
            for (key in map.keys) {
                val pos = BlockPos.of(key)
                if (level.hasChunkAt(pos) && level.getBlockState(pos).`is`(Blocks.OBSIDIAN)) level.setBlock(pos, Blocks.LAVA.defaultBlockState(), 2)
                untrack(level, pos)
            }
        }
        tracked.clear()
    }
}
