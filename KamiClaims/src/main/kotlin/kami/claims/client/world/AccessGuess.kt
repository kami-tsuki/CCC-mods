package kami.claims.client.world

import kami.claims.client.rankOf
import kami.claims.Rank
import kami.claims.client.ClientClaims
import kami.claims.client.store.ClaimsStore
import kami.claims.service.View

object AccessGuess {
    val actions = listOf("break", "place", "interact", "container")

    fun owner(dim: String, x: Int, z: Int): Int = ClientClaims.at(dim, x, z)?.country ?: -1

    fun allowed(dim: String, blockX: Int, blockZ: Int, action: String): Boolean {
        if (!ClientClaims.active(dim)) return true
        val e = ClientClaims.at(dim, blockX shr 4, blockZ shr 4) ?: return false
        val relation = ClientClaims.country(e)?.relation ?: View.REL_NONE
        val type = ClientClaims.typeName(e)
        if (type == "residential") {
            if (e.flags and View.MINE != 0) return !(action == "place" && e.flags and View.MOVING != 0)
            if (e.flags and View.TAKEN != 0) return false
        }
        if (relation == View.REL_BANISHED) return false
        val snap = ClaimsStore.snap ?: return relation == View.REL_MEMBER
        val line = snap.types.firstOrNull { it.name == type } ?: return relation == View.REL_MEMBER
        val index = actions.indexOf(action)
        val access = if (relation == View.REL_MEMBER) line.access.getOrNull(index) else line.defaults.getOrNull(index) ?: line.access.getOrNull(index)
        val rank = if (relation == View.REL_MEMBER) ClaimsStore.snap?.rank?.let(::rankOf) ?: Rank.CITIZEN
        else if (relation == View.REL_ALLY || relation == View.REL_FAMILY) Rank.ALLIED else null
        return when (access) {
            "none" -> false
            "any" -> true
            "allied" -> rank != null && rank >= Rank.ALLIED
            "citizen" -> rank != null && rank >= Rank.CITIZEN
            "worker", "job" -> rank != null && (rank >= Rank.OFFICER || e.flags and View.ASSIGNED != 0)
            "officer" -> rank != null && rank >= Rank.OFFICER
            else -> false
        }
    }
}
