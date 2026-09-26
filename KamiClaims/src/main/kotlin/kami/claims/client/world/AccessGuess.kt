package kami.claims.client.world

import kami.claims.Rank
import kami.claims.client.ClientClaims
import kami.claims.client.store.ClaimsStore
import kami.claims.service.View

object AccessGuess {
    val actions = listOf("break", "place", "interact", "container")

    fun owner(dim: String, x: Int, z: Int): Int = ClientClaims.at(dim, x, z)?.country ?: -1

    fun allowed(dim: String, blockX: Int, blockZ: Int, action: String): Boolean {
        if (!ClientClaims.active(dim)) return true
        val e = ClientClaims.at(dim, blockX shr 4, blockZ shr 4) ?: return action == "interact" || action == "container"
        val relation = ClientClaims.country(e)?.relation ?: View.REL_NONE
        if (relation == View.REL_BANISHED) return false
        val type = ClientClaims.typeName(e)
        if (type == "residential" && (e.flags and (View.MINE or View.TAKEN)) != 0) return e.flags and View.MINE != 0
        val snap = ClaimsStore.snap ?: return relation == View.REL_MEMBER
        val line = snap.types.firstOrNull { it.name == type } ?: return relation == View.REL_MEMBER
        val index = actions.indexOf(action)
        val access = if (relation == View.REL_MEMBER) line.access.getOrNull(index) else line.defaults.getOrNull(index) ?: line.access.getOrNull(index)
        val rank = if (relation == View.REL_MEMBER) ClaimsStore.snap?.rank?.let { runCatching { Rank.valueOf(it.uppercase()) }.getOrNull() } ?: Rank.CITIZEN
        else if (relation == View.REL_ALLY || relation == View.REL_FAMILY) Rank.ALLIED else null
        val job = snap.info?.members?.firstOrNull { it.id == snap.me }?.job.orEmpty()
        return when (access) {
            "none" -> false
            "any" -> true
            "allied" -> rank != null && rank >= Rank.ALLIED
            "citizen" -> rank != null && rank >= Rank.CITIZEN
            "worker" -> rank != null && (rank >= Rank.OFFICER || (rank >= Rank.CITIZEN && job.isNotEmpty()))
            "job" -> rank != null && (rank >= Rank.OFFICER || (rank >= Rank.CITIZEN && job.isNotEmpty() && (line.job.isEmpty() || line.job == job)))
            "officer" -> rank != null && rank >= Rank.OFFICER
            else -> false
        }
    }
}
