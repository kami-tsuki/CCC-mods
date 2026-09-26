package kami.claims.net

import kami.libs.chat.Chat
import kami.claims.*
import kami.claims.service.Fail
import kami.claims.service.NeedsConfirm
import kami.claims.service.Planner
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
import kotlin.math.max
import kotlin.math.min

private fun id(path: String) = Packets.id(KamiClaims.ID, path)

private fun <T : CustomPacketPayload> codec(write: (FriendlyByteBuf, T) -> Unit, read: (FriendlyByteBuf) -> T) =
    Packets.codec(write, read)

private fun FriendlyByteBuf.count(max: Int) = readVarInt().also { require(it in 0..max) { "list too long" } }

class Act(val name: String, val args: List<String>, val asCountry: String = "", val rid: Int = 0) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val MAX_ARGS = 8
        const val MAX_ARG = 4096
        val TYPE = CustomPacketPayload.Type<Act>(id("act"))
        val CODEC = codec<Act>(
            { b, v ->
                b.writeUtf(v.name, 32)
                b.writeVarInt(v.args.size)
                v.args.forEach { b.writeUtf(it, MAX_ARG) }
                b.writeUtf(v.asCountry, 24)
                b.writeVarInt(v.rid)
            },
            { b ->
                val name = b.readUtf(32)
                val args = List(b.count(MAX_ARGS)) { b.readUtf(MAX_ARG) }
                Act(name, args, b.readUtf(24), b.readVarInt())
            }
        )
    }
}

class Snapshot(val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<Snapshot>(id("snapshot"))
        val CODEC = codec<Snapshot>({ b, v -> b.writeUtf(v.json, 1_048_576) }, { b -> Snapshot(b.readUtf(1_048_576)) })
    }
}

class Denied(val action: String, val x: Int, val y: Int, val z: Int, val owner: String, val color: Int, val type: String, val reason: String, val borderDistance: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<Denied>(id("denied"))
        val CODEC = codec<Denied>(
            { b, v ->
                b.writeUtf(v.action, 16); b.writeInt(v.x); b.writeInt(v.y); b.writeInt(v.z)
                b.writeUtf(v.owner, 64); b.writeInt(v.color); b.writeUtf(v.type, 32); b.writeUtf(v.reason, 256); b.writeVarInt(v.borderDistance)
            },
            { b -> Denied(b.readUtf(16), b.readInt(), b.readInt(), b.readInt(), b.readUtf(64), b.readInt(), b.readUtf(32), b.readUtf(256), b.readVarInt()) }
        )
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
                d.countries.forEach { b.writeUtf(it.name, 64); b.writeInt(it.color); b.writeByte(it.relation); b.writeVarInt(it.flag); b.writeInt(it.secondary) }
                b.writeVarInt(d.entries.size)
                d.entries.forEach { b.writeByte(it.dim); b.writeInt(it.x); b.writeInt(it.z); b.writeVarInt(it.country); b.writeByte(it.type); b.writeByte(it.flags) }
                b.writeVarInt(d.reserved.size)
                d.reserved.forEach { b.writeByte(it.dim); b.writeInt(it.x); b.writeInt(it.z) }
            },
            { b ->
                val rev = b.readVarInt()
                val dims = List(b.count(16)) { b.readUtf(64) }
                val types = List(b.count(64)) { b.readUtf(32) }
                val countries = List(b.count(8192)) { View.CountryView(b.readUtf(64), b.readInt(), b.readByte().toInt(), b.readVarInt(), b.readInt()) }
                val entries = List(b.count(400_000)) { View.Entry(b.readByte().toInt(), b.readInt(), b.readInt(), b.readVarInt(), b.readByte().toInt(), b.readByte().toInt() and 0xFF) }
                val reserved = List(b.count(100_000)) { View.Reserved(b.readByte().toInt(), b.readInt(), b.readInt()) }
                ClaimsPacket(View.Payload(rev, dims, types, countries, entries, reserved))
            }
        )
    }
}

object Net {
    private val lastAct = HashMap<UUID, Int>()
    private val lastPreview = HashMap<UUID, Int>()
    private val sent = HashMap<UUID, Int>()
    private var lastPush = 0
    private val openPlayers = HashSet<UUID>()

    fun register(e: RegisterPayloadHandlersEvent) {
        val r = e.registrar("2").optional()
        r.playToServer(Act.TYPE, Act.CODEC) { a, ctx -> (ctx.player() as? ServerPlayer)?.let { handle(it, a) } }
        r.playToClient(Snapshot.TYPE, Snapshot.CODEC) { s, _ -> ClientHooks.snapshot(s) }
        r.playToClient(ClaimsPacket.TYPE, ClaimsPacket.CODEC) { c, _ -> ClientHooks.claims(c.data) }
        r.playToClient(Denied.TYPE, Denied.CODEC) { d, _ -> ClientHooks.denied(d) }
    }

    fun canOpen(p: ServerPlayer) = p.connection.hasChannel(Snapshot.TYPE)

    fun send(p: ServerPlayer, msg: String = "", ok: Boolean = true, open: Boolean = false) = send(p, Reply(msg, ok), open)

    fun send(p: ServerPlayer, reply: Reply, open: Boolean = false) {
        if (canOpen(p)) PacketDistributor.sendToPlayer(p, Snapshot(Sync.encode(p, reply, open)))
    }

    fun deny(p: ServerPlayer, d: Denied) = PacketDistributor.sendToPlayer(p, d)

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
        lastPreview.remove(p.uuid)
        sent.remove(p.uuid)
        openPlayers.remove(p.uuid)
        Sync.forget(p)
    }

    private fun cells(p: ServerPlayer, a: List<String>, from: Int): List<Key> {
        val dim = Service.here(p).dim
        if (a.getOrNull(from) == "cells") return a.getOrNull(from + 1).orEmpty().split(',').mapNotNull { pair ->
            val (x, z) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            Key(dim, x.toIntOrNull() ?: return@mapNotNull null, z.toIntOrNull() ?: return@mapNotNull null)
        }.take(Config.s.maxRect)
        val n = a.drop(from).take(4).mapNotNull { it.toIntOrNull() }
        if (n.size < 4) return emptyList()
        val (x1, x2) = min(n[0], n[2]) to max(n[0], n[2])
        val (z1, z2) = min(n[1], n[3]) to max(n[1], n[3])
        if ((x2 - x1 + 1).toLong() * (z2 - z1 + 1) > Config.s.maxRect) return emptyList()
        return (x1..x2).flatMap { x -> (z1..z2).map { z -> Key(dim, x, z) } }
    }

    private fun preview(p: ServerPlayer, a: Act) {
        val tick = p.server.tickCount
        if (tick - (lastPreview[p.uuid] ?: -100) < 2) return
        lastPreview[p.uuid] = tick
        val own = Realm.of(p.stringUUID) ?: return send(p, Reply(rid = a.rid))
        val target = a.asCountry.takeIf { it.isNotBlank() }?.let { Realm.country(it) }?.takeIf { it.parent == own.id } ?: own
        val kind = a.name.removePrefix("preview_")
        val type = a.args.getOrNull(0).orEmpty()
        val keys = cells(p, a.args, if (kind == "unclaim") 0 else 1)
        val plan = when (kind) {
            "claim" -> Planner.claim(target, type, keys)
            "unclaim" -> Planner.unclaim(target, keys)
            else -> Planner.retype(target, type, keys)
        }
        Sync.preview(p, kind, type, plan, "$kind|${a.args.joinToString("|")}")
        send(p, Reply(rid = a.rid))
    }

    private fun handle(p: ServerPlayer, a: Act) {
        when (a.name) {
            "open" -> {
                openPlayers += p.uuid
                Sync.focus(p, Service.here(p).x, Service.here(p).z)
                send(p, Reply(rid = a.rid), open = true)
            }
            "close" -> openPlayers -= p.uuid
            "chunk" -> {
                Sync.focus(p, a.args.getOrNull(0)?.toIntOrNull() ?: return, a.args.getOrNull(1)?.toIntOrNull() ?: return)
                send(p, Reply(rid = a.rid))
            }
            "view" -> {
                Sync.view(p, a.args.getOrNull(0) ?: "")
                send(p, Reply(rid = a.rid))
            }
            "watch" -> {
                Sync.watch(p, a.args)
                send(p, Reply(rid = a.rid))
            }
            "preview_claim", "preview_unclaim", "preview_type" -> preview(p, a)
            else -> perform(p, a)
        }
    }

    private fun perform(p: ServerPlayer, a: Act) {
        val tick = p.server.tickCount
        if (tick - (lastAct[p.uuid] ?: -100) < Config.s.guiCooldown) return send(p, Reply("Slow down a little.", false, a.rid, "COOLDOWN"))
        lastAct[p.uuid] = tick
        val reply = try {
            Reply(Chat.plain(Service.act(p, a.name, a.args, a.asCountry)), true, a.rid)
        } catch (e: Fail) {
            Reply(Chat.plain(e.message ?: "Failed."), false, a.rid, e.reason, e.target)
        } catch (e: NeedsConfirm) {
            Reply(Chat.plain(e.lines.first()), false, a.rid, "CONFIRM")
        } catch (e: Exception) {
            KamiClaims.LOG.error("Action ${a.name} failed", e)
            Reply("Something went wrong on the server.", false, a.rid, "ERROR")
        }
        if (reply.ok) {
            Effects.chime(p, true)
            pushTo(p.server.playerList.players)
            broadcastOpen(p.server, except = p.uuid)
        }
        send(p, reply)
    }
}
