package kami.libs.discord

import java.security.SecureRandom
import java.util.UUID

class Pending(val uuid: UUID, val name: String, val expiresAt: Long)

object Codes {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 8
    private val random = SecureRandom()
    private val byCode = HashMap<String, Pending>()
    private val byUuid = HashMap<UUID, String>()
    private val failures = HashMap<Long, ArrayDeque<Long>>()

    @Synchronized
    fun issue(uuid: UUID, name: String, ttlMinutes: Int): String {
        val now = System.currentTimeMillis()
        byCode.values.removeAll { it.expiresAt <= now }
        byUuid.values.retainAll(byCode.keys)
        byUuid[uuid]?.let { return it }
        var code: String
        do code = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }) while (code in byCode)
        byCode[code] = Pending(uuid, name, now + ttlMinutes * 60_000L)
        byUuid[uuid] = code
        return code
    }

    @Synchronized
    fun consume(code: String): Pending? {
        val pending = byCode.remove(code.trim().uppercase()) ?: return null
        byUuid.remove(pending.uuid)
        return pending.takeIf { it.expiresAt > System.currentTimeMillis() }
    }

    @Synchronized
    fun limited(userId: Long, attempts: Int, windowMinutes: Int): Boolean {
        val queue = failures[userId] ?: return false
        val cutoff = System.currentTimeMillis() - windowMinutes * 60_000L
        while (queue.isNotEmpty() && queue.first() < cutoff) queue.removeFirst()
        if (queue.isEmpty()) failures.remove(userId)
        return queue.size >= attempts
    }

    @Synchronized
    fun failed(userId: Long) {
        failures.getOrPut(userId) { ArrayDeque() }.addLast(System.currentTimeMillis())
    }
}
