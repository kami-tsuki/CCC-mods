package kami.claims.research

import kami.claims.Country
import kami.claims.Realm
import kami.claims.net.ResearchSync
import kami.claims.now
import kami.libs.claims.Boost as BoostLimits
import kotlinx.serialization.Serializable

@Serializable
enum class NodeState { QUEUED, READY, RESEARCHING, PAUSED }

@Serializable
class QueueEntry(
    var node: String,
    var state: NodeState = NodeState.QUEUED,
    var remainingMs: Long = 0,
    var paid: Boolean = false,
    val tasks: MutableMap<Int, Long> = mutableMapOf(),
    val by: String? = null,
    val since: Long = now(),
    var penaltyMs: Long = 0,
    var queuedAt: Long = 0,
    var startedAt: Long = 0,
    var paidCost: Long = -1
)

@Serializable
class BuffState(val enabled: MutableSet<String> = mutableSetOf(), val billed: MutableSet<String> = mutableSetOf())

@Serializable
class Boost(val pct: Int, var leftMs: Long)

object Boosts {
    fun pct(country: Country, kind: String) = country.boosts[kind]?.firstOrNull()?.pct ?: 0

    fun add(country: Country, kind: String, pct: Int, ms: Long): Boolean {
        if (kind !in BoostLimits.KINDS || pct !in 1..BoostLimits.MAX_PCT || ms <= 0 || ms > BoostLimits.MAX_HOURS * 3_600_000L || !country.active) return false
        country.boosts.getOrPut(kind) { mutableListOf() }.add(Boost(pct, ms))
        Realm.changed(false)
        ResearchSync.touch(country)
        return true
    }

    fun tick(country: Country, elapsed: Long) {
        val minutes = country.boosts.values.sumOf { l -> l.firstOrNull()?.leftMs?.div(60_000) ?: 0 }
        var changed = false
        country.boosts.values.forEach { list ->
            var left = elapsed
            while (left > 0 && list.isNotEmpty()) {
                val head = list.first()
                if (head.leftMs > left) {
                    head.leftMs -= left
                    left = 0
                } else {
                    left -= head.leftMs
                    list.removeAt(0)
                    changed = true
                }
            }
        }
        country.boosts.values.removeAll { it.isEmpty() }
        if (changed || minutes != country.boosts.values.sumOf { l -> l.firstOrNull()?.leftMs?.div(60_000) ?: 0 }) {
            Realm.changed(false)
            ResearchSync.touch(country)
        }
    }
}

@Serializable
class ResearchState(
    val done: MutableMap<String, Long> = mutableMapOf(),
    val queue: MutableList<QueueEntry> = mutableListOf(),
    val visited: MutableSet<String> = mutableSetOf(),
    val parked: MutableList<QueueEntry> = mutableListOf(),
    var treeVersion: Int = 0
)
