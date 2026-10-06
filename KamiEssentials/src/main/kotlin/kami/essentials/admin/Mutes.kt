package kami.essentials.admin

import kami.essentials.chat.Talk
import kami.libs.chat.duration
import kami.libs.chat.tell
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Mute(val reason: String, val by: String, val since: Long, val until: Long?) {
    fun active(now: Long) = until == null || until > now
}

object Mutes {
    private val mutes = ConcurrentHashMap<UUID, Mute>()

    fun load() = Db.read { load(this) }

    internal fun load(s: Session) {
        mutes.clear()
        s.query("SELECT uuid, reason, \"by\", since, until FROM ess_mute") {
            UUID.fromString(it.string("uuid")) to Mute(it.string("reason"), it.string("by"), it.long("since"), it.longOrNull("until"))
        }.forEach { (id, m) -> mutes[id] = m }
    }

    fun unload() = mutes.clear()

    operator fun get(id: UUID, now: Long = System.currentTimeMillis()): Mute? {
        val m = mutes[id] ?: return null
        if (m.active(now)) return m
        unmute(id)
        return null
    }

    fun active(now: Long = System.currentTimeMillis()): List<Pair<UUID, Mute>> = mutes.keys.mapNotNull { id -> get(id, now)?.let { id to it } }

    fun mute(id: UUID, m: Mute) {
        mutes[id] = m
        Db.write { exec("INSERT OR REPLACE INTO ess_mute (uuid, reason, \"by\", since, until) VALUES (?, ?, ?, ?, ?)", id.toString(), m.reason, m.by, m.since, m.until) }
    }

    fun unmute(id: UUID): Boolean {
        if (mutes.remove(id) == null) return false
        Db.write { exec("DELETE FROM ess_mute WHERE uuid = ?", id.toString()) }
        return true
    }

    fun silenced(p: ServerPlayer): Boolean {
        val m = get(p.uuid) ?: return false
        p.tell(Talk.chat.warn(phrase(m, System.currentTimeMillis())))
        return true
    }

    fun phrase(m: Mute, now: Long): Phrase = m.until?.let { Phrase.of("kami_essentials.admin.muted.timed", Phrase.value(m.reason), duration((it - now) / 1000)) }
        ?: Phrase.of("kami_essentials.admin.muted.perm", Phrase.value(m.reason))
}
