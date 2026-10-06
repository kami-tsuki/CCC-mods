package kami.essentials.admin

import com.mojang.authlib.GameProfile
import kami.essentials.mixin.StoredUserEntryAccess
import kami.libs.admin.Durations
import kami.libs.chat.duration
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.text.Phrase
import net.minecraft.server.MinecraftServer
import net.minecraft.server.players.BanListEntry
import net.minecraft.server.players.IpBanListEntry
import net.minecraft.server.players.UserBanListEntry
import java.util.Date
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Moderation {
    private val links = ConcurrentHashMap<UUID, MutableSet<String>>()

    fun load() = Db.read { load(this) }

    internal fun load(s: Session) {
        links.clear()
        s.query("SELECT uuid, ip FROM ess_ban_ip") { UUID.fromString(it.string("uuid")) to it.string("ip") }
            .forEach { (id, ip) -> links.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(ip) }
    }

    fun unload() = links.clear()

    fun expiry(raw: String, now: Long): Long? = Durations.parse(raw)?.let { now + it.inWholeMilliseconds }

    fun profile(e: UserBanListEntry) = (e as StoredUserEntryAccess).`kami$user`() as? GameProfile

    fun banned(server: MinecraftServer, id: UUID): UserBanListEntry? = server.playerList.bans.get(GameProfile(id, ""))

    fun bans(server: MinecraftServer, now: Long = System.currentTimeMillis()) = server.playerList.bans.entries.filter { live(it, now) }

    fun bannedIps(server: MinecraftServer, id: UUID): List<String> = links[id].orEmpty().filter { server.playerList.ipBans.isBanned(it) }

    fun ban(server: MinecraftServer, profile: GameProfile, by: String, reason: String, until: Long?, ips: Collection<String>): List<String> {
        val now = Date()
        val expires = until?.let { Date(it) }
        server.playerList.bans.add(UserBanListEntry(profile, now, by, expires, reason))
        val added = ips.filter { ip -> server.playerList.ipBans.get(ip)?.let { e -> e.getExpires()?.let { old -> expires == null || old.before(expires) } == true } ?: true }
        added.forEach { server.playerList.ipBans.add(IpBanListEntry(it, now, by, expires, reason)) }
        if (added.isNotEmpty()) {
            links.getOrPut(profile.id) { ConcurrentHashMap.newKeySet() }.addAll(added)
            Db.write { batch("INSERT OR IGNORE INTO ess_ban_ip (uuid, ip) VALUES (?, ?)", added.map { listOf(profile.id.toString(), it) }) }
        }
        val screen = screen(reason, until, now.time).component()
        (listOfNotNull(server.playerList.getPlayer(profile.id)) + ips.flatMap { server.playerList.getPlayersWithAddress(it) }).distinct().forEach { it.connection.disconnect(screen) }
        return added
    }

    fun unban(server: MinecraftServer, id: UUID): List<String>? {
        banned(server, id) ?: return null
        server.playerList.bans.remove(GameProfile(id, ""))
        val linked = links.remove(id).orEmpty().toList()
        if (linked.isNotEmpty()) Db.write { exec("DELETE FROM ess_ban_ip WHERE uuid = ?", id.toString()) }
        val ips = linked.filter { ip -> server.playerList.ipBans.isBanned(ip) && links.none { (other, set) -> ip in set && banned(server, other) != null } }
        ips.forEach { server.playerList.ipBans.remove(it) }
        return ips
    }

    fun kick(server: MinecraftServer, id: UUID, reason: String): Boolean {
        val p = server.playerList.getPlayer(id) ?: return false
        p.connection.disconnect(Phrase.of("kami_essentials.admin.kick.screen", Phrase.value(reason)).component())
        return true
    }

    private fun screen(reason: String, until: Long?, now: Long) = until?.let { Phrase.of("kami_essentials.admin.ban.screen.timed", Phrase.value(reason), duration((it - now) / 1000)) }
        ?: Phrase.of("kami_essentials.admin.ban.screen.perm", Phrase.value(reason))

    private fun live(e: BanListEntry<*>, now: Long) = e.getExpires()?.let { it.time > now } ?: true
}
