package kami.libs.discord

import kami.libs.config.Configs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.security.SecureRandom
import java.util.UUID

internal const val MINUTE_MS = 60_000L

class Pending(val uuid: UUID, val name: String, val expiresAt: Long)

@Serializable
private class StoredCode(val code: String, val uuid: String, val name: String, val expiresAt: Long)

object Codes {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 8
    private const val MAX = 500
    private val random = SecureRandom()
    private val json = Configs.json()
    private val path by lazy { FMLPaths.CONFIGDIR.get().resolve("kami-discord-codes.json") }
    private val byCode by lazy { load() }
    private val failures = HashMap<Long, ArrayDeque<Long>>()

    @Synchronized
    fun issue(uuid: UUID, name: String, ttlMinutes: Int): String {
        val now = System.currentTimeMillis()
        byCode.values.removeAll { it.expiresAt <= now }
        val expires = now + ttlMinutes * MINUTE_MS
        byCode.entries.firstOrNull { it.value.uuid == uuid }?.let {
            val fresh = it.value.expiresAt - now > ttlMinutes * MINUTE_MS / 2
            it.setValue(Pending(uuid, name, expires))
            if (!fresh) save()
            return it.key
        }
        evict()
        var code: String
        do code = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }) while (code in byCode)
        byCode[code] = Pending(uuid, name, expires)
        save()
        return code
    }

    @Synchronized
    fun consume(code: String): Pending? {
        val pending = byCode.remove(normal(code)) ?: return null
        save()
        return pending.takeIf { it.expiresAt > System.currentTimeMillis() }
    }

    @Synchronized
    fun restore(code: String, pending: Pending) {
        evict()
        byCode[normal(code)] = pending
        save()
    }

    @Synchronized
    fun reload() {
        val fresh = load()
        byCode.clear()
        byCode.putAll(fresh)
    }

    private fun evict() {
        while (byCode.size >= MAX) byCode.remove(byCode.minBy { it.value.expiresAt }.key)
    }

    private fun normal(code: String) = code.filter(Char::isLetterOrDigit).uppercase()

    private fun load(): HashMap<String, Pending> {
        val map = HashMap<String, Pending>()
        runCatching {
            if (Files.exists(path)) json.decodeFromString<List<StoredCode>>(Files.readString(path))
                .filter { it.expiresAt > System.currentTimeMillis() }
                .take(MAX)
                .forEach { s -> runCatching { map[s.code] = Pending(UUID.fromString(s.uuid), s.name, s.expiresAt) } }
        }
        return map
    }

    private fun save() {
        runCatching {
            atomicWrite(path, json.encodeToString(byCode.map { (code, p) -> StoredCode(code, p.uuid.toString(), p.name, p.expiresAt) }))
        }
    }

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
