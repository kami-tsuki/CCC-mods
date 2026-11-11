package kami.claims.gov

import kami.claims.Country

object RankMigration {
    private const val CHANCELLOR_ID = "chancellor"
    private const val OFFICER_ID = "officer"
    private const val CHANCELLOR_PLOTS = 9
    private const val OFFICER_PLOTS = 4
    private val officerPerms = RoleTemplate.MEMBER.perms + setOf(Perm.INVITE, Perm.KICK, Perm.BANISH, Perm.PARDON, Perm.ALLIES, Perm.HOUSING, Perm.CHUNK_RULES, Perm.VIEW_JOBS, Perm.ASSIGN_JOBS, Perm.DEFINE_JOBS, Perm.ASSIGN_CHUNKS, Perm.SCOPE_JOBS)
    private val chancellorPerms = officerPerms + setOf(Perm.CLAIM, Perm.UNCLAIM, Perm.CHUNK_TYPE, Perm.SECTORS, Perm.TAXES, Perm.WITHDRAW, Perm.SPEND, Perm.TRADE, Perm.ASSIGN_RANKS, Perm.EDIT_RANKS, Perm.CREATE_RANKS, Perm.REORDER_RANKS, Perm.EDIT_GROUPS, Perm.DETAILS, Perm.PROVINCES, Perm.RESEARCH, Perm.DIPLOMACY, Perm.AUDIT, Perm.DELEGATED)

    fun seed(c: Country) {
        if (c.ranks.isEmpty()) plant(c)
        normalize(c)
    }

    fun normalize(c: Country) {
        c.members.values.filter { member -> c.ranks.none { it.id == member.rank } }.forEach { it.rank = CITIZEN }
    }

    fun keepUnknownJobs(c: Country) {
        c.members.values.flatMap { it.jobs.keys }.filter { it !in c.jobDefs }.forEach { id ->
            c.jobDefs[id] = JobSpec(id, id.replaceFirstChar { it.uppercase() }, scope = JobScope.COUNTRY, quota = 1, active = false)
        }
    }

    private fun plant(c: Country) {
        c.ranks += listOf(
            RankDef(PRESIDENT, "President", 0x1F4E79, plots = RankPlots.PRESIDENT),
            RankDef(CHANCELLOR_ID, "Chancellor", 0x2E75B6, chancellorPerms.toMutableSet(), CHANCELLOR_PLOTS),
            RankDef(OFFICER_ID, "Officer", 0x5B9BD5, officerPerms.toMutableSet(), OFFICER_PLOTS),
            RankDef(CITIZEN, "Citizen", 0xA6A6A6, RoleTemplate.MEMBER.perms.toMutableSet(), RankPlots.CITIZEN)
        )
        c.members.values.forEach { it.rank = it.rank.lowercase() }
    }
}
