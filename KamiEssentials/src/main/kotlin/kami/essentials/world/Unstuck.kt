package kami.essentials.world

import kami.essentials.chat.Talk
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.bar
import kami.libs.chat.duration
import kami.libs.chat.tell
import kami.libs.claims.ClaimsApi
import kami.libs.command.fail
import kami.libs.text.Phrase
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.levelgen.Heightmap
import java.util.UUID

object Unstuck {
    private const val COOLDOWN_MS = 15 * 60_000L
    private const val IDLE_MS = 60_000L
    private const val RADIUS = 4
    private const val SEARCH_CHUNKS = 32
    private const val LAST_USE = "kami_essentials_unstuck"

    private class Idle(val pos: BlockPos, val since: Long, var hinted: Boolean = false)

    private val idle = HashMap<UUID, Idle>()

    fun use(p: ServerPlayer) {
        val left = left(p)
        if (left > 0) fail(Phrase.of("kami_essentials.unstuck.cooldown", duration((left + 59_999) / 60_000 * 60)))
        val level = p.serverLevel()
        val here = ClaimsApi.countryAt(level, p.blockPosition())
        val own = ClaimsApi.countryOf(p.uuid)
        if (here != null && here == own) fail(Phrase.of("kami_essentials.unstuck.own"))
        val target = (if (here != null) outside(level, p, here, own) else up(level, p.blockPosition())) ?: fail(Phrase.of("kami_essentials.unstuck.nowhere"))
        p.stopRiding()
        p.teleportTo(level, target.x + 0.5, target.y.toDouble(), target.z + 0.5, p.yRot, p.xRot)
        p.resetFallDistance()
        saved(p).putLong(LAST_USE, System.currentTimeMillis())
        idle.remove(p.uuid)
        p.tell(Talk.chat.ok(Phrase.of("kami_essentials.unstuck.done")))
    }

    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        val online = server.playerList.players
        idle.keys.retainAll(online.mapTo(HashSet()) { it.uuid })
        online.forEach { p ->
            val pos = p.blockPosition()
            val state = idle[p.uuid]
            if (state == null || state.pos != pos) {
                idle[p.uuid] = Idle(pos, now)
                return@forEach
            }
            if (state.hinted || now - state.since < IDLE_MS || p.isSpectator || left(p) > 0) return@forEach
            state.hinted = true
            if (ClaimsApi.countryAt(p.level(), pos).let { it == null || it != ClaimsApi.countryOf(p.uuid) }) {
                p.bar(Chat.bar(Tone.INFO, Phrase.of("kami_essentials.unstuck.hint", Phrase.value("/unstuck"))))
            }
        }
    }

    private fun saved(p: ServerPlayer) = p.persistentData.getCompound(Player.PERSISTED_NBT_TAG).also { p.persistentData.put(Player.PERSISTED_NBT_TAG, it) }

    private fun left(p: ServerPlayer) = (saved(p).getLong(LAST_USE) + COOLDOWN_MS - System.currentTimeMillis()).coerceAtLeast(0)

    private fun outside(level: ServerLevel, p: ServerPlayer, country: String, own: String?): BlockPos? {
        val dim = level.dimension().location().toString()
        val cx = p.blockX shr 4
        val cz = p.blockZ shr 4
        for (r in 1..SEARCH_CHUNKS) {
            val ring = (-r..r).flatMap { d -> listOf(cx + d to cz - r, cx + d to cz + r, cx - r to cz + d, cx + r to cz + d) }.distinct()
            ring.sortedBy { (x, z) -> (x - cx) * (x - cx) + (z - cz) * (z - cz) }.forEach { (x, z) ->
                val at = ClaimsApi.at(dim, x, z)?.country
                if (at != country && (at == null || at == own)) top(level, (x shl 4) + 8, (z shl 4) + 8, level.minBuildHeight)?.let { return BlockPos((x shl 4) + 8, it, (z shl 4) + 8) }
            }
        }
        return null
    }

    private fun up(level: ServerLevel, from: BlockPos): BlockPos? {
        var best: BlockPos? = null
        for (dx in -RADIUS..RADIUS) for (dz in -RADIUS..RADIUS) {
            val x = from.x + dx
            val z = from.z + dz
            val y = top(level, x, z, from.y) ?: continue
            if (best == null || y > best.y) best = BlockPos(x, y, z)
        }
        return best
    }

    private fun top(level: ServerLevel, x: Int, z: Int, floor: Int): Int? {
        val ceiling = if (level.dimensionType().hasCeiling()) level.minBuildHeight + level.dimensionType().logicalHeight() - 2 else level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
        val pos = BlockPos.MutableBlockPos()
        for (y in ceiling downTo maxOf(floor, level.minBuildHeight + 1)) {
            if (free(level, pos.set(x, y, z)) && free(level, pos.set(x, y + 1, z)) && level.getBlockState(pos.set(x, y - 1, z)).blocksMotion()) return y
        }
        return null
    }

    private fun free(level: ServerLevel, pos: BlockPos) = level.getBlockState(pos).let { !it.blocksMotion() && it.fluidState.isEmpty }
}
