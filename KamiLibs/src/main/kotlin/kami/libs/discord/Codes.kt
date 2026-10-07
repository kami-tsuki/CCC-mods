package kami.libs.discord

import kami.libs.db.Db
import kami.libs.log.Log
import kami.libs.stats.Stats
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.UUID

internal const val MINUTE_MS = 60_000L

class Pending(val uuid: UUID, val name: String, val expiresAt: Long)

@Serializable
internal class StoredCode(val code: String, val uuid: String, val name: String, val expiresAt: Long)

object Codes {
    private val log = Log.of("library")
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 8
    private const val MAX = 500
    private const val UPSERT = "INSERT OR REPLACE INTO libs_discord_code (code, uuid, name, expires_at) VALUES (?, ?, ?, ?)"
    private val random = SecureRandom()
    private val byCode = HashMap<String, Pending>()
    private val failures = HashMap<Long, ArrayDeque<Long>>()

    @Synchronized
    fun load() {
        byCode.clear()
        val now = System.currentTimeMillis()
        Db.read { query("SELECT code, uuid, name, expires_at FROM libs_discord_code WHERE expires_at > ? LIMIT ?", now, MAX) { StoredCode(it.string("code"), it.string("uuid"), it.string("name"), it.long("expires_at")) } }
            .forEach { s -> runCatching { byCode[s.code] = Pending(UUID.fromString(s.uuid), s.name, s.expiresAt) } }
        Db.write { exec("DELETE FROM libs_discord_code WHERE expires_at <= ?", now) }
    }

    @Synchronized
    fun issue(uuid: UUID, name: String, ttlMinutes: Int): String {
        val now = System.currentTimeMillis()
        if (byCode.values.removeAll { it.expiresAt <= now }) Db.write { exec("DELETE FROM libs_discord_code WHERE expires_at <= ?", now) }
        val expires = now + ttlMinutes * MINUTE_MS
        byCode.entries.firstOrNull { it.value.uuid == uuid }?.let {
            val fresh = it.value.expiresAt - now > ttlMinutes * MINUTE_MS / 2
            it.setValue(Pending(uuid, name, expires))
            if (!fresh) store(it.key, it.value)
            return it.key
        }
        evict()
        var code: String
        do code = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }) while (code in byCode)
        val pending = Pending(uuid, name, expires)
        byCode[code] = pending
        store(code, pending)
        Stats.event("discord.code", "p:$uuid")
        return code
    }

    @Synchronized
    fun consume(code: String): Pending? {
        val key = normal(code)
        val pending = byCode.remove(key) ?: return null
        runCatching { Db.durable { exec("DELETE FROM libs_discord_code WHERE code = ?", key) } }.onFailure { log.error("Cannot delete the used link code: {}", it.message) }
        return pending.takeIf { it.expiresAt > System.currentTimeMillis() }
    }

    @Synchronized
    fun restore(code: String, pending: Pending) {
        evict()
        val key = normal(code)
        byCode[key] = pending
        store(key, pending)
    }

    private fun store(code: String, p: Pending) = Db.write { exec(UPSERT, code, p.uuid.toString(), p.name, p.expiresAt) }

    private fun evict() {
        while (byCode.size >= MAX) {
            val oldest = byCode.minBy { it.value.expiresAt }.key
            byCode.remove(oldest)
            Db.write { exec("DELETE FROM libs_discord_code WHERE code = ?", oldest) }
        }
    }

    private fun normal(code: String) = code.filter(Char::isLetterOrDigit).uppercase()

    @Synchronized
    fun limited(userId: Long, attempts: Int, windowMinutes: Int): Boolean {
        val queue = failures[userId] ?: return false
        val cutoff = System.currentTimeMillis() - windowMinutes * MINUTE_MS
        while (queue.isNotEmpty() && queue.first() < cutoff) queue.removeFirst()
        if (queue.isEmpty()) failures.remove(userId)
        return queue.size >= attempts
    }

    @Synchronized
    fun failed(userId: Long, windowMinutes: Int) {
        val now = System.currentTimeMillis()
        failures.values.removeAll { it.last() < now - windowMinutes * MINUTE_MS }
        failures.getOrPut(userId) { ArrayDeque() }.addLast(now)
    }
}
