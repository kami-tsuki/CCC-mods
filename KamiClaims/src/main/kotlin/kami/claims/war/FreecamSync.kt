package kami.claims.war

import kami.claims.net.FreecamPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

object FreecamSync {
    private const val REFRESH = 200

    private class Sent(val state: Int, val tick: Int)

    private val last = HashMap<UUID, Sent>()

    fun tick(server: MinecraftServer) {
        val t = server.tickCount
        server.playerList.players.forEach { p ->
            if (p.connection?.hasChannel(FreecamPacket.TYPE) != true) return@forEach
            val state = FreecamRule.state(p)
            val prev = last[p.uuid]
            if (prev != null && prev.state == state && t - prev.tick in 0 until REFRESH) return@forEach
            last[p.uuid] = Sent(state, t)
            PacketDistributor.sendToPlayer(p, FreecamPacket(state))
        }
    }

    fun forget(p: ServerPlayer) {
        last.remove(p.uuid)
    }

    fun reset() = last.clear()
}
