package kami.essentials.net

import kami.essentials.Config
import kami.essentials.Flag
import kami.essentials.KamiEssentials
import kami.essentials.Perms
import kami.essentials.Store
import kami.essentials.chat.Talk
import kami.essentials.client.ClientEssentials
import kami.essentials.discord.Gate
import kami.essentials.display.Sidebar
import kami.essentials.vanish.Vanish
import kami.libs.admin.Subject
import kami.libs.discord.Links
import kami.libs.economy.Account
import kami.libs.log.Log
import kami.libs.net.ActPayload
import kami.libs.net.Packets
import kami.libs.net.SnapshotPayload
import kami.libs.net.TickCooldown
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import java.util.UUID

private val packets = Packets.forMod(KamiEssentials.ID)
private val actChannel = ActPayload.channel(packets, maxArgs = 3, maxArg = 36, withCountry = false)
private val snapshotChannel = SnapshotPayload.channel(packets, maxBytes = 131072)

@Serializable
class Snap(val channel: String, val channels: List<String>, val sidebar: Boolean?, val trades: Boolean?, val dmSound: Boolean, val invisible: Boolean?, val discordOn: Boolean = false, val discord: String? = null, val discordPing: Boolean = true, val account: Account? = null, val viewing: String? = null)

object Net {
    private class Pending(var subject: String?, val sets: LinkedHashMap<String, String> = LinkedHashMap())

    private const val MAX_SETS = 8
    private val log = Log.of("essentials")
    private val cooldown = TickCooldown(4)
    private val pending = HashMap<UUID, Pending>()

    val actType get() = actChannel.type

    fun act(name: String, vararg args: String) = actChannel(name, args.toList())

    fun register(e: RegisterPayloadHandlersEvent) {
        val r = e.registrar("1").optional()
        r.playToServer(actChannel.type, actChannel.codec) { a, ctx -> (ctx.player() as? ServerPlayer)?.let { handle(it, a) } }
        r.playToClient(snapshotChannel.type, snapshotChannel.codec) { s, _ -> ClientEssentials.receive(s) }
        FORGE_BUS.addListener<ServerTickEvent.Post> { tick(it.server) }
    }

    fun forget(p: ServerPlayer) {
        cooldown.forget(p.uuid)
        pending.remove(p.uuid)
    }

    private fun handle(p: ServerPlayer, a: ActPayload) {
        val set = a.name == "set"
        val subject = a.args.getOrNull(if (set) 2 else 0)
        val q = pending.getOrPut(p.uuid) { Pending(subject) }
        q.subject = subject
        val key = a.args.getOrNull(0).orEmpty()
        if (set && (key in q.sets || q.sets.size < MAX_SETS) && runCatching { Subject.requireSelf(p, subject) }.isSuccess) q.sets[key] = a.args.getOrNull(1).orEmpty()
        flush(p)
    }

    private fun flush(p: ServerPlayer) {
        if (!cooldown.ready(p.uuid, p.server.tickCount)) return
        val q = pending.remove(p.uuid) ?: return
        q.sets.forEach { (k, v) -> set(p, k, v) }
        PacketDistributor.sendToPlayer(p, snapshotChannel(Json.encodeToString(snap(p, Subject.resolve(p, q.subject), !q.subject.isNullOrBlank()))))
    }

    private fun tick(server: MinecraftServer) {
        if (pending.isEmpty()) return
        pending.keys.toList().forEach { id ->
            val p = server.playerList.getPlayer(id)
            if (p == null) pending.remove(id)
            else runCatching { flush(p) }.onFailure { log.warn("personal snapshot for {} failed: {}", p.gameProfile.name, it.message) }
        }
    }

    private fun set(p: ServerPlayer, key: String, value: String) {
        val on = value == "on"
        when (key) {
            "channel" -> Talk.channel(p, value)
            "sidebar" -> if (Config.s.sidebar && Perms.has(p, Perms.SCOREBOARD)) Sidebar.set(p, on)
            "trades" -> if (Perms.has(p, Perms.TRADE)) Store[Flag.NO_TRADES, p.uuid] = !on
            "dm_sound" -> Store[Flag.QUIET_DM, p.uuid] = !on
            "discord_ping" -> Store[Flag.QUIET_DISCORD, p.uuid] = !on
            "discord_unlink" -> if (Config.s.discord.enabled) Gate.unlink(p.uuid)
            "invisible" -> if (Perms.has(p, Perms.INVIS) && Vanish.active(p) != on) Vanish.toggle(p, null)
        }
    }

    private fun snap(p: ServerPlayer, id: UUID, viewing: Boolean) = Snap(
        Talk.channel(p), Talk.channels(p),
        Sidebar.enabled(p).takeIf { Config.s.sidebar && Perms.has(p, Perms.SCOREBOARD) },
        (!Store[Flag.NO_TRADES, p.uuid]).takeIf { Perms.has(p, Perms.TRADE) },
        !Store[Flag.QUIET_DM, p.uuid],
        Vanish.active(p).takeIf { Perms.has(p, Perms.INVIS) },
        Config.s.discord.enabled, Links.get(p.uuid)?.discordName, !Store[Flag.QUIET_DISCORD, p.uuid], account(p.server, id),
        id.toString().takeIf { viewing }
    )
}
