package kami.claims.war

import kami.claims.Config
import kami.claims.Country
import kami.claims.Rank
import kami.claims.Realm
import kami.claims.net.NameTagsPacket
import kami.claims.net.TagView
import kami.claims.service.View
import kami.libs.player.Presence
import kami.libs.skills.Skills
import kami.libs.stats.Staff
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

object NameTagSync {
    const val RANGE = 72.0
    const val EVERY = 20

    private val last = HashMap<UUID, Map<UUID, TagView>>()

    private class Info(val country: Country?, val warring: Boolean, val veiled: Boolean)

    fun shown(viewerCountry: String?, targetCountry: String?, targetWarring: Boolean, hide: Boolean, veiled: Boolean) =
        !veiled && !(hide && targetWarring && targetCountry != viewerCountry)

    fun tick(server: MinecraftServer) {
        val t = server.tickCount
        val players = server.playerList.players
        val viewers = players.filter { (t + it.id) % EVERY == 0 && it.connection?.hasChannel(NameTagsPacket.TYPE) == true }
        if (viewers.isEmpty()) return
        val on = Config.s.visibility.nametagLines
        val info = HashMap<UUID, Info>()
        viewers.forEach { v ->
            val next = if (on) collect(v, players, info) else emptyMap()
            if (next == last[v.uuid] || (next.isEmpty() && v.uuid !in last)) return@forEach
            if (next.isEmpty()) last.remove(v.uuid) else last[v.uuid] = next
            PacketDistributor.sendToPlayer(v, NameTagsPacket(next))
        }
    }

    private fun collect(v: ServerPlayer, players: List<ServerPlayer>, info: HashMap<UUID, Info>): Map<UUID, TagView> {
        fun of(p: ServerPlayer) = info.getOrPut(p.uuid) {
            val c = Realm.of(p.stringUUID)
            Info(c, c?.let { Visibility.warring(it.id) } ?: false, Presence.hidden(p.uuid) || Staff.veiled(p) || p.isSpectator)
        }
        val hide = Config.s.visibility.hideNametags
        val vc = of(v).country
        return players.asSequence()
            .filter { it !== v && it.level() === v.level() }
            .map { it to it.distanceToSqr(v) }
            .filter { it.second < RANGE * RANGE }
            .filter { (t, _) -> of(t).let { shown(vc?.id, it.country?.id, it.warring, hide, it.veiled) } }
            .sortedBy { it.second }
            .take(NameTagsPacket.CAP)
            .associate { (t, _) -> t.uuid to view(vc, t, of(t).country) }
    }

    private fun view(vc: Country?, t: ServerPlayer, tc: Country?): TagView {
        val sk = Skills.profile(t.uuid)
        val rank = if (tc != null && tc === vc) (tc.members[t.stringUUID]?.rank ?: Rank.CITIZEN).ordinal else -1
        return TagView(t.uuid, tc?.name, tc?.let(View::color) ?: 0, rank, sk?.cls?.ordinal ?: -1, sk?.level ?: 0)
    }

    fun forget(p: ServerPlayer) {
        last.remove(p.uuid)
    }

    fun reset() = last.clear()
}
