package kami.claims.gov

import kami.claims.*
import kami.claims.gov.ActionArgs.NEW
import kami.claims.research.Capacity
import kami.claims.research.Features
import kami.claims.service.Fail
import kami.claims.service.Housing
import kami.claims.service.Oversight
import kami.claims.service.Service
import kami.claims.service.Words
import kami.claims.service.Words.money
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.claims.service.Work
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer

object JobActions {
    const val MAX_JOBS = 12
    private const val MAX_TERM_DAYS = 30
    private const val MAX_CONTRACTS = 60
    private const val MAX_PENDING_PER_OFFERER = 10
    private val s get() = Config.s

    fun act(p: ServerPlayer, name: String, args: List<String>): Phrase? = when (name) {
        "job_define" -> define(p, args)
        "job_tier" -> tier(p, args)
        "job_toggle" -> toggle(p, args)
        "job_delete" -> delete(p, args)
        "job_add" -> add(p, args)
        "job_remove" -> remove(p, args)
        "job_take" -> take(p, args)
        "assign" -> assign(p, args)
        "unassign" -> unassign(p, args)
        "contract_offer" -> offer(p, args)
        "contract_accept" -> accept(p, args)
        "contract_cancel" -> cancel(p, args)
        else -> null
    }

    private fun president(c: Country, id: String) = Permissions.positionOf(c, id) == 0

    private fun exempt(c: Country, id: String) = president(c, id) && c.government.levers.treasury != TreasuryControl.COUNCIL

    private fun holdsOther(c: Country, me: String, jobId: String) = me in Work.holders(c, jobId) && !exempt(c, me)

    private fun requireMember(c: Country, me: String, job: JobSpec, pay: Int) {
        if (me !in c.members && pay > 0 && job.definedBy !in c.members) throw Fail("kami_claims.error.job_delegated")
    }

    private fun define(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.DEFINE_JOBS)
        val me = p.stringUUID
        val existing = ActionArgs.arg(a, 0).takeIf { it != NEW }?.let { ActionArgs.job(c, it) }
        if (existing == null && c.jobDefs.size >= MAX_JOBS) throw Fail("kami_claims.error.job_limit", num(MAX_JOBS))
        val scope = JobArgs.scope(a)
        if (scope == JobScope.SECTOR && !Oversight.may(c, p, Perm.SCOPE_JOBS)) throw Fail("kami_claims.error.permission")
        val draft = JobArgs.spec(c, a, existing, scope, JobRules.scopeRef(c, scope, JobArgs.ref(a)), me)
        if (existing != null && holdsOther(c, me, existing.id) && JobRules.altersEarnings(existing, draft)) throw Fail("kami_claims.error.job_self")
        if (!JobRules.mayGrant(c, me, draft.perms - existing?.perms.orEmpty())) throw Fail("kami_claims.error.permission")
        withinBudget(c, Work.forecast(c, draft), existing?.let { Work.forecast(c, it) } ?: 0)
        c.jobDefs[draft.id] = draft
        Audit.record(c, me, "job_define", draft.id, JobRules.describe(draft))
        return Phrase.of("kami_claims.done.job_defined", v(draft.name))
    }

    private fun withinBudget(c: Country, forecast: Long, unchangedUpTo: Long = 0) {
        val budget = Work.dailyBudget(c, Housing.income(c))
        if (forecast > budget && forecast > unchangedUpTo) throw Fail("kami_claims.error.job_budget", money(forecast), money(budget))
    }

    private fun tier(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.DEFINE_JOBS)
        if (c.government.levers.labour != Labour.WAGE) throw Fail("kami_claims.error.job_tier_labour")
        val me = p.stringUUID
        val job = ActionArgs.job(c, ActionArgs.arg(a, 0))
        val tier = ActionArgs.choice<PayTier>(ActionArgs.arg(a, 1))
        val base = job.basePay.takeIf { it > 0 } ?: job.pay
        val pay = JobRules.tierPay(base, tier)
        if (pay != job.pay && holdsOther(c, me, job.id)) throw Fail("kami_claims.error.job_self")
        val before = Work.forecast(c, job)
        val previous = job.pay
        job.pay = pay
        try {
            withinBudget(c, Work.forecast(c, job), before)
        } catch (e: Fail) {
            job.pay = previous
            throw e
        }
        job.basePay = base
        if (pay != previous) job.changedDay = today()
        Audit.record(c, me, "job_tier", job.id, tier.name.lowercase())
        return Phrase.of("kami_claims.done.job_tier", v(job.name), money(pay))
    }

    private fun toggle(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.DEFINE_JOBS)
        val job = ActionArgs.job(c, ActionArgs.arg(a, 0))
        if (!job.active) {
            if (job.scope == JobScope.SECTOR && job.scopeRef !in c.sectors) throw Fail("kami_claims.error.sector_unknown")
            withinBudget(c, Work.forecast(c, job))
        }
        job.active = !job.active
        Audit.record(c, p.stringUUID, "job_toggle", job.id, job.active.toString())
        return Phrase.of(if (job.active) "kami_claims.done.job_enabled" else "kami_claims.done.job_disabled", v(job.name))
    }

    private fun delete(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.DEFINE_JOBS)
        val job = ActionArgs.job(c, ActionArgs.arg(a, 0))
        if (holdsOther(c, p.stringUUID, job.id)) throw Fail("kami_claims.error.job_self")
        val holders = Work.holders(c, job.id)
        if (!Work.affordable(c, holders.map { it to job.id })) throw Fail("kami_claims.error.job_unaffordable")
        holders.forEach { Work.release(c, it, job.id) }
        c.contracts.removeAll { it.job == job.id }
        c.jobDefs.remove(job.id)
        Audit.record(c, p.stringUUID, "job_delete", job.id, job.name)
        return Phrase.of("kami_claims.done.job_deleted", v(job.name))
    }

    private fun commands(c: Country, me: String, id: String) = me !in c.members || id == me || Permissions.outranks(c, me, id)

    private fun grant(c: Country, id: String, job: JobSpec, pay: Int = job.pay) {
        val m = ActionArgs.member(c, id)
        if (job.id in m.jobs) throw Fail("kami_claims.error.job_has")
        if (!job.active) throw Fail("kami_claims.error.job_inactive")
        JobRules.seasoned(m, pay)
        if (job.definedBy == id && JobRules.mayPay(job, c.government.levers.labour) && !exempt(c, id)) throw Fail("kami_claims.error.job_self")
        if (job.scope == JobScope.TYPE) Features.require(c, Features.claimType(job.scopeRef))
        Features.requireRoom(c, Capacity.JOB_SLOTS, m.jobs.size)
        if (pay > 0) withinBudget(c, Work.forecast(c, job, hiring = 1))
        Work.give(c, id, job.id)
        Mail.direct(id, Phrase.of("kami_claims.mail.job", Words.job(job.name)))
    }

    private fun add(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.ASSIGN_JOBS)
        val me = p.stringUUID
        val id = ActionArgs.uuid(a, 0)
        val job = ActionArgs.job(c, ActionArgs.arg(a, 1))
        if (job.assignment == Assignment.CONTRACT) throw Fail("kami_claims.error.job_contract_only")
        requireMember(c, me, job, job.pay)
        if (!commands(c, me, id)) throw Fail("kami_claims.error.rank_below")
        if (id == me && JobRules.mayPay(job, c.government.levers.labour) && !exempt(c, me)) throw Fail("kami_claims.error.job_self")
        grant(c, id, job)
        Audit.record(c, me, "job_add", id, job.id)
        return Phrase.of("kami_claims.done.job_assigned", Words.job(job.name))
    }

    private fun remove(p: ServerPlayer, a: List<String>): Phrase {
        val me = p.stringUUID
        val id = ActionArgs.uuid(a, 0)
        val c = if (id == me) Service.home(p) else Service.need(p, Perm.ASSIGN_JOBS)
        val job = ActionArgs.job(c, ActionArgs.arg(a, 1))
        if (job.id !in ActionArgs.member(c, id).jobs) throw Fail("kami_claims.error.job_missing")
        if (!commands(c, me, id)) throw Fail("kami_claims.error.rank_below")
        if (id != me && !Work.affordable(c, listOf(id to job.id))) throw Fail("kami_claims.error.job_unaffordable")
        Work.release(c, id, job.id, paid = id != me)
        Audit.record(c, me, "job_remove", id, job.id)
        return Phrase.of("kami_claims.done.job_removed")
    }

    private fun take(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.home(p)
        val job = ActionArgs.job(c, ActionArgs.arg(a, 0))
        if (job.assignment != Assignment.OPEN) throw Fail("kami_claims.error.job_open_only")
        if (Work.coolingDown(c, p.stringUUID, job)) throw Fail("kami_claims.error.job_cooldown")
        grant(c, p.stringUUID, job)
        return Phrase.of("kami_claims.done.job_assigned", Words.job(job.name))
    }

    private fun chunkOf(p: ServerPlayer, c: Country, a: List<String>): Claim {
        val dim = a.getOrNull(3)?.takeIf { it.isNotBlank() } ?: p.level().dimension().location().toString()
        val x = a.getOrNull(1)?.toIntOrNull() ?: p.chunkPosition().x
        val z = a.getOrNull(2)?.toIntOrNull() ?: p.chunkPosition().z
        return Realm.at(dim, x, z)?.takeIf { it.country == c.id } ?: throw Fail("kami_claims.error.not_your_chunk")
    }

    private fun assign(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.ASSIGN_CHUNKS)
        val id = ActionArgs.uuid(a, 0)
        ActionArgs.member(c, id)
        val cl = chunkOf(p, c, a)
        if (!Work.holds(c, id, cl)) throw Fail("kami_claims.error.job_cover", v("${cl.x}, ${cl.z}"))
        cl.workers += id
        Mail.direct(id, Phrase.of("kami_claims.mail.assigned", v(cl.type), v("${cl.x}, ${cl.z}")))
        Audit.record(c, p.stringUUID, "chunk_assign", id, "${cl.x},${cl.z}")
        return Phrase.of("kami_claims.done.assigned")
    }

    private fun unassign(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.ASSIGN_CHUNKS)
        val me = p.stringUUID
        val id = ActionArgs.uuid(a, 0)
        if (c.members[id] != null && !commands(c, me, id)) throw Fail("kami_claims.error.rank_below")
        val cl = chunkOf(p, c, a)
        if (cl.workers.remove(id) && id != me) Mail.direct(id, Phrase.of("kami_claims.mail.unassigned", v("${cl.x}, ${cl.z}")))
        Audit.record(c, me, "chunk_unassign", id, "${cl.x},${cl.z}")
        return Phrase.of("kami_claims.done.unassigned")
    }

    private fun offer(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.ASSIGN_JOBS)
        val me = p.stringUUID
        if (c.government.levers.labour != Labour.MARKET) throw Fail("kami_claims.error.contract_market")
        val job = ActionArgs.job(c, ActionArgs.arg(a, 0))
        val id = ActionArgs.uuid(a, 1)
        if (id == me) throw Fail("kami_claims.error.contract_self")
        ActionArgs.member(c, id)
        if (!commands(c, me, id)) throw Fail("kami_claims.error.rank_below")
        if (job.assignment != Assignment.CONTRACT || !job.active) throw Fail("kami_claims.error.job_inactive")
        if (c.contracts.size >= MAX_CONTRACTS || c.contracts.count { it.offeredBy == me && !it.accepted } >= MAX_PENDING_PER_OFFERER || c.contracts.any { it.job == job.id && it.player == id }) throw Fail("kami_claims.error.contract_dup")
        val pay = ActionArgs.int(a, 2).coerceIn(0, job.pay)
        requireMember(c, me, job, pay)
        val quota = ActionArgs.int(a, 3).coerceAtLeast(0)
        JobRules.validate(quota, job.payModel, job.assignment, c.government.levers.labour)
        val pending = c.contracts.filter { !it.accepted }.sumOf { it.pay.toLong() }
        withinBudget(c, Work.payroll(c) + pending + pay)
        val k = Contract(
            ActionArgs.newId(), job.id, id, me, pay, quota, ActionArgs.int(a, 4).coerceIn(1, JobRules.MAX_PERIOD),
            today() + ActionArgs.int(a, 5).coerceIn(1, MAX_TERM_DAYS)
        )
        c.contracts += k
        Mail.direct(id, Phrase.of("kami_claims.mail.contract_offered", Words.job(job.name), money(pay)))
        Audit.record(c, me, "contract_offer", id, job.id)
        return Phrase.of("kami_claims.done.contract_offered")
    }

    private fun contract(c: Country, id: String) = c.contracts.firstOrNull { it.id == id } ?: throw Fail("kami_claims.error.contract_missing")

    private fun accept(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.home(p)
        val k = contract(c, ActionArgs.arg(a, 0))
        if (k.player != p.stringUUID || k.accepted || k.until <= today()) throw Fail("kami_claims.error.contract_missing")
        val job = ActionArgs.job(c, k.job)
        k.pay = minOf(k.pay, job.pay)
        grant(c, k.player, job, k.pay)
        k.accepted = true
        Mail.direct(k.offeredBy, Phrase.of("kami_claims.mail.contract_accepted", v(Names.of(p.server, k.player)), Words.job(job.name)))
        Audit.record(c, k.player, "contract_accept", k.id, job.id)
        return Phrase.of("kami_claims.done.contract_accepted", Words.job(job.name))
    }

    private fun mayCancel(c: Country, me: String, k: Contract) =
        (k.offeredBy == me || president(c, me)) && Permissions.holds(c, me, Perm.ASSIGN_JOBS) && Permissions.outranks(c, me, k.player)

    private fun cancel(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.home(p)
        val me = p.stringUUID
        val k = contract(c, ActionArgs.arg(a, 0))
        val declined = k.player == me && !k.accepted
        if (!declined && !mayCancel(c, me, k)) throw Fail("kami_claims.error.permission")
        if (k.accepted && !Work.affordable(c, listOf(k.player to k.job))) throw Fail("kami_claims.error.job_unaffordable")
        if (k.accepted) Work.release(c, k.player, k.job)
        c.contracts.remove(k)
        Mail.direct(k.player, Phrase.of("kami_claims.mail.contract_cancelled", Words.job(c.jobDefs[k.job]?.name ?: k.job)), Tone.WARN)
        Audit.record(c, me, "contract_cancel", k.id, k.job)
        return Phrase.of("kami_claims.done.contract_cancelled")
    }
}
