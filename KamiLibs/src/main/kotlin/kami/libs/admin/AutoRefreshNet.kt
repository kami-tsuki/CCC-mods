package kami.libs.admin

import kami.libs.KamiLibs
import kami.libs.db.Db
import kami.libs.net.Packets
import kami.libs.net.SnapshotPayload
import kami.libs.ui.AutoRefresh
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import kotlin.math.min

object AutoRefreshNet {
    private const val KEY = "autorefresh"
    private const val EVERY = 200
    private const val MIN_TPS = 18.0
    private val channel = SnapshotPayload.channel(Packets.forMod(KamiLibs.ID), "auto_refresh", 4)
    private var enabled = true
    private var sent = ""

    fun register(e: RegisterPayloadHandlersEvent) {
        e.registrar("1").optional().playToClient(channel.type, channel.codec) { s, _ -> AutoRefresh.receive(s.json) }
        Admin.act("server.autorefresh", needsReason = false) { ctx, _ ->
            set(ctx.player.server, !enabled)
            Admin.done("autorefresh")
        }
    }

    fun load() {
        enabled = Db.read { one("SELECT value FROM libs_meta WHERE key = ?", KEY) { it.string("value") } } != "0"
        sent = ""
    }

    fun tick(server: MinecraftServer) {
        if (server.tickCount % EVERY != 0) return
        val state = state(server)
        if (state == sent) return
        sent = state
        server.playerList.players.forEach { PacketDistributor.sendToPlayer(it, channel(state)) }
    }

    fun login(p: ServerPlayer) = PacketDistributor.sendToPlayer(p, channel(state(p.server)))

    private fun set(server: MinecraftServer, on: Boolean) {
        enabled = on
        Db.write { exec("INSERT INTO libs_meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", KEY, if (on) "1" else "0") }
        sent = state(server)
        server.playerList.players.forEach { PacketDistributor.sendToPlayer(it, channel(sent)) }
    }

    private fun state(server: MinecraftServer): String {
        val tps = min(20.0, 1000.0 / (server.averageTickTimeNanos / 1_000_000.0).coerceAtLeast(0.001))
        return "${if (enabled && tps >= MIN_TPS) 1 else 0}${if (enabled) 1 else 0}"
    }
}
