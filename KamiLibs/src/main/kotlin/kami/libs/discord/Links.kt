package kami.libs.discord

import kami.libs.config.Configs
import kami.libs.log.Log
import kotlinx.serialization.Serializable
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class Link(val uuid: String, val name: String, val discordId: Long, val discordName: String, val linkedAt: Long)

@Serializable
private class LinkFile(val links: List<Link> = emptyList())

object Links {
    private val log = Log.of("discord")
    private val json = Configs.json()
    private val path by lazy { FMLPaths.CONFIGDIR.get().resolve("kami-discord-links.json") }
    private val byUuid by lazy { load() }
    @Volatile var broken = false
        private set

    @Synchronized
    fun get(uuid: UUID): Link? = byUuid[uuid.toString()]

    @Synchronized
    fun byDiscord(id: Long): Link? = byUuid.values.firstOrNull { it.discordId == id }

    @Synchronized
    fun all(): List<Link> = byUuid.values.toList()

    @Synchronized
    fun put(link: Link) {
        byUuid.values.removeAll { it.discordId == link.discordId && it.uuid != link.uuid }
        byUuid[link.uuid] = link
        save()
    }

    @Synchronized
    fun remove(uuid: UUID): Link? = byUuid.remove(uuid.toString())?.also { save() }

    @Synchronized
    fun rename(uuid: UUID, name: String) {
        val link = byUuid[uuid.toString()]?.takeIf { it.name != name } ?: return
        byUuid[link.uuid] = Link(link.uuid, name, link.discordId, link.discordName, link.linkedAt)
        save()
    }

    private fun load(): LinkedHashMap<String, Link> {
        val map = LinkedHashMap<String, Link>()
        if (!Files.exists(path)) return map
        try {
            json.decodeFromString(LinkFile.serializer(), Files.readString(path)).links.forEach { map[it.uuid] = it }
        } catch (e: Exception) {
            broken = true
            log.error("Unreadable {}: {}; links are read-only and unlinked players are refused until it is fixed", path.fileName, e.message)
        }
        return map
    }

    private fun save() {
        if (broken) return
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(LinkFile.serializer(), LinkFile(byUuid.values.toList())))
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
