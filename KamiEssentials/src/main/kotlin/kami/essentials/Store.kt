package kami.essentials

import kami.libs.config.WorldStore
import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class Flag { INVISIBLE, NO_SIDEBAR, COUNTRY_CHAT, ADMIN_CHAT, NO_TRADES, QUIET_DM }

@Serializable
private class Data(val flags: Map<Flag, List<String>> = emptyMap())

object Store {
    private val store = WorldStore(Data.serializer(), ::Data)
    private val sets = Flag.entries.associateWith { ConcurrentHashMap.newKeySet<UUID>() }

    fun load(server: MinecraftServer) {
        val path = server.getWorldPath(LevelResource.ROOT).resolve("kami_essentials.json")
        val data = store.load(path) { KamiEssentials.LOG.error("Unreadable essentials data, kept as .bad", it) }
        sets.forEach { (flag, set) -> set.clear(); data.flags[flag]?.mapNotNullTo(set) { runCatching { UUID.fromString(it) }.getOrNull() } }
    }

    fun ids(flag: Flag): Set<UUID> = sets.getValue(flag)

    operator fun get(flag: Flag, id: UUID) = id in sets.getValue(flag)

    operator fun set(flag: Flag, id: UUID, on: Boolean) {
        val set = sets.getValue(flag)
        if (if (on) set.add(id) else set.remove(id)) save()
    }

    private fun save() {
        store.data = Data(sets.mapValues { (_, s) -> s.map(UUID::toString).sorted() })
        store.changed()
        store.save()
    }
}
