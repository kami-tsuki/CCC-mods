package kami.claims.client.store

import kami.claims.net.StateView
import kami.claims.research.NodeState

class ResearchChange(val completed: List<String>, val started: List<String>, val levelFrom: Int, val levelTo: Int, val xpGained: Long) {
    val leveledUp get() = levelTo > levelFrom

    companion object {
        fun between(old: StateView, next: StateView): ResearchChange? {
            if (old.country.isEmpty() || old.country != next.country) return null
            val completed = next.done.filter { it !in old.done }
            val running = old.queue.filter { it.state == NodeState.RESEARCHING }.map { it.node }.toSet()
            val started = next.queue.filter { it.state == NodeState.RESEARCHING && it.node !in running && it.node !in completed }.map { it.node }
            val xp = (next.xp - old.xp).coerceAtLeast(0L)
            if (completed.isEmpty() && started.isEmpty() && old.level == next.level && xp == 0L) return null
            return ResearchChange(completed, started, old.level, next.level, xp)
        }
    }
}
