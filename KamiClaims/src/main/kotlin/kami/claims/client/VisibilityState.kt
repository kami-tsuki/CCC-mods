package kami.claims.client

import kami.claims.service.View
import kami.claims.war.Entry
import kami.claims.war.Selection
import kami.libs.xaero.Tracked
import kami.libs.xaero.TrackedSource
import net.minecraft.client.Minecraft
import java.util.UUID

object VisibilityState : TrackedSource {
    @Volatile
    private var hidden: Set<UUID> = emptySet()

    @Volatile
    private var entries: List<Entry> = emptyList()

    fun isHidden(id: UUID) = id in hidden

    fun receive(s: Selection) {
        hidden = s.hidden
        entries = s.entries
    }

    fun clear() {
        hidden = emptySet()
        entries = emptyList()
    }

    fun select(entries: List<Entry>, loaded: Set<UUID>, fairPlay: Boolean): List<Tracked> =
        entries.filter { fairPlay || it.id !in loaded }.map { Tracked(it.id, it.dim, it.x + 0.5, it.y.toDouble(), it.z + 0.5) }

    override fun players(): Collection<Tracked> {
        val list = entries
        if (list.isEmpty() || !ClientClaims.prefs.xaeroTracked) return emptyList()
        val loaded = Minecraft.getInstance().level?.players()?.mapTo(HashSet()) { it.uuid } ?: emptySet()
        return select(list, loaded, ClientClaims.war and View.WAR_FAIRPLAY != 0)
    }
}
