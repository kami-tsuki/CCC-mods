package kami.claims.gov

import kami.claims.Country
import kami.claims.Realm
import kami.claims.gov.ActionArgs.NEW
import kami.claims.gov.ActionArgs.arg
import kami.claims.gov.ActionArgs.int
import kami.claims.admin.CountryRequests
import kami.claims.admin.GovernanceDetail
import kami.claims.net.GovSync
import kami.claims.research.Capacity
import kami.claims.research.Levels
import kami.claims.service.Fail
import kami.claims.service.Housing
import kami.claims.service.Words
import kami.claims.service.Work
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import net.minecraft.server.MinecraftServer

object AdminGovernance {
    private const val ADMIN_AUDIT_LINES = 100

    val handlers: Map<String, (Country, String, List<String>) -> Unit> = mapOf(
        CountryRequests.GOV_SET to ::setGovernment,
        CountryRequests.RANK_CREATE to ::createRank,
        CountryRequests.RANK_EDIT to ::editRank,
        CountryRequests.RANK_PERMS to ::rankPerms,
        CountryRequests.RANK_PLOTS to ::rankPlots,
        CountryRequests.RANK_MOVE to ::moveRank,
        CountryRequests.RANK_DELETE to ::deleteRank,
        CountryRequests.RANK_ASSIGN to ::assignRank,
        CountryRequests.GROUP_PERMS to ::groupPerms,
        CountryRequests.JOB_SAVE to ::saveJob,
        CountryRequests.JOB_DELETE to ::deleteJob,
        CountryRequests.JOB_ASSIGN to ::assignJob,
        CountryRequests.JOB_REMOVE to ::removeJob,
        CountryRequests.CONTRACT_CANCEL to ::cancelContract,
        CountryRequests.SECTOR_SAVE to ::saveSector,
        CountryRequests.SECTOR_DELETE to ::deleteSector
    )

    fun detail(server: MinecraftServer, c: Country) = GovernanceDetail(
        GovSync.government(c), GovSync.ranks(c), GovSync.groups(c), GovSync.jobSpecs(c), GovSync.contracts(c), GovSync.sectors(c),
        GovSync.audit(server, c, ADMIN_AUDIT_LINES),
        Levels.capacity(c, Capacity.RANKS)
    )

    fun revision(c: Country): List<Any?> = listOf(c.government.preset, c.government.levers, c.audit.size, c.audit.lastOrNull()?.at)

    private fun done(c: Country, actor: String, action: String, target: String = "", detail: String = "", territory: Boolean = false) {
        Audit.record(c, actor, "admin_$action", target, detail)
        Realm.changed(territory = territory)
    }

    private fun setGovernment(c: Country, actor: String, a: List<String>) {
        val preset = govChoice<PresetId>(a, 0)
        val levers = Levers.parse(a, 1)
        val reset = RESET_RANKS in a.drop(9)
        GovernmentActions.requireStructure(c, levers, reset)
        c.government.switch(c, preset, levers, reset)
        done(c, actor, "gov_set", preset.key, "$levers reset=$reset")
    }

    private fun createRank(c: Country, actor: String, a: List<String>) {
        val name = RankRules.name(c, arg(a, 0))
        val color = int(a, 1)
        val perms = PermCodec.parse(a.getOrElse(2) { "" })
        val plots = a.getOrNull(3)?.let { int(a, 3) } ?: 0
        RankRules.color(color)
        RankRules.plots(plots)
        val index = RankRules.room(c)
        val rank = RankDef(ActionArgs.newId(), name, color, perms.toMutableSet(), plots)
        Housing.repriced(c) { c.ranks.add(index, rank) }
        done(c, actor, "rank_create", rank.id, name)
    }

    private fun editRank(c: Country, actor: String, a: List<String>) {
        val rank = RankRules.rank(c, arg(a, 0))
        val name = RankRules.name(c, arg(a, 1), rank)
        val color = int(a, 2)
        RankRules.color(color)
        rank.name = name
        rank.color = color
        done(c, actor, "rank_edit", rank.id, name)
    }

    private fun rankPerms(c: Country, actor: String, a: List<String>) {
        val rank = RankRules.unfixed(c, arg(a, 0))
        val perms = PermCodec.parse(a.getOrElse(1) { "" })
        val change = PermCodec.diff(perms - rank.perms, rank.perms - perms)
        rank.perms.clear()
        rank.perms.addAll(perms)
        done(c, actor, "rank_perms", rank.id, change)
    }

    private fun rankPlots(c: Country, actor: String, a: List<String>) {
        val rank = RankRules.rank(c, arg(a, 0))
        val plots = int(a, 1)
        RankRules.plots(plots)
        rank.plots = plots
        done(c, actor, "rank_plots", rank.id, plots.toString())
    }

    private fun moveRank(c: Country, actor: String, a: List<String>) {
        val rank = RankRules.unfixed(c, arg(a, 0))
        val to = RankRules.slot(c, rank, int(a, 1))
        Housing.repriced(c) {
            c.ranks.remove(rank)
            c.ranks.add(to, rank)
        }
        done(c, actor, "rank_move", rank.id, to.toString())
    }

    private fun deleteRank(c: Country, actor: String, a: List<String>) {
        val rank = RankRules.unfixed(c, arg(a, 0))
        val target = RankRules.successor(c, rank, a.getOrNull(1)?.takeIf { it.isNotBlank() })
        RankRules.removable(rank, target)
        Housing.repriced(c) {
            RankRules.reassign(c, rank, target)
            c.ranks.remove(rank)
        }
        done(c, actor, "rank_delete", rank.id, target.id)
    }

    private fun assignRank(c: Country, actor: String, a: List<String>) {
        val id = ActionArgs.uuid(a, 0)
        val member = ActionArgs.member(c, id)
        val dest = RankRules.rank(c, arg(a, 1))
        RankRules.reassignable(member, dest)
        Housing.repriced(c) { member.changeRank(dest.id, c) }
        done(c, actor, "rank_assign", id, dest.id)
    }

    private fun groupPerms(c: Country, actor: String, a: List<String>) {
        val claimant = Permissions.group(arg(a, 0))
        val perms = RankRules.groupPerms(a.getOrElse(1) { "" })
        val current = c.groups[claimant].orEmpty()
        c.groups[claimant] = perms.toMutableSet()
        done(c, actor, "group_perms", claimant.name, PermCodec.diff(perms - current, current - perms))
    }

    private fun job(c: Country, id: String) = c.jobDefs[id] ?: throw Fail("kami_claims.error.unknown_job")

    private fun saveJob(c: Country, actor: String, a: List<String>) {
        val existing = arg(a, 0).takeIf { it != NEW }?.let { job(c, it) }
        val scope = JobArgs.scope(a)
        val spec = JobArgs.spec(c, a, existing, scope, JobRules.scopeRef(c, scope, JobArgs.ref(a)))
        c.jobDefs[spec.id] = spec
        done(c, actor, "job_save", spec.id, spec.name)
    }

    private fun deleteJob(c: Country, actor: String, a: List<String>) {
        val job = job(c, arg(a, 0))
        Work.holders(c, job.id).forEach { Work.release(c, it, job.id, paid = false) }
        c.contracts.removeAll { it.job == job.id }
        c.jobDefs.remove(job.id)
        done(c, actor, "job_delete", job.id, job.name)
    }

    private fun assignJob(c: Country, actor: String, a: List<String>) {
        val id = ActionArgs.uuid(a, 0)
        val member = ActionArgs.member(c, id)
        val job = job(c, arg(a, 1))
        if (job.id in member.jobs) throw Fail("kami_claims.error.job_has")
        Work.give(c, id, job.id)
        done(c, actor, "job_assign", id, job.id)
    }

    private fun removeJob(c: Country, actor: String, a: List<String>) {
        val id = ActionArgs.uuid(a, 0)
        val job = job(c, arg(a, 1))
        if (job.id !in ActionArgs.member(c, id).jobs) throw Fail("kami_claims.error.job_missing")
        Work.release(c, id, job.id, paid = false)
        done(c, actor, "job_remove", id, job.id)
    }

    private fun cancelContract(c: Country, actor: String, a: List<String>) {
        val contract = c.contracts.firstOrNull { it.id == arg(a, 0) } ?: throw Fail("kami_claims.error.contract_missing")
        if (contract.accepted) Work.release(c, contract.player, contract.job, paid = false)
        c.contracts.remove(contract)
        Mail.direct(contract.player, Phrase.of("kami_claims.mail.contract_cancelled", Words.job(c.jobDefs[contract.job]?.name ?: contract.job)), Tone.WARN)
        done(c, actor, "contract_cancel", contract.id, contract.job)
    }

    private fun sector(c: Country, id: String) = Sectors.get(c, id) ?: throw Fail("kami_claims.error.sector_unknown")

    private fun saveSector(c: Country, actor: String, a: List<String>) {
        val existing = arg(a, 0).takeIf { it != NEW }?.let { sector(c, it) }
        val saved = Sectors.save(c, existing, arg(a, 1), ActionArgs.color(a, 2), arg(a, 3), a.getOrNull(4).orEmpty())
        done(c, actor, "sector_save", saved.id, saved.name, territory = true)
    }

    private fun deleteSector(c: Country, actor: String, a: List<String>) {
        val s = sector(c, arg(a, 0))
        Sectors.dissolve(c, s)
        done(c, actor, "sector_delete", s.id, s.name, territory = true)
    }
}
