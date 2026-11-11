package kami.claims.gov

import kami.claims.Country
import kami.claims.Member
import kami.claims.NameRules
import kami.claims.research.Capacity
import kami.claims.research.Levels
import kami.claims.service.Fail
import kami.claims.service.Words.num

object RankRules {
    const val MAX_CRITICAL_RANKS = 2
    const val MAX_PLOTS = 1000

    fun rank(c: Country, id: String): RankDef = Permissions.rank(c, id) ?: throw Fail("kami_claims.error.rank_unknown")

    fun below(c: Country, actor: String, rankId: String): RankDef {
        if (!Permissions.outranksRank(c, actor, rankId)) throw Fail("kami_claims.error.rank_above")
        return rank(c, rankId)
    }

    fun unfixed(c: Country, id: String): RankDef = rank(c, id).also { if (it.fixed) throw Fail("kami_claims.error.rank_fixed") }

    fun editable(c: Country, actor: String, id: String): RankDef {
        unfixed(c, id)
        return below(c, actor, id)
    }

    fun name(c: Country, text: String, except: RankDef? = null): String {
        val name = ActionArgs.label(text)
        val key = NameRules.key(name)
        if (c.ranks.any { it !== except && NameRules.key(it.name) == key }) throw Fail("kami_claims.error.rank_name_taken")
        return name
    }

    fun resetTo(c: Country, preset: PresetId) {
        val starter = Presets.starterRanks(preset)
        val kept = starter.map { it.id }.toSet()
        c.members.values.filter { it.rank !in kept }.forEach { it.changeRank(CITIZEN, c) }
        c.ranks.clear()
        c.ranks += starter
    }

    fun color(color: Int) {
        if (color !in 0..0xFFFFFF) throw Fail("kami_claims.error.rank_color")
    }

    fun plots(plots: Int) {
        if (plots !in 0..MAX_PLOTS) throw Fail("kami_claims.error.rank_plots", num(MAX_PLOTS))
    }

    fun room(c: Country): Int {
        if (c.ranks.size >= c.government.rankCap(Levels.capacity(c, Capacity.RANKS))) throw Fail("kami_claims.error.rank_cap")
        return c.ranks.size - 1
    }

    fun creatable(c: Country, actor: String): Int {
        val index = room(c)
        if (Permissions.positionOf(c, actor) >= index) throw Fail("kami_claims.error.rank_above")
        return index
    }

    fun grantable(c: Country, actor: String, added: Set<Perm>) {
        val missing = Permissions.missing(c, actor, added)
        if (missing.isNotEmpty()) throw Fail("kami_claims.error.rank_grant", missing.joinToString { it.node })
    }

    fun criticalCap(c: Country, rank: RankDef?, perms: Set<Perm>) {
        if (perms.none { it.risk == Risk.CRITICAL }) return
        val others = c.ranks.count { !it.fixed && it !== rank && it.perms.any { p -> p.risk == Risk.CRITICAL } }
        if (others >= MAX_CRITICAL_RANKS) throw Fail("kami_claims.error.rank_critical_cap", num(MAX_CRITICAL_RANKS))
    }

    fun lockout(c: Country, rank: RankDef, perms: Set<Perm>) {
        if (Perm.ASSIGN_RANKS !in rank.perms || Perm.ASSIGN_RANKS in perms) return
        val others = c.ranks.any { !it.fixed && it !== rank && Perm.ASSIGN_RANKS in it.perms }
        if (!others && Permissions.holders(c, rank.id) > 0) throw Fail("kami_claims.error.rank_lockout")
    }

    fun slot(c: Country, rank: RankDef, to: Int): Int {
        if (rank.id == CITIZEN || to !in 1..c.ranks.size - 2) throw Fail("kami_claims.error.rank_move")
        return to
    }

    fun moved(c: Country, actor: String, rank: RankDef, up: Boolean): Int {
        val from = c.ranks.indexOf(rank)
        return movedTo(c, actor, rank, if (up) from - 1 else from + 1)
    }

    fun movedTo(c: Country, actor: String, rank: RankDef, to: Int): Int {
        slot(c, rank, to)
        if (to <= Permissions.positionOf(c, actor)) throw Fail("kami_claims.error.rank_move")
        return to
    }

    fun successor(c: Country, rank: RankDef, id: String?): RankDef =
        id?.let { rank(c, it) } ?: c.ranks.getOrNull(c.ranks.indexOf(rank) + 1) ?: throw Fail("kami_claims.error.rank_citizen")

    fun removable(rank: RankDef, target: RankDef) {
        if (rank.id == CITIZEN) throw Fail("kami_claims.error.rank_citizen")
        if (target === rank) throw Fail("kami_claims.error.rank_target")
        if (target.fixed) throw Fail("kami_claims.error.rank_fixed")
    }

    fun deletable(c: Country, actor: String, rank: RankDef, target: RankDef) {
        removable(rank, target)
        below(c, actor, target.id)
        grantable(c, actor, elevated(target.perms))
        if (promotes(c, rank, target)) grantable(c, actor, setOf(Perm.ASSIGN_RANKS))
        lockout(c, rank, emptySet())
    }

    private fun promotes(c: Country, rank: RankDef, target: RankDef) =
        Permissions.holders(c, rank.id) > 0 && c.ranks.indexOf(target) < c.ranks.indexOf(rank)

    fun reassign(c: Country, rank: RankDef, target: RankDef) {
        c.members.values.filter { it.rank == rank.id }.forEach { it.changeRank(target.id, c) }
    }

    fun groupPerms(text: String): Set<Perm> {
        val perms = PermCodec.parse(text)
        if (!Perm.outsiderAllowed.containsAll(perms)) throw Fail("kami_claims.error.group_perm")
        return perms
    }

    private fun elevated(perms: Set<Perm>) = perms.filterTo(mutableSetOf()) { it.risk >= Risk.HIGH }

    fun reassignable(member: Member, dest: RankDef) {
        if (dest.fixed || member.rank == PRESIDENT) throw Fail("kami_claims.error.rank_fixed")
    }

    fun assignable(c: Country, actor: String, member: String, dest: RankDef) {
        val current = Permissions.rankOf(c, member) ?: throw Fail("kami_claims.error.not_member")
        below(c, actor, current.id)
        below(c, actor, dest.id)
        grantable(c, actor, elevated(dest.perms))
    }
}
