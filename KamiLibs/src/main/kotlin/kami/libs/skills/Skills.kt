package kami.libs.skills

import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.UUID

enum class SkillClass(val id: String, val color: Int) {
    RESEARCHER("researcher", 0x6FA8FF),
    TRADER("trader", 0xE8C15A),
    FARMER("farmer", 0x8BD15B),
    FISHER("fisher", 0x4FD1C5),
    LUMBER("lumber", 0xB5835A),
    MINER("miner", 0xA0A8B8),
    SOLDIER("soldier", 0xE0605A),
    WORKER("worker", 0xE89A4A);

    companion object {
        fun of(id: String?): SkillClass? = entries.firstOrNull { it.id == id }
    }
}

interface SkillProvider {
    fun value(player: UUID, key: String): Double
    fun grant(player: UUID, cls: SkillClass, source: String, scale: Double, bucket: String)
}

class SkillProfile(val cls: SkillClass?, val level: Int)

object Skills {
    const val RESEARCH_TIME = "research_time"
    const val RESEARCH_COST = "research_cost"
    const val RESEARCH_INSIGHT = "research_insight"
    const val AUCTION_FEE = "auction_fee"
    const val SCAN_RANGE = "scan_range"
    const val ORDER_SLOTS = "order_slots"
    const val AUCTION_SLOTS = "auction_slots"
    const val INFINITE_LOTS = "infinite_lots"

    private val caps = mapOf(
        RESEARCH_TIME to 0.5, RESEARCH_COST to 0.5, RESEARCH_INSIGHT to 0.5, SCAN_RANGE to 0.5, AUCTION_FEE to 0.9,
        ORDER_SLOTS to 12.0, AUCTION_SLOTS to 10.0, INFINITE_LOTS to 6.0
    )

    @Volatile var provider: SkillProvider? = null

    @Volatile var profiles: (UUID) -> SkillProfile? = { null }

    fun cap(key: String): Double = caps[key] ?: Double.MAX_VALUE

    fun profile(player: UUID): SkillProfile? = if (provider == null) null else profiles(player)

    fun value(player: UUID, key: String): Double = (provider?.value(player, key) ?: 0.0).coerceAtMost(cap(key))

    fun best(players: Iterable<UUID>, key: String): Double = players.maxOfOrNull { value(it, key) } ?: 0.0

    fun bestOnline(players: Iterable<UUID>, key: String): Double {
        if (provider == null) return 0.0
        val list = ServerLifecycleHooks.getCurrentServer()?.playerList ?: return 0.0
        return best(players.filter { list.getPlayer(it) != null }, key)
    }

    fun grant(player: UUID, cls: SkillClass, source: String, scale: Double = 1.0, bucket: String = source) {
        if (scale > 0) provider?.grant(player, cls, source, scale, bucket)
    }
}
