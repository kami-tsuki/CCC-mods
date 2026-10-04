package kami.libs.discord

import kami.libs.config.Configs
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.UUID

class Pending(val uuid: UUID, val name: String, val expiresAt: Long)

@Serializable
private class StoredCode(val code: String, val uuid: String, val name: String, val expiresAt: Long)

object Codes {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 8
    private val random = SecureRandom()
    private val json = Configs.json()
    private val path by lazy { FMLPaths.CONFIGDIR.get().resolve("kami-discord-codes.json") }
    private val byCode by lazy { load() }
    private val failures = HashMap<Long, ArrayDeque<Long>>()

    @Synchronized
    fun issue(uuid: UUID, name: String, ttlMinutes: Int): String {
        val now = System.currentTimeMillis()
        byCode.values.removeAll { it.expiresAt <= now }
        byCode.entries.firstOrNull { it.value.uuid == uuid }?.let { return it.key }
        var code: String
        do code = String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }) while (code in byCode)
        byCode[code] = Pending(uuid, name, now + ttlMinutes * 60_000L)
        save()
        return code
    }

    @Synchronized
    fun consume(code: String): Pending? {
        val pending = byCode.remove(code.trim().uppercase()) ?: return null
        save()
        return pending.takeIf { it.expiresAt > System.currentTimeMillis() }
    }

    private fun load(): HashMap<String, Pending> {
        val map = HashMap<String, Pending>()
        runCatching {
            if (Files.exists(path)) json.decodeFromString<List<StoredCode>>(Files.readString(path))
                .filter { it.expiresAt > System.currentTimeMillis() }
                .forEach { map[it.code] = Pending(UUID.fromString(it.uuid), it.name, it.expiresAt) }
        }
        return map
    }

    private fun save() {
        runCatching {
            val tmp = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(tmp, json.encodeToString(byCode.map { (code, p) -> StoredCode(code, p.uuid.toString(), p.name, p.expiresAt) }))
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
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
