package kami.claims.client.world

import kami.claims.gov.Perm
import kami.claims.client.ClientClaims
import kami.claims.client.store.ClaimsStore
import kami.claims.net.Info
import kami.claims.service.View

object AccessGuess {
    val actions = listOf("break", "place", "interact", "container")
    private val usePerm = mapOf("break" to Perm.BREAK, "place" to Perm.PLACE, "interact" to Perm.INTERACT, "container" to Perm.CONTAINERS)

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

    private fun outsiderPerms(info: Info?, relation: Int): List<String> {
        val groups = info?.groups.orEmpty()
        return if (relation == View.REL_ALLY) groups["ALLIED"].orEmpty() else groups["PARENT"].orEmpty() + groups["PROVINCE"].orEmpty()
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
        val member = relation == View.REL_MEMBER
        val friend = member || relation == View.REL_ALLY || relation == View.REL_FAMILY
        val perms = snap.info?.myPerms.orEmpty()
        val use = usePerm[action]?.name
        val permitted = when {
            member -> Perm.BYPASS_RULES.name in perms || use in perms
            friend -> use in outsiderPerms(snap.info, relation)
            else -> true
        }
        val jobs = snap.info?.members?.firstOrNull { it.id == snap.me }?.jobs.orEmpty()
        return permitted && when (access) {
            "none" -> false
            "any" -> true
            "allied" -> friend
            "citizen" -> member
            "assigned" -> member && e.flags and View.ASSIGNED != 0
            "job" -> member && line.job in jobs
            "worker" -> member && jobs.isNotEmpty()
            "officer" -> member && Perm.CHUNK_RULES.name in perms
            else -> false
        }
    }
}
