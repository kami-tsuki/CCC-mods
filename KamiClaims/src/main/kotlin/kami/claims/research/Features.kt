package kami.claims.research

import kami.claims.Country
import kami.claims.Rank
import kami.claims.service.Fail
import kami.claims.service.Words
import kami.libs.claims.Locks
import kami.libs.text.Phrase

object Features {
    const val CHANCELLOR = "role:chancellor"
    const val BANISH = "banish"
    const val ALLIANCES = "alliances"
    const val EMBARGOES = "embargoes"
    const val VENDORS = "economy:vendors"
    const val LOANS = "loans"

    fun claimType(type: String) = "claim_type:$type"

    fun unlockLevel(id: String): Int? = Research.defs.levels.featureLevel(id)

    private fun unlockingNodes(id: String) = Research.defs.nodes.values.filter { node -> node.unlocks.any { it is FeatureUnlock && it.id == id } }

    fun unlocked(country: Country, id: String): Boolean {
        val level = unlockLevel(id)
        val nodes = unlockingNodes(id)
        if (level == null && nodes.isEmpty()) return true
        return level?.let { Research.level(country) >= it } == true || nodes.any { it.key in country.research.done }
    }

    fun lockReason(country: Country, id: String): Phrase? {
        if (unlocked(country, id)) return null
        return unlockLevel(id)?.let { Locks.level(Words.feature(id), it) } ?: Locks.research(unlockingNodes(id).first().label().asValue())
    }

    fun limit(country: Country, key: Capacity, used: Int): Phrase? {
        val max = Levels.capacity(country, key)
        if (used < max) return null
        val raise = Goals.nextRaise(country, key)
        return Locks.capacity(Goals.label(key), max, raise?.level, raise?.max)
    }

    fun requireRoom(country: Country, key: Capacity, used: Int) {
        limit(country, key, used)?.let { throw Fail(it) }
    }

    fun require(country: Country, id: String) {
        lockReason(country, id)?.let { throw Fail(it) }
    }

    fun requireRank(country: Country, rank: Rank) {
        when (rank) {
            Rank.CHANCELLOR -> require(country, CHANCELLOR)
            Rank.OFFICER -> requireRoom(country, Capacity.OFFICERS, Levels.used(country, Capacity.OFFICERS))
            else -> Unit
        }
    }
}
