package kami.claims.net

import kami.claims.*
import kami.claims.service.Fail
import kami.claims.service.Service
import kami.claims.service.View
import kami.claims.world.Effects
import kami.libs.net.Packets

import kami.claims.client.ClientHooks
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID

private fun id(path: String) = Packets.id(KamiClaims.ID, path)

private fun <T : CustomPacketPayload> codec(write: (FriendlyByteBuf, T) -> Unit, read: (FriendlyByteBuf) -> T) =
    Packets.codec(write, read)

private fun FriendlyByteBuf.count(max: Int) = readVarInt().also { require(it in 0..max) { "list too long" } }

class Act(val name: String, val args: List<String>, val asCountry: String = "") : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val MAX_ARGS = 6
        val TYPE = CustomPacketPayload.Type<Act>(id("act"))
        val CODEC = codec<Act>(
            { b, v ->
                b.writeUtf(v.name, 32)
                b.writeVarInt(v.args.size)
                v.args.forEach { b.writeUtf(it, 64) }
                b.writeUtf(v.asCountry, 24)
            },
            { b ->
                val name = b.readUtf(32)
                val args = List(b.count(MAX_ARGS)) { b.readUtf(64) }
                Act(name, args, b.readUtf(24))
            }
        )
    }
}

class Snapshot(val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<Snapshot>(id("snapshot"))
        val CODEC = codec<Snapshot>({ b, v -> b.writeUtf(v.json, 262_144) }, { b -> Snapshot(b.readUtf(262_144)) })
    }
}

class ClaimsPacket(val data: View.Payload) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ClaimsPacket>(id("claims"))
        val CODEC = codec<ClaimsPacket>(
            { b, v ->
                val d = v.data
                b.writeVarInt(d.rev)
                b.writeVarInt(d.dims.size)
                d.dims.forEach { b.writeUtf(it, 64) }
                b.writeVarInt(d.types.size)
                d.types.forEach { b.writeUtf(it, 32) }
                b.writeVarInt(d.countries.size)
                d.countries.forEach { b.writeUtf(it.name, 64); b.writeInt(it.color); b.writeByte(it.relation) }
                b.writeVarInt(d.entries.size)
                d.entries.forEach { b.writeByte(it.dim); b.writeInt(it.x); b.writeInt(it.z); b.writeVarInt(it.country); b.writeByte(it.type); b.writeByte(it.flags) }
            },
            { b ->
                val rev = b.readVarInt()
                val dims = List(b.count(16)) { b.readUtf(64) }
                val types = List(b.count(64)) { b.readUtf(32) }
                val countries = List(b.count(8192)) { View.CountryView(b.readUtf(64), b.readInt(), b.readByte().toInt()) }
                val entries = List(b.count(400_000)) { View.Entry(b.readByte().toInt(), b.readInt(), b.readInt(), b.readVarInt(), b.readByte().toInt(), b.readByte().toInt() and 0xFF) }
                ClaimsPacket(View.Payload(rev, dims, types, countries, entries))
            }
        )
    }
}

object Net {
    private val lastAct = HashMap<UUID, Int>()
    private val sent = HashMap<UUID, Int>()
    private var lastPush = 0
    private val openPlayers = HashSet<UUID>()

    fun register(e: RegisterPayloadHandlersEvent) {
        val r = e.registrar("1").optional()
        r.playToServer(Act.TYPE, Act.CODEC) { a, ctx -> (ctx.player() as? ServerPlayer)?.let { handle(it, a) } }
        r.playToClient(Snapshot.TYPE, Snapshot.CODEC) { s, _ -> ClientHooks.snapshot(s) }
        r.playToClient(ClaimsPacket.TYPE, ClaimsPacket.CODEC) { c, _ -> ClientHooks.claims(c.data) }
    }

    fun canOpen(p: ServerPlayer) = p.connection.hasChannel(Snapshot.TYPE)

    fun send(p: ServerPlayer, msg: String = "", ok: Boolean = true, open: Boolean = false) {
        if (canOpen(p)) PacketDistributor.sendToPlayer(p, Snapshot(Sync.encode(p, msg, ok, open)))
    }

    fun push(server: MinecraftServer) {
        if (server.tickCount - lastPush < 20) return
        lastPush = server.tickCount
        pushTo(server.playerList.players)
        broadcastOpen(server)
    }

    fun broadcastOpen(server: MinecraftServer, except: UUID? = null) {
        if (openPlayers.isEmpty()) return
        openPlayers.forEach { uuid ->
            if (uuid == except) return@forEach
            server.playerList.getPlayer(uuid)?.let { send(it) }
        }
    }

    private fun pushTo(players: Collection<ServerPlayer>) {
        players.forEach { p ->
            if (sent[p.uuid] == Realm.rev || !p.connection.hasChannel(ClaimsPacket.TYPE)) return@forEach
            sent[p.uuid] = Realm.rev
            PacketDistributor.sendToPlayer(p, ClaimsPacket(View.build(p.stringUUID)))
        }
    }

    fun forget(p: ServerPlayer) {
        lastAct.remove(p.uuid)
        sent.remove(p.uuid)
        openPlayers.remove(p.uuid)
        Sync.forget(p)
    }

    private fun handle(p: ServerPlayer, a: Act) {
        val tick = p.server.tickCount
        if (tick - (lastAct[p.uuid] ?: -100) < Config.s.guiCooldown) return
        lastAct[p.uuid] = tick
        when (a.name) {
            "open" -> {
                openPlayers += p.uuid
                Sync.focus(p, Service.here(p).x, Service.here(p).z)
                send(p, open = true)
            }
            "close" -> openPlayers -= p.uuid
            "chunk" -> {
                Sync.focus(p, a.args.getOrNull(0)?.toIntOrNull() ?: return, a.args.getOrNull(1)?.toIntOrNull() ?: return)
                send(p)
            }
            "view" -> {
                Sync.view(p, a.args.getOrNull(0) ?: "")
                send(p)
            }
            else -> {
                var ok = true
                val msg = try {
                    Service.act(p, a.name, a.args, a.asCountry)
                } catch (e: Fail) {
                    ok = false
                    e.message ?: "Failed."
                } catch (e: Exception) {
                    KamiClaims.LOG.error("Action ${a.name} failed", e)
                    ok = false
                    "Internal error."
                }
                if (ok) {
                    Effects.chime(p, true)
                    pushTo(p.server.playerList.players)
                    broadcastOpen(p.server, except = p.uuid)
                }
                send(p, msg, ok)
            }
        }
    }
}
