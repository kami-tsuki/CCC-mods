package kami.claims.net

import kami.libs.text.Phrase
import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.service.Fail
import kami.claims.service.NeedsConfirm
import kami.claims.service.Oversight
import kami.claims.service.Planner
import kami.claims.service.Service
import kami.claims.service.View
import kami.claims.social.Perms
import kami.claims.world.Effects
import kami.libs.net.Packets

import kami.claims.client.ClientHooks
import kami.libs.admin.AdminFailure
import kami.libs.admin.Subject
import kami.libs.net.ActPayload
import kami.libs.net.SnapshotPayload
import kami.libs.net.TickCooldown
import kami.libs.util.RateLimit
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

private val net = Packets.forMod(KamiClaims.ID)
private fun id(path: String) = net.id(path)
private fun <T : CustomPacketPayload> codec(write: (FriendlyByteBuf, T) -> Unit, read: (FriendlyByteBuf) -> T) =
    net.codec(write, read)

private fun FriendlyByteBuf.count(max: Int) = readVarInt().also { require(it in 0..max) { "list too long" } }

val Act = ActPayload.channel(net, maxArgs = 8, maxArg = 4096, withCountry = true)
val Snapshot = SnapshotPayload.channel(net, maxBytes = 1_048_576)

class Denied(val action: String, val x: Int, val y: Int, val z: Int, val owner: String, val color: Int, val type: String, val reason: String, val borderDistance: Int) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<Denied>(id("denied"))
        val CODEC = codec<Denied>(
            { b, v ->
                b.writeUtf(v.action, 16); b.writeInt(v.x); b.writeInt(v.y); b.writeInt(v.z)
                b.writeUtf(v.owner, 64); b.writeInt(v.color); b.writeUtf(v.type, 32); b.writeUtf(v.reason, 4096); b.writeVarInt(v.borderDistance)
            },
            { b -> Denied(b.readUtf(16), b.readInt(), b.readInt(), b.readInt(), b.readUtf(64), b.readInt(), b.readUtf(32), b.readUtf(4096), b.readVarInt()) }
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
                d.countries.forEach { b.writeUtf(it.name, 64); b.writeInt(it.color); b.writeByte(it.relation); b.writeVarInt(it.flag); b.writeInt(it.secondary); b.writeInt(it.reputation); b.writeDouble(it.fame ?: -1.0) }
                b.writeVarInt(d.entries.size)
                d.entries.forEach { b.writeByte(it.dim); b.writeInt(it.x); b.writeInt(it.z); b.writeVarInt(it.country); b.writeByte(it.type); b.writeVarInt(it.flags) }
                b.writeVarInt(d.reserved.size)
                d.reserved.forEach { b.writeByte(it.dim); b.writeInt(it.x); b.writeInt(it.z) }
                b.writeVarInt(d.war)
            },
            { b ->
                val rev = b.readVarInt()
                val dims = List(b.count(16)) { b.readUtf(64) }
                val types = List(b.count(64)) { b.readUtf(32) }
                val countries = List(b.count(8192)) { View.CountryView(b.readUtf(64), b.readInt(), b.readByte().toInt(), b.readVarInt(), b.readInt(), b.readInt(), b.readDouble().takeIf { it >= 0 }) }
                val entries = List(b.count(400_000)) { View.Entry(b.readByte().toInt(), b.readInt(), b.readInt(), b.readVarInt(), b.readByte().toInt(), b.readVarInt()) }
                val reserved = List(b.count(100_000)) { View.Reserved(b.readByte().toInt(), b.readInt(), b.readInt()) }
                ClaimsPacket(View.Payload(rev, dims, types, countries, entries, reserved, b.readVarInt()))
            }
        )
    }
}

object Net {
    private val READS = setOf("chunk", "view", "watch", "personal", "names")
    private val reads = RateLimit<UUID>(20, 1000L)
    private var lastAct = TickCooldown(0)
    private var lastPreview = TickCooldown(2)
    private val sent = HashMap<UUID, Int>()
    private val claimSig = HashMap<UUID, Int>()
    private val pushQueue = LinkedHashSet<UUID>()
    private val openSig = HashMap<UUID, Pair<Int, Int>>()
    private var lastPush = 0
    private val openPlayers = HashSet<UUID>()
    private val lastSnapshot = HashMap<UUID, Int>()
    private val deferred = HashMap<UUID, ArrayDeque<Deferred>>()
    private const val SNAPSHOT_GAP = 2
    private const val PUSH_PER_TICK = 10
    private const val OPEN_REFRESH = 100
    private val PERSONAL_ACTS = setOf("rent_pay", "rent_pay_all", "plot_release", "plot_trust", "plot_untrust")

    private class Deferred(val kind: String, val rids: MutableList<Int>, var open: Boolean)

    fun reset() {
        lastAct = TickCooldown(0)
        lastPreview = TickCooldown(2)
        sent.clear()
        claimSig.clear()
        pushQueue.clear()
        openSig.clear()
        lastPush = 0
        openPlayers.clear()
        lastSnapshot.clear()
        deferred.clear()
    }

    private fun recentlySent(uuid: UUID, tick: Int) = lastSnapshot[uuid]?.let { tick - it in 0 until SNAPSHOT_GAP } == true

    private fun answer(p: ServerPlayer, kind: String, rid: Int, open: Boolean = false) {
        val queue = deferred[p.uuid]
        if (queue == null && !recentlySent(p.uuid, p.server.tickCount)) return send(p, Reply(rid = rid), open)
        val pending = queue ?: ArrayDeque<Deferred>().also { deferred[p.uuid] = it }
        val older = pending.firstOrNull { it.kind == kind }?.also { pending.remove(it) }
        val rids = older?.rids ?: mutableListOf()
        if (rid != 0) rids += rid
        pending += Deferred(kind, rids, open || older?.open == true)
    }

    private fun drain(server: MinecraftServer) {
        if (deferred.isEmpty()) return
        val tick = server.tickCount
        val entries = deferred.entries.iterator()
        while (entries.hasNext()) {
            val (uuid, queue) = entries.next()
            val p = server.playerList.getPlayer(uuid)
            if (p == null) { entries.remove(); continue }
            if (recentlySent(uuid, tick)) continue
            val next = queue.removeFirst()
            send(p, Reply(rid = next.rids.lastOrNull() ?: 0, extraRids = next.rids.dropLast(1)), next.open)
            if (queue.isEmpty()) entries.remove()
        }
    }

    fun register(e: RegisterPayloadHandlersEvent) {
        val r = e.registrar("3").optional()
        r.playToServer(Act.type, Act.codec) { a, ctx -> (ctx.player() as? ServerPlayer)?.let { handle(it, a) } }
        r.playToClient(Snapshot.type, Snapshot.codec) { s, _ -> ClientHooks.snapshot(s) }
        r.playToClient(ClaimsPacket.TYPE, ClaimsPacket.CODEC) { c, _ -> ClientHooks.claims(c.data) }
        r.playToClient(ResearchDefsPacket.TYPE, ResearchDefsPacket.CODEC) { d, _ -> ClientHooks.researchDefs(d.defs) }
        r.playToClient(ResearchStatePacket.TYPE, ResearchStatePacket.CODEC) { s, _ -> ClientHooks.researchState(s.state) }
        r.playToClient(Denied.TYPE, Denied.CODEC) { d, _ -> ClientHooks.denied(d) }
        r.playToClient(PositionsPacket.TYPE, PositionsPacket.CODEC) { p, _ -> ClientHooks.positions(p.data) }
    }

    fun canOpen(p: ServerPlayer) = p.connection.hasChannel(Snapshot.type)

    fun send(p: ServerPlayer, msg: String = "", ok: Boolean = true, open: Boolean = false) = send(p, Reply(msg, ok), open)

    fun send(p: ServerPlayer, reply: Reply, open: Boolean = false, consume: Boolean = true) {
        if (!canOpen(p)) return
        lastSnapshot[p.uuid] = p.server.tickCount
        openSig[p.uuid] = openSignature(p) to p.server.tickCount
        Snapshot.send(p, Sync.encode(p, reply, open, consume))
    }

    private fun openSignature(p: ServerPlayer) = 31 * (31 * Realm.rev + Bank.funds(p.uuid).hashCode()) + p.server.playerList.playerCount

    private fun refreshOpen(p: ServerPlayer) {
        val last = openSig[p.uuid]
        val tick = p.server.tickCount
        if (last != null && last.first == openSignature(p) && tick - last.second in 0 until OPEN_REFRESH) return
        send(p, Reply(), false, consume = false)
    }

    fun deny(p: ServerPlayer, d: Denied) = PacketDistributor.sendToPlayer(p, d)

    fun push(server: MinecraftServer) {
        ResearchSync.flush(server)
        drain(server)
        if (server.tickCount - lastPush !in 0..19) {
            lastPush = server.tickCount
            server.playerList.players.forEach { if (sent[it.uuid] != Realm.claimRev) pushQueue += it.uuid }
            broadcastOpen(server)
        }
        var left = PUSH_PER_TICK
        val queue = pushQueue.iterator()
        while (left > 0 && queue.hasNext()) {
            val uuid = queue.next()
            queue.remove()
            server.playerList.getPlayer(uuid)?.let { p -> if (pushTo(p)) left-- }
        }
    }

    fun broadcastOpen(server: MinecraftServer, except: UUID? = null) {
        openPlayers.forEach { uuid ->
            if (uuid != except) server.playerList.getPlayer(uuid)?.let { refreshOpen(it) }
        }
    }

    private fun pushTo(p: ServerPlayer): Boolean {
        pushQueue.remove(p.uuid)
        if (sent[p.uuid] == Realm.claimRev || !p.connection.hasChannel(ClaimsPacket.TYPE)) return false
        sent[p.uuid] = Realm.claimRev
        val data = View.build(p.stringUUID, p.hasPermissions(Config.s.visibility.opLevel))
        val sig = View.signature(data)
        if (claimSig.put(p.uuid, sig) != sig) PacketDistributor.sendToPlayer(p, ClaimsPacket(data))
        return true
    }

    fun forget(p: ServerPlayer) {
        lastAct.forget(p.uuid)
        lastPreview.forget(p.uuid)
        reads.forget(p.uuid)
        lastSnapshot.remove(p.uuid)
        deferred.remove(p.uuid)
        sent.remove(p.uuid)
        claimSig.remove(p.uuid)
        pushQueue.remove(p.uuid)
        openSig.remove(p.uuid)
        ResearchSync.forget(p)
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
        if ((x2.toLong() - x1 + 1) * (z2.toLong() - z1 + 1) > Config.s.maxRect) return emptyList()
        return (x1..x2).flatMap { x -> (z1..z2).map { z -> Key(dim, x, z) } }
    }

    private fun preview(p: ServerPlayer, a: ActPayload) {
        if (!Perms.has(p, Perms.USE)) return send(p, Reply(Phrase.of("kami_claims.error.permission").json(), false, a.rid, "DENIED"))
        val tick = p.server.tickCount
        if (!lastPreview.ready(p.uuid, tick)) return answer(p, "preview", a.rid)
        val own = Realm.of(p.stringUUID)
        val target = (if (a.asCountry.isBlank()) own else Realm.country(a.asCountry)?.takeIf { it === own || Oversight.granted(it, p, Cap.CLAIM) })
            ?: return send(p, Reply(rid = a.rid))
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

    private fun handle(p: ServerPlayer, a: ActPayload) {
        if (a.name in READS && !reads.allow(p.uuid)) return answer(p, a.name, a.rid)
        when (a.name) {
            "open" -> {
                openPlayers += p.uuid
                Sync.focus(p, Service.here(p).x, Service.here(p).z)
                answer(p, "open", a.rid, open = true)
            }
            "close" -> close(p)
            "chunk" -> {
                Sync.focus(p, a.args.getOrNull(0)?.toIntOrNull() ?: return, a.args.getOrNull(1)?.toIntOrNull() ?: return)
                answer(p, a.name, a.rid)
            }
            "view" -> {
                Sync.view(p, a.args.getOrNull(0) ?: "")
                answer(p, a.name, a.rid)
            }
            "watch" -> {
                Sync.watch(p, a.args)
                answer(p, a.name, a.rid)
            }
            "personal" -> {
                Sync.subject(p, a.args.getOrNull(0).orEmpty())
                answer(p, a.name, a.rid)
            }
            "names" -> {
                Sync.names(p, a.args.getOrNull(0).orEmpty())
                answer(p, a.name, a.rid)
            }
            "preview_claim", "preview_unclaim", "preview_type" -> preview(p, a)
            else -> perform(p, a)
        }
    }

    private fun close(p: ServerPlayer) {
        openPlayers -= p.uuid
        Sync.view(p, "")
        val queue = deferred[p.uuid] ?: return
        queue.removeAll { it.kind == "open" && it.rids.isEmpty() }
        queue.filter { it.kind == "open" }.forEach { it.open = false }
        if (queue.isEmpty()) deferred.remove(p.uuid)
    }

    private fun perform(p: ServerPlayer, a: ActPayload) {
        if (!Perms.has(p, Perms.USE)) return send(p, Reply(Phrase.of("kami_claims.error.permission").json(), false, a.rid, "DENIED"))
        val tick = p.server.tickCount
        if (!lastAct.ready(p.uuid, tick, Config.s.guiCooldown)) return send(p, Reply(Phrase.of("kami_libs.admin.rate_limited").json(), false, a.rid, "COOLDOWN"))
        val reply = try {
            if (a.name in PERSONAL_ACTS) Subject.requireSelf(p, Sync.subject(p)?.toString())
            Reply(Service.act(p, a.name, a.args, a.asCountry).json(), true, a.rid)
        } catch (e: Fail) {
            Reply(e.phrase.json(), false, a.rid, e.reason, e.target)
        } catch (e: AdminFailure) {
            Reply(Phrase.of(e.key).json(), false, a.rid, "READ_ONLY")
        } catch (e: NeedsConfirm) {
            Reply(e.lines.first().json(), false, a.rid, "CONFIRM")
        } catch (e: Exception) {
            KamiClaims.LOG.error("Action ${a.name} failed", e)
            Reply(Phrase.of("kami_claims.error.server").json(), false, a.rid, "ERROR")
        }
        if (reply.ok) {
            Effects.chime(p, true)
            pushTo(p)
            broadcastOpen(p.server, except = p.uuid)
        }
        send(p, reply)
    }
}
