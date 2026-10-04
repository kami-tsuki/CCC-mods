package kami.libs.discord

import kami.libs.config.Configs
import kami.libs.log.Log
import kotlinx.serialization.Serializable
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

@Serializable
data class Link(val uuid: String, val name: String, val discordId: Long, val discordName: String, val linkedAt: Long)

@Serializable
val Link.id: UUID get() = UUID.fromString(uuid)

@Serializable
private class LinkFile(val links: List<Link> = emptyList())

private class Index(links: Collection<Link>) {
    val byDiscord = links.associateBy { it.discordId }
    val byName = links.groupBy { it.name.lowercase() }
}

internal fun atomicWrite(path: Path, text: String) {
    val tmp = path.resolveSibling("${path.fileName}.tmp")
    Files.writeString(tmp, text)
    restrict(tmp)
    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

internal fun restrict(path: Path) {
    runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
}

object Links {
    private val log = Log.of("discord")
    private val json = Configs.json()
    private val path by lazy { FMLPaths.CONFIGDIR.get().resolve("kami-discord-links.json") }
    private val byUuid by lazy { load() }
    private var failed = false
    private var index: Index? = null
    val broken: Boolean
        @Synchronized get() {
            byUuid
            return failed
        }

    @Synchronized
    fun get(uuid: UUID): Link? = byUuid[uuid.toString()]

    @Synchronized
    fun byDiscord(id: Long): Link? = index().byDiscord[id]

    @Synchronized
    fun byName(name: String): List<Link> = index().byName[name.lowercase()].orEmpty()

    @Synchronized
    fun all(): List<Link> = byUuid.values.toList()

    @Synchronized
    fun put(link: Link): Boolean {
        if (broken) return false
        commit {
            values.removeAll { it.discordId == link.discordId && it.uuid != link.uuid }
            put(link.uuid, link)
        }
        return true
    }

    @Synchronized
    fun removeAll(uuids: Collection<UUID>): List<Link> {
        if (broken) return emptyList()
        val keys = uuids.mapTo(HashSet()) { it.toString() }
        val removed = byUuid.values.filter { it.uuid in keys }
        if (removed.isNotEmpty()) commit { keys.forEach { remove(it) } }
        return removed
    }

    @Synchronized
    fun rename(uuid: UUID, name: String) {
        val link = byUuid[uuid.toString()]?.takeIf { it.name != name && !broken } ?: return
        commit { put(link.uuid, Link(link.uuid, name, link.discordId, link.discordName, link.linkedAt)) }
    }

    @Synchronized
    fun reload() {
        val fresh = load()
        byUuid.clear()
        byUuid.putAll(fresh)
        index = null
    }

    private fun index() = index ?: Index(byUuid.values).also { index = it }

    private fun load(): LinkedHashMap<String, Link> {
        failed = false
        val map = LinkedHashMap<String, Link>()
        if (!Files.exists(path)) return map
        try {
            json.decodeFromString(LinkFile.serializer(), Files.readString(path)).links.forEach { map[it.uuid] = it }
        } catch (e: Exception) {
            failed = true
            log.error("Unreadable {}: {}; all players are refused until it is fixed and /discord reload is run", path.fileName, e.message)
        }
        return map
    }

    private fun commit(change: MutableMap<String, Link>.() -> Unit) {
        val next = LinkedHashMap(byUuid).apply(change)
        save(next.values)
        byUuid.clear()
        byUuid.putAll(next)
        index = null
    }

    private fun save(links: Collection<Link>) = atomicWrite(path, json.encodeToString(LinkFile.serializer(), LinkFile(links.toList())))
}
