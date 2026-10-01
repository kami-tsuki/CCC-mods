package kami.claims.client.store

import kami.claims.net.StateView

class ResearchChange(val completed: List<String>, val leveledUp: Boolean) {
    companion object {
        fun between(old: StateView, next: StateView): ResearchChange? {
            if (old.country.isEmpty() || old.country != next.country) return null
            val completed = next.done.filter { it !in old.done }
            val leveledUp = next.level > old.level
            return if (completed.isEmpty() && !leveledUp) null else ResearchChange(completed, leveledUp)
        }
    }
}
