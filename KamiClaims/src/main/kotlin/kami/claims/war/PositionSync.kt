package kami.claims.war

import kami.claims.Config
import kami.claims.Realm
import kami.claims.net.PositionsPacket
import kami.libs.stats.Staff
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

object PositionSync {
    private val last = HashMap<UUID, Selection>()
    private val dims = HashMap<ResourceLocation, String>()

    fun tick(server: MinecraftServer) {
        val cfg = Config.s.visibility
        if (!cfg.shareMemberPositions && !cfg.adminSeeAll && !cfg.hideEnemiesOnMinimap) {
            flush(server)
            return
        }
        val players = server.playerList.players
        val t = server.tickCount
        val slice = players.filter { (t + it.id) % cfg.positionIntervalTicks == 0 && it.connection?.hasChannel(PositionsPacket.TYPE) == true }
        if (slice.isEmpty()) return
        val all = players.map(::pos)
        val open = players.indices.filter { !Staff.veiled(players[it]) }.map { all[it] }
        slice.forEach { v -> send(v, if (v.hasPermissions(cfg.opLevel)) all else open) }
    }

    private fun flush(server: MinecraftServer) {
        if (last.isEmpty()) return
        last.keys.forEach { id -> server.playerList.getPlayer(id)?.takeIf { it.connection?.hasChannel(PositionsPacket.TYPE) == true }?.let { PacketDistributor.sendToPlayer(it, PositionsPacket(Selection(emptyList(), emptySet(), false))) } }
        last.clear()
    }

    private fun send(p: ServerPlayer, targets: List<Pos>) {
        val cfg = Config.s.visibility
        val opAll = cfg.adminSeeAll && p.hasPermissions(cfg.opLevel)
        val next = PositionFilter.select(pos(p), opAll, targets, cfg.shareMemberPositions, cfg.hideEnemiesOnMinimap, ::enemies, ::family, cap = PositionFilter.CAP, warring = viewerPos(p))
        val prev = last[p.uuid]
        if (!PositionFilter.changed(prev, next)) return
        if (next.empty) last.remove(p.uuid) else last[p.uuid] = next
        PacketDistributor.sendToPlayer(p, PositionsPacket(next))
    }

    private fun viewerPos(p: ServerPlayer) = Realm.of(p.stringUUID)?.let { Visibility.warring(it.id) } ?: false

    private fun enemies(a: String, b: String) = Wars.enemies(a, b)

    private fun family(own: String, target: String) = Realm.country(target)?.parent == own

    private fun pos(p: ServerPlayer): Pos {
        val dim = p.level().dimension().location()
        return Pos(p.uuid, Realm.of(p.stringUUID)?.id, dims.getOrPut(dim) { dim.toString() }, p.x, p.y, p.z)
    }

    fun forget(p: ServerPlayer) {
        last.remove(p.uuid)
    }

    fun reset() = last.clear()
}
