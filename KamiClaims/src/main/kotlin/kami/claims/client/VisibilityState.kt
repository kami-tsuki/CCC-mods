package kami.claims.client

import kami.claims.service.View
import kami.claims.war.Entry
import kami.claims.war.Selection
import kami.libs.xaero.Tracked
import kami.libs.xaero.TrackedSource
import kami.libs.xaero.XaeroMaps
import net.minecraft.client.Minecraft
import java.util.UUID

object VisibilityState : TrackedSource {
    @Volatile
    private var friendly: Set<UUID> = emptySet()

    @Volatile
    private var war = false

    @Volatile
    private var entries: List<Entry> = emptyList()

    private class Cached(val list: List<Entry>, val tick: Long, val fair: Boolean, val tracked: List<Tracked>)

    @Volatile
    private var cache: Cached? = null

    fun isHidden(id: UUID) = war && id !in friendly && id != Minecraft.getInstance().player?.uuid

    fun receive(s: Selection) {
        friendly = s.friendly
        war = s.war
        entries = s.entries
    }

    fun clear() {
        friendly = emptySet()
        war = false
        entries = emptyList()
    }

    fun select(entries: List<Entry>, loaded: (UUID) -> Boolean, fairPlay: Boolean): List<Tracked> =
        entries.filter { fairPlay || !loaded(it.id) }.map { Tracked(it.id, it.dim, it.x + 0.5, it.y.toDouble(), it.z + 0.5) }

    override fun players(): Collection<Tracked> {
        val list = entries
        if (list.isEmpty() || !XaeroMaps.on("claims.tracked")) return emptyList()
        val level = Minecraft.getInstance().level
        val tick = level?.gameTime ?: -1L
        val fair = ClientClaims.war and View.WAR_FAIRPLAY != 0
        cache?.takeIf { it.list === list && it.tick == tick && it.fair == fair }?.let { return it.tracked }
        val tracked = select(list, { level?.getPlayerByUUID(it) != null }, fair)
        cache = Cached(list, tick, fair, tracked)
        return tracked
    }
}
