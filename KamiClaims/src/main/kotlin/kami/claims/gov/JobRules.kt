package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.Member
import kami.claims.now
import kami.claims.research.Features
import kami.claims.service.Fail

enum class PayTier(val percent: Int) { LOW(75), STANDARD(100), HIGH(125) }

internal object JobRules {
    const val MAX_TASKS = 4
    const val MAX_TASK_CAP = 4096
    const val MAX_PERIOD = 30
    private const val MAX_FILTER = 200
    private const val MIN_MEMBER_DAYS = 2
    private val filterless = setOf(TaskKind.HERD, TaskKind.FISH)
    private val filtered = setOf(TaskKind.BUILD, TaskKind.CRAFT, TaskKind.SMELT)

    fun scopeRef(c: Country, scope: JobScope, ref: String): String = when (scope) {
        JobScope.COUNTRY -> ""
        JobScope.TYPE -> ref.also {
            if (it !in SectorRules.workTypes) throw Fail("kami_claims.error.sector_type")
            Features.require(c, Features.claimType(it))
        }
        JobScope.SECTOR -> ref.also { if (Sectors.get(c, it) == null) throw Fail("kami_claims.error.sector_unknown") }
    }

    fun seasoned(member: Member, pay: Int) {
        if (pay > 0 && now() - member.since < MIN_MEMBER_DAYS * Config.s.dayMillis) throw Fail("kami_claims.error.job_new_member")
    }

    fun tierPay(base: Int, tier: PayTier) = (base.toLong() * tier.percent / 100).toInt().coerceIn(0, Config.s.maxJobPay)

    fun mayPay(job: JobSpec, labour: Labour) = job.pay > 0 || labour == Labour.QUOTA || labour == Labour.DUTY

    fun altersEarnings(old: JobSpec, new: JobSpec) =
        old.pay != new.pay || old.quota != new.quota || old.period != new.period || old.payModel != new.payModel ||
            old.assignment != new.assignment || old.scope != new.scope || old.scopeRef != new.scopeRef || signature(old) != signature(new)

    private fun signature(job: JobSpec) = job.tasks.map { Triple(it.kind, it.filter, it.cap) }

    fun tasks(text: String, scope: JobScope): MutableList<TaskSpec> {
        val list = text.split(';').filter { it.isNotBlank() }.map { task(it, scope) }
        if (list.size !in 1..MAX_TASKS) throw Fail("kami_claims.error.job_tasks")
        return list.toMutableList()
    }

    fun assignments(labour: Labour): Set<Assignment> = when (labour) {
        Labour.MARKET -> setOf(Assignment.CONTRACT)
        Labour.WAGE, Labour.QUOTA -> setOf(Assignment.OPEN, Assignment.APPOINTED)
        Labour.DUTY -> setOf(Assignment.APPOINTED)
    }

    fun payModels(labour: Labour): Set<PayModel> = if (labour == Labour.QUOTA) setOf(PayModel.RATION) else setOf(PayModel.FIXED, PayModel.PIECE)

    fun validate(quota: Int, payModel: PayModel, assignment: Assignment, labour: Labour) {
        if (payModel !in payModels(labour) || assignment !in assignments(labour)) throw Fail("kami_claims.error.job_matrix")
        quota(quota, payModel, labour)
    }

    val grantable = setOf(Perm.NEWS_WRITE)

    fun perms(text: String): Set<Perm> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { token ->
        grantable.firstOrNull { it.name.equals(token, true) } ?: throw Fail("kami_claims.error.job_perms")
    }.toSet()

    fun mayGrant(c: Country, actor: String, added: Set<Perm>) = added.all { Permissions.holds(c, actor, it) }

    fun describe(job: JobSpec) = if (job.perms.isEmpty()) job.name else job.perms.joinToString(",", "${job.name} [", "]") { it.name }

    private fun quota(quota: Int, payModel: PayModel, labour: Labour) {
        if (payModel == PayModel.PIECE && quota == 0) throw Fail("kami_claims.error.job_piece_quota")
        if (quota == 0 && payModel != PayModel.RATION && labour != Labour.DUTY) throw Fail("kami_claims.error.job_quota")
    }

    private fun task(entry: String, scope: JobScope): TaskSpec {
        val parts = entry.split(':')
        val kind = ActionArgs.choice<TaskKind>(parts.first().trim())
        val filter = if (parts.size > 2 && kind !in filterless) parts.subList(1, parts.size - 1).joinToString(":").trim() else ""
        val cap = parts.takeIf { it.size > 1 }?.last()?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: throw Fail("kami_claims.error.number") } ?: 0
        val needsFilter = kind in filtered && scope != JobScope.SECTOR
        if (filter.length > MAX_FILTER || !validFilter(filter) || needsFilter && filter.isBlank()) throw Fail("kami_claims.error.task_filter")
        return TaskSpec(kind, filter, if (cap <= 0) MAX_TASK_CAP else cap.coerceAtMost(MAX_TASK_CAP))
    }

    private val resourceId = Regex("^([a-z0-9_.-]+:)?[a-z0-9_./-]+$")

    fun validFilter(filter: String) =
        filter.split(',').map { it.trim() }.filter { it.isNotEmpty() }.all { resourceId.matches(it.removePrefix("#")) && ".." !in it }
}
