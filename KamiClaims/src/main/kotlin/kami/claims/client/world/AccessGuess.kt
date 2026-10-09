package kami.claims.client.world

import kami.claims.client.app.rankOf
import kami.claims.Rank
import kami.claims.client.ClientClaims
import kami.claims.client.store.ClaimsStore
import kami.claims.service.View

object AccessGuess {
    val actions = listOf("break", "place", "interact", "container")

    fun owner(dim: String, x: Int, z: Int): Int = ClientClaims.at(dim, x, z)?.country ?: -1

    private val memo = HashMap<Long, Boolean>()
    private var memoCountries: Any? = null
    private var memoSnap: Any? = null
    private var memoDim = ""

    fun allowed(dim: String, blockX: Int, blockZ: Int, action: String): Boolean {
        val index = actions.indexOf(action)
        if (index < 0) return compute(dim, blockX, blockZ, action)
        val snap = ClaimsStore.snap
        if (ClientClaims.countries !== memoCountries || snap !== memoSnap || dim != memoDim) {
            memo.clear()
            memoCountries = ClientClaims.countries
            memoSnap = snap
            memoDim = dim
        }
        return memo.getOrPut((ClientClaims.key(blockX shr 4, blockZ shr 4) shl 2) or index.toLong()) { compute(dim, blockX, blockZ, action) }
    }

    private fun compute(dim: String, blockX: Int, blockZ: Int, action: String): Boolean {
        if (!ClientClaims.active(dim)) return true
        val e = ClientClaims.at(dim, blockX shr 4, blockZ shr 4) ?: return action == "interact" || action == "container"
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
        val jobs = snap.info?.members?.firstOrNull { it.id == snap.me }?.jobs.orEmpty()
        val officer = rank != null && rank >= Rank.OFFICER
        return when (access) {
            "none" -> false
            "any" -> true
            "allied" -> rank != null && rank >= Rank.ALLIED
            "citizen" -> rank != null && rank >= Rank.CITIZEN
            "assigned" -> officer || e.flags and View.ASSIGNED != 0
            "job" -> officer || relation == View.REL_MEMBER && line.job in jobs
            "worker" -> officer || relation == View.REL_MEMBER && jobs.isNotEmpty()
            "officer" -> officer
            else -> false
        }
    }
}
