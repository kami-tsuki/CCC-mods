package kami.essentials

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Flag { INVISIBLE, NO_SIDEBAR, COUNTRY_CHAT }

@Serializable
private class Data(val flags: Map<Flag, List<String>> = emptyMap())

object Store {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val sets = Flag.entries.associateWith { ConcurrentHashMap.newKeySet<UUID>() }
    private var file: Path? = null

    fun load(server: MinecraftServer) {
        val path = server.getWorldPath(LevelResource.ROOT).resolve("kami_essentials.json")
        file = path
        val data = if (Files.exists(path)) runCatching { json.decodeFromString<Data>(Files.readString(path)) }.getOrElse {
            KamiEssentials.LOG.error("Unreadable essentials data, kept as .bad", it)
            Files.move(path, path.resolveSibling("kami_essentials.json.bad"), StandardCopyOption.REPLACE_EXISTING)
            Data()
        } else Data()
        sets.forEach { (flag, set) -> set.clear(); data.flags[flag]?.mapNotNullTo(set) { runCatching { UUID.fromString(it) }.getOrNull() } }
    }

    fun ids(flag: Flag): Set<UUID> = sets.getValue(flag)

    operator fun get(flag: Flag, id: UUID) = id in sets.getValue(flag)

    operator fun set(flag: Flag, id: UUID, on: Boolean) {
        val set = sets.getValue(flag)
        if (if (on) set.add(id) else set.remove(id)) save()
    }

    private fun save() {
        val path = file ?: return
        val tmp = path.resolveSibling("kami_essentials.json.tmp")
        Files.writeString(tmp, json.encodeToString(Data(sets.mapValues { (_, s) -> s.map(UUID::toString).sorted() })))
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    }
}
