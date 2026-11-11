package kami.claims.gov

import kami.claims.Claimant
import kami.claims.Country
import kami.claims.service.Fail
import kami.claims.service.Housing

object Permissions {
    val groupKeys = listOf(Claimant.ALLIED, Claimant.PARENT, Claimant.PROVINCE)

    fun group(name: String): Claimant = groupKeys.firstOrNull { it.name.equals(name, true) } ?: throw Fail("kami_claims.error.group_unknown")

    fun rankOf(c: Country, id: String): RankDef? = c.members[id]?.let { rank(c, it.rank) }

    fun rank(c: Country, rankId: String): RankDef? = c.ranks.firstOrNull { it.id == rankId }

    fun position(c: Country, rankId: String): Int = c.ranks.indexOfFirst { it.id == rankId }.takeIf { it >= 0 } ?: Int.MAX_VALUE

    fun positionOf(c: Country, id: String): Int = c.members[id]?.let { position(c, it.rank) } ?: Int.MAX_VALUE

    fun president(c: Country): String? = c.members.entries.firstOrNull { it.value.rank == PRESIDENT }?.key

    fun isPresident(c: Country, id: String): Boolean = c.members[id]?.rank == PRESIDENT

    fun holders(c: Country, rankId: String): Int = c.members.values.count { it.rank == rankId }

    fun outsiderPerms(c: Country, id: String): Set<Perm> = when (c.outsiders[id]) {
        Standing.BANISHED -> emptySet()
        Standing.ALLIED -> if (id in c.autoAllies) familyPerms(c, id) else c.groups[Claimant.ALLIED].orEmpty()
        null -> familyPerms(c, id)
    }

    private fun familyPerms(c: Country, id: String): Set<Perm> = Housing.category(c, id)?.let { c.groups[it] }.orEmpty()

    fun holds(c: Country, id: String, perm: Perm): Boolean {
        val rank = rankOf(c, id) ?: return perm in outsiderPerms(c, id)
        return rank.fixed || perm in rank.perms || viaJob(c, id, perm)
    }

    private fun viaJob(c: Country, id: String, perm: Perm): Boolean =
        perm in JobRules.grantable && c.members[id]?.jobs?.keys.orEmpty().any { job -> c.jobDefs[job]?.let { it.active && perm in it.perms } == true }

    fun outranks(c: Country, actor: String, target: String): Boolean = positionOf(c, actor) < positionOf(c, target)

    fun outranksRank(c: Country, actor: String, rankId: String): Boolean = positionOf(c, actor) < position(c, rankId)

    fun nonCitizens(c: Country): List<String> = c.members.filterValues { it.rank != CITIZEN }.keys.toList()

    fun missing(c: Country, actor: String, perms: Collection<Perm>): List<Perm> = perms.filterNot { holds(c, actor, it) }
}
