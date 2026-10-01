package kami.claims.research

import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.common.util.FakePlayer
import java.util.UUID

class Sighting(val id: String, val tags: List<String>)

class StructureWatch {
    private val inside = HashMap<UUID, String>()

    fun observe(player: UUID, sighting: Sighting?): Sighting? {
        if (sighting == null) {
            inside.remove(player)
            return null
        }
        return sighting.takeIf { inside.put(player, it.id) != it.id }
    }

    fun forget(player: UUID) {
        inside.remove(player)
    }
}

object Structures {
    const val PERIOD = 100

    private val watch = StructureWatch()

    fun tick(tick: Int, players: List<ServerPlayer>) {
        for (player in players) {
            if (player is FakePlayer || (tick + player.id) % PERIOD != 0) continue
            val entered = watch.observe(player.uuid, sightingAt(player)) ?: continue
            Progress.of(player)?.let { Progress.structureEntered(it, entered.id, entered.tags) }
        }
    }

    fun forget(player: ServerPlayer) = watch.forget(player.uuid)

    private fun sightingAt(player: ServerPlayer): Sighting? {
        val level = player.level() as? ServerLevel ?: return null
        val start = level.structureManager().getStructureWithPieceAt(player.blockPosition()) { true }
        if (!start.isValid) return null
        val registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE)
        val id = registry.getKey(start.structure)?.toString() ?: return null
        return Sighting(id, registry.wrapAsHolder(start.structure).tags().map { it.location().toString() }.toList())
    }
}
