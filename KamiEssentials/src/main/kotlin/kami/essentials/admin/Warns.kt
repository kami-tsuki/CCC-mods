package kami.essentials.admin

import kami.essentials.chat.Talk
import kami.libs.chat.tell
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class Revoke(val by: String, val at: Long, val reason: String)

data class Warn(val id: Long, val uuid: UUID, val reason: String, val by: String, val at: Long, val delivered: Boolean = false, val revoke: Revoke? = null)

object Warns {
    private val warns = ConcurrentHashMap<Long, Warn>()
    private val next = AtomicLong()

    fun load() = Db.read { load(this) }

    internal fun load(s: Session) {
        warns.clear()
        s.query("SELECT id, uuid, reason, \"by\", at, delivered, revoked_by, revoked_at, revoke_reason FROM ess_warn") { r ->
            Warn(
                r.long("id"), UUID.fromString(r.string("uuid")), r.string("reason"), r.string("by"), r.long("at"), r.int("delivered") != 0,
                r.stringOrNull("revoked_by")?.let { Revoke(it, r.long("revoked_at"), r.string("revoke_reason")) }
            )
        }.forEach { warns[it.id] = it }
        next.set(warns.keys.maxOrNull() ?: 0)
    }

    fun unload() = warns.clear()

    operator fun get(id: Long): Warn? = warns[id]

    fun all(): List<Warn> = warns.values.sortedByDescending { it.id }

    fun of(id: UUID): List<Warn> = all().filter { it.uuid == id }

    fun active(id: UUID): Int = warns.values.count { it.uuid == id && it.revoke == null }

    fun warn(id: UUID, reason: String, by: String, at: Long, delivered: Boolean): Warn {
        val w = Warn(next.incrementAndGet(), id, reason, by, at, delivered)
        warns[w.id] = w
        Db.durableAsync { exec("INSERT INTO ess_warn (id, uuid, reason, \"by\", at, delivered) VALUES (?, ?, ?, ?, ?, ?)", w.id, id.toString(), reason, by, at, if (delivered) 1 else 0) }
        return w
    }

    fun revoke(warnId: Long, revoke: Revoke): Warn? {
        val w = warns[warnId]?.takeIf { it.revoke == null }?.copy(revoke = revoke) ?: return null
        warns[warnId] = w
        Db.durableAsync { exec("UPDATE ess_warn SET revoked_by = ?, revoked_at = ?, revoke_reason = ? WHERE id = ?", revoke.by, revoke.at, revoke.reason, warnId) }
        return w
    }

    fun deliver(p: ServerPlayer) {
        val pending = warns.values.filter { it.uuid == p.uuid && !it.delivered }.sortedBy { it.id }
        if (pending.isEmpty()) return
        pending.forEach { w ->
            if (w.revoke == null) p.tell(Talk.chat.warn(phrase(w)))
            warns[w.id] = w.copy(delivered = true)
        }
        Db.durableAsync { exec("UPDATE ess_warn SET delivered = 1 WHERE uuid = ? AND delivered = 0", p.uuid.toString()) }
    }

    fun phrase(w: Warn): Phrase = Phrase.of("kami_essentials.admin.warned", Phrase.value(w.reason), Phrase.value(w.by))
}
