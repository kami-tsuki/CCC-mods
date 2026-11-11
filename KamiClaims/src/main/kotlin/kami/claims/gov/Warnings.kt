package kami.claims.gov

import kami.claims.Claimant
import kami.claims.Country

enum class WarnTier { INFO, CAUTION, DANGER, CRITICAL }

class Warning(val key: String, val severity: WarnTier)

object Warnings {
    private const val MANY_HOLDERS = 5
    private val rankManagement = setOf(Perm.EDIT_RANKS, Perm.CREATE_RANKS)
    private val territoryPowers = setOf(Perm.CAPITAL, Perm.PROVINCES, Perm.WAR)
    private val removal = setOf(Perm.KICK, Perm.BANISH)
    private val outsiderHarm = setOf(Perm.BREAK, Perm.CONTAINERS)

    fun of(c: Country, rank: RankDef?, added: Set<Perm>, removed: Set<Perm>, group: Claimant? = null): List<Warning> =
        listOfNotNull(
            manageRanks(added),
            selfEscalation(rank, added),
            withdraw(added),
            withdrawCrowd(c, rank, added),
            removeCitizens(added),
            giveUpLand(added),
            territory(added),
            bypass(added),
            outsiderHarm(group, added),
            alliedAccess(group, added),
            defaultRank(rank, added),
            onlyPresidentPromotes(c, rank, removed),
            lostPower(c, rank, removed)
        ).sortedByDescending { it.severity }

    private fun holders(c: Country, rank: RankDef?) = if (rank == null) 0 else Permissions.holders(c, rank.id)

    private fun manageRanks(added: Set<Perm>) = warn("manage_ranks", WarnTier.CRITICAL, added.any { it in rankManagement })

    private fun selfEscalation(rank: RankDef?, added: Set<Perm>): Warning? {
        val after = rank?.perms.orEmpty() + added
        val rising = added.any { it == Perm.EDIT_RANKS || it == Perm.ASSIGN_RANKS }
        return warn("self_escalation", WarnTier.CRITICAL, rising && Perm.EDIT_RANKS in after && Perm.ASSIGN_RANKS in after)
    }

    private fun withdraw(added: Set<Perm>) = warn("withdraw", WarnTier.CRITICAL, Perm.WITHDRAW in added)

    private fun withdrawCrowd(c: Country, rank: RankDef?, added: Set<Perm>): Warning? {
        val count = holders(c, rank)
        return warn("withdraw_crowd", WarnTier.CRITICAL, Perm.WITHDRAW in added && count >= MANY_HOLDERS)
    }

    private fun removeCitizens(added: Set<Perm>) = warn("remove_citizens", WarnTier.DANGER, added.any { it in removal })

    private fun giveUpLand(added: Set<Perm>) = warn("give_up_land", WarnTier.DANGER, Perm.UNCLAIM in added)

    private fun territory(added: Set<Perm>) = warn("territory", WarnTier.CRITICAL, added.any { it in territoryPowers })

    private fun bypass(added: Set<Perm>) = warn("bypass", WarnTier.CRITICAL, Perm.BYPASS_RULES in added)

    private fun outsiderHarm(group: Claimant?, added: Set<Perm>) = warn("outsider_harm", WarnTier.DANGER, group != null && added.any { it in outsiderHarm })

    private fun alliedAccess(group: Claimant?, added: Set<Perm>) =
        warn("allied_access", WarnTier.DANGER, group == Claimant.ALLIED && added.any { it.group == PermGroup.TREASURY || it.group == PermGroup.PEOPLE })

    private fun defaultRank(rank: RankDef?, added: Set<Perm>): Warning? {
        val risky = added.filter { it.risk >= Risk.HIGH }
        val severity = if (risky.any { it.risk == Risk.CRITICAL }) WarnTier.CRITICAL else WarnTier.DANGER
        return warn("default_rank", severity, rank?.id == CITIZEN && risky.isNotEmpty())
    }

    private fun onlyPresidentPromotes(c: Country, rank: RankDef?, removed: Set<Perm>): Warning? {
        val others = c.ranks.any { !it.fixed && it !== rank && Perm.ASSIGN_RANKS in it.perms }
        return warn("only_president_promotes", WarnTier.CAUTION, rank != null && Perm.ASSIGN_RANKS in removed && !others)
    }

    private fun lostPower(c: Country, rank: RankDef?, removed: Set<Perm>): Warning? {
        val count = holders(c, rank)
        return warn("lost_power", WarnTier.INFO, removed.isNotEmpty() && count > 0)
    }

    private fun warn(key: String, tier: WarnTier, condition: Boolean) =
        if (condition) Warning(key, tier) else null
}
