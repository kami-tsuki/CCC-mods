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
import kami.libs.discord.Links
import kami.libs.net.ActPayload
import kami.libs.net.Packets
import kami.libs.net.SnapshotPayload
import kami.libs.net.TickCooldown
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

private val packets = Packets.forMod(KamiEssentials.ID)
private val actChannel = ActPayload.channel(packets, maxArgs = 2, maxArg = 16, withCountry = false)
private val snapshotChannel = SnapshotPayload.channel(packets, maxBytes = 4096)

@Serializable
class Snap(val channel: String, val channels: List<String>, val sidebar: Boolean?, val trades: Boolean?, val dmSound: Boolean, val invisible: Boolean?, val discordOn: Boolean = false, val discord: String? = null, val discordPing: Boolean = true)

object Net {
    private val cooldown = TickCooldown(4)

    val actType get() = actChannel.type

    fun act(name: String, vararg args: String) = actChannel(name, args.toList())

    fun register(e: RegisterPayloadHandlersEvent) {
        val r = e.registrar("1").optional()
        r.playToServer(actChannel.type, actChannel.codec) { a, ctx -> (ctx.player() as? ServerPlayer)?.let { handle(it, a) } }
        r.playToClient(snapshotChannel.type, snapshotChannel.codec) { s, _ -> ClientEssentials.receive(s) }
    }

    fun forget(p: ServerPlayer) = cooldown.forget(p.uuid)

    private fun handle(p: ServerPlayer, a: ActPayload) {
        if (a.name == "set" && cooldown.ready(p.uuid, p.server.tickCount)) set(p, a.args.getOrNull(0).orEmpty(), a.args.getOrNull(1).orEmpty())
        PacketDistributor.sendToPlayer(p, snapshotChannel(Json.encodeToString(snap(p))))
    }

    private fun set(p: ServerPlayer, key: String, value: String) {
        val on = value == "on"
        when (key) {
            "channel" -> Talk.channel(p, value)
            "sidebar" -> if (Config.s.sidebar && Perms.has(p, Perms.SCOREBOARD)) Sidebar.set(p, on)
            "trades" -> if (Perms.has(p, Perms.TRADE)) Store[Flag.NO_TRADES, p.uuid] = !on
            "dm_sound" -> Store[Flag.QUIET_DM, p.uuid] = !on
            "discord_ping" -> Store[Flag.QUIET_DISCORD, p.uuid] = !on
            "discord_unlink" -> Gate.unlink(p.uuid, "unlinked")
            "invisible" -> if (Perms.has(p, Perms.INVIS) && Vanish.active(p) != on) Vanish.toggle(p, null)
        }
    }

    private fun snap(p: ServerPlayer) = Snap(
        Talk.channel(p), Talk.channels(p),
        Sidebar.enabled(p).takeIf { Config.s.sidebar && Perms.has(p, Perms.SCOREBOARD) },
        (!Store[Flag.NO_TRADES, p.uuid]).takeIf { Perms.has(p, Perms.TRADE) },
        !Store[Flag.QUIET_DM, p.uuid],
        Vanish.active(p).takeIf { Perms.has(p, Perms.INVIS) },
        Config.s.discord.enabled, Links.get(p.uuid)?.discordName, !Store[Flag.QUIET_DISCORD, p.uuid]
    )
}
