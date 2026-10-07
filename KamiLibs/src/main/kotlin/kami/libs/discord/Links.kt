package kami.libs.discord

import kami.libs.db.Db
import kami.libs.db.Row
import kami.libs.log.Log
import kami.libs.stats.Stats
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

@Serializable
data class Link(val uuid: String, val name: String, val discordId: Long, val discordName: String, val linkedAt: Long)

@Serializable
val Link.id: UUID get() = UUID.fromString(uuid)

@Serializable
internal class LinkFile(val links: List<Link> = emptyList())

private class Index(links: Collection<Link>) {
    val byDiscord = links.associateBy { it.discordId }
    val byName = links.groupBy { it.name.lowercase() }
}

internal fun restrict(path: Path) {
    runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
}

internal fun Row.link() = Link(string("uuid"), string("name"), long("discord_id"), string("discord_name"), long("linked_at"))

private const val UPSERT = "INSERT OR REPLACE INTO libs_discord_link (uuid, name, discord_id, discord_name, linked_at) VALUES (?, ?, ?, ?, ?)"

private fun Link.row() = listOf(uuid, name, discordId, discordName, linkedAt)

object Links {
    private val log = Log.of("discord")
    private val byUuid = LinkedHashMap<String, Link>()
    private var index: Index? = null

    @Synchronized
    fun load() {
        byUuid.clear()
        Db.read { query("SELECT uuid, name, discord_id, discord_name, linked_at FROM libs_discord_link ORDER BY linked_at", row = Row::link) }.forEach { byUuid[it.uuid] = it }
        index = null
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
        try {
            Db.durable { exec(UPSERT, *link.row().toTypedArray()) }
        } catch (e: Exception) {
            log.error("Cannot save the link of {}: {}", link.name, e.message)
            return false
        }
        val displaced = byUuid.values.filter { it.discordId == link.discordId && it.uuid != link.uuid }
        displaced.forEach { byUuid.remove(it.uuid) }
        byUuid[link.uuid] = link
        index = null
        displaced.forEach(::unlinked)
        Stats.event("discord.link", "p:${link.uuid}", link.discordId.toString())
        return true
    }

    @Synchronized
    fun removeAll(uuids: Collection<UUID>): List<Link> {
        val keys = uuids.mapTo(HashSet()) { it.toString() }
        val removed = byUuid.values.filter { it.uuid in keys }
        if (removed.isEmpty()) return removed
        try {
            Db.durable { removed.forEach { exec("DELETE FROM libs_discord_link WHERE uuid = ?", it.uuid) } }
        } catch (e: Exception) {
            log.error("Cannot remove links: {}", e.message)
            return emptyList()
        }
        removed.forEach { byUuid.remove(it.uuid) }
        index = null
        removed.forEach(::unlinked)
        return removed
    }

    @Synchronized
    fun rename(uuid: UUID, name: String, discordName: String? = null) {
        val link = byUuid[uuid.toString()]?.takeIf { it.name != name || (discordName != null && it.discordName != discordName) } ?: return
        val next = link.copy(name = name, discordName = discordName ?: link.discordName)
        Db.write { exec(UPSERT, *next.row().toTypedArray()) }
        byUuid[next.uuid] = next
        index = null
    }

    private fun unlinked(link: Link) = Stats.event("discord.unlink", "p:${link.uuid}", link.discordId.toString())

    private fun index() = index ?: Index(byUuid.values).also { index = it }
}
