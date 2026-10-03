package kami.claims.service

import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.economy.Treasury
import kami.claims.research.Capacity
import kami.claims.research.Features
import kami.claims.research.Progress
import kami.claims.service.Words.money
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import java.util.UUID
import kotlin.math.floor

object Work {
    private val unpaidMailed = HashMap<String, Long>()

    fun reset() = unpaidMailed.clear()

    fun holds(m: Member, type: String) = m.jobs.keys.any { Config.s.jobs[it]?.type == type }

    fun fits(c: Country, cl: Claim, id: String): Boolean {
        val m = c.members[id] ?: return false
        return cl.def?.job != null && id in cl.workers && holds(m, cl.type)
    }

    fun matching(c: Country, id: String, cl: Claim): List<Pair<JobCfg, Job>> {
        val m = c.members[id] ?: return emptyList()
        return m.jobs.mapNotNull { (name, job) -> Config.s.jobs[name]?.takeIf { it.type == cl.type }?.let { it to job } }
    }

    fun give(c: Country, id: String, job: String) {
        c.members.getValue(id).jobs.getOrPut(job) { Job() }
    }

    fun take(c: Country, id: String, job: String) {
        val m = c.members[id] ?: return
        m.jobs.remove(job)
        val type = Config.s.jobs[job]?.type ?: return
        if (!holds(m, type)) Realm.claims(c.id).filter { it.type == type }.forEach { it.workers -= id }
    }

    fun forget(c: Country, id: String) = Realm.claims(c.id).forEach { it.workers -= id }

    fun retyped(cl: Claim) = cl.workers.clear()

    fun jobAdd(c: Country, id: String, job: String): Phrase {
        val m = c.members[id] ?: throw Fail("kami_claims.error.not_member")
        val cfg = Config.s.jobs[job] ?: throw Fail("kami_claims.error.unknown_job")
        if (job in m.jobs) throw Fail("kami_claims.error.job_has")
        Features.require(c, Features.claimType(cfg.type))
        Features.requireRoom(c, Capacity.JOB_SLOTS, m.jobs.size)
        give(c, id, job)
        Mail.direct(id, Phrase.of("kami_claims.mail.job", Words.job(job)))
        return Phrase.of("kami_claims.done.job_assigned", Words.job(job))
    }

    fun jobRemove(c: Country, id: String, job: String): Phrase {
        val m = c.members[id] ?: throw Fail("kami_claims.error.not_member")
        if (job !in m.jobs) throw Fail("kami_claims.error.job_missing")
        take(c, id, job)
        return Phrase.of("kami_claims.done.job_removed")
    }

    fun assign(c: Country, id: String, cl: Claim): Phrase {
        val m = c.members[id] ?: throw Fail("kami_claims.error.not_member")
        if (cl.def?.job == null) throw Fail("kami_claims.error.job_none_here")
        if (!holds(m, cl.type)) throw Fail("kami_claims.error.job_needed", Words.job(cl.def!!.job!!))
        cl.workers += id
        Mail.direct(id, Phrase.of("kami_claims.mail.assigned", Words.job(cl.def!!.job!!), v("${cl.x}, ${cl.z}")))
        return Phrase.of("kami_claims.done.assigned")
    }

    fun unassign(c: Country, actor: Rank, actorId: String, id: String, cl: Claim): Phrase {
        val rank = c.members[id]?.rank
        if (id != actorId && rank != null && rank >= actor) throw Fail("kami_claims.error.lower_ranks")
        if (cl.workers.remove(id) && id != actorId) Mail.direct(id, Phrase.of("kami_claims.mail.unassigned", v("${cl.x}, ${cl.z}")))
        return Phrase.of("kami_claims.done.unassigned")
    }

    fun payroll(c: Country): Long = c.members.values.sumOf { m -> m.jobs.keys.sumOf { (c.job(it)?.pay ?: 0).toLong() } }

    fun pay(c: Country, day: Long, income: Long) {
        var budget = floor(income * Config.s.jobShare).toLong()
        c.members.forEach { (id, m) ->
            m.jobs.forEach { (name, job) ->
                val def = c.job(name) ?: return@forEach
                if (day - job.start < def.period) return@forEach
                val due = job.progress >= def.quota
                val affordable = def.pay <= budget && def.pay <= c.treasury
                val paid = due && affordable && run {
                    Treasury.move(c, LedgerKind.JOB_PAY, -def.pay.toLong(), id, name)
                    Bank.give(UUID.fromString(id), def.pay).also { ok -> if (!ok) Treasury.move(c, LedgerKind.JOB_PAY, def.pay.toLong(), id, name) }
                }
                if (due && affordable && !paid) {
                    if (unpaidMailed.put(id, today()) != today()) Mail.direct(id, Phrase.of("kami_claims.mail.wage_unpaid", Words.job(name)), Tone.BAD)
                    return@forEach
                }
                job.progress = 0
                job.start = day
                if (!due) return@forEach
                if (paid) {
                    budget -= def.pay
                    if (def.pay > 0) Progress.report(c, "wage", "", 1)
                    Mail.direct(id, Phrase.of("kami_claims.mail.wage_paid", money(def.pay), Words.job(name)), Tone.OK)
                } else Mail.direct(id, Phrase.of("kami_claims.mail.wage_unpaid", Words.job(name)), Tone.BAD)
            }
        }
    }
}
