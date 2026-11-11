package kami.claims.service

import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.economy.Treasury
import kami.claims.gov.JobRules
import kami.claims.gov.JobScope
import kami.claims.gov.JobSpec
import kami.claims.gov.Labour
import kami.claims.gov.Land
import kami.claims.gov.PayModel
import kami.claims.gov.Sectors
import kami.claims.gov.TaskKind
import kami.claims.gov.TreasuryControl
import kami.claims.research.Progress
import kami.claims.service.Words.money
import kami.claims.war.ChunkRef
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.economy.CountryParty
import kami.libs.economy.Flow
import kami.libs.text.Phrase
import java.util.UUID
import kotlin.math.floor

object Work {
    class Terms(val pay: Int, val quota: Int, val period: Int)

    private const val BUDGET_DAYS = 7
    private const val TREASURY_DAYS = 30
    private const val BUDGET_JOB_BONUS = 0.2
    private const val EXPIRY_SETTLE_DAYS = 7

    private class Due(val id: String, val spec: JobSpec, val job: Job, val amount: Int)

    private val unpaidMailed = HashMap<String, Long>()
    private val actions = mapOf(
        TaskKind.HARVEST to setOf(Action.BREAK, Action.INTERACT, Action.CONTAINER),
        TaskKind.MINE to setOf(Action.BREAK, Action.INTERACT, Action.CONTAINER),
        TaskKind.CHOP to setOf(Action.BREAK, Action.INTERACT, Action.CONTAINER),
        TaskKind.BUILD to setOf(Action.BREAK, Action.PLACE),
        TaskKind.CRAFT to setOf(Action.INTERACT, Action.CONTAINER),
        TaskKind.SMELT to setOf(Action.INTERACT, Action.CONTAINER),
        TaskKind.HERD to setOf(Action.INTERACT),
        TaskKind.FISH to setOf(Action.INTERACT)
    )
    internal var wage: (String, Int, String) -> Boolean = { id, n, country -> Bank.give(UUID.fromString(id), n, Flow.WAGES, CountryParty(country).key) }

    fun reset() {
        unpaidMailed.clear()
        Credit.reset()
    }

    fun terms(c: Country, id: String, spec: JobSpec): Terms {
        c.contracts.firstOrNull { it.accepted && it.player == id && it.job == spec.id }?.let { return Terms(it.pay, it.quota, it.period) }
        val job = c.members[id]?.jobs?.get(spec.id)
        return Terms(snapshot(job?.pay, spec.pay), snapshot(job?.quota, spec.quota), snapshot(job?.period, spec.period))
    }

    private fun snapshot(held: Int?, current: Int) = held?.takeIf { it >= 0 } ?: current

    fun earned(t: Terms, progress: Int): Int = if (t.quota <= 0) 0 else (t.pay.toLong() * progress.coerceIn(0, t.quota) / t.quota).toInt()

    fun inScope(c: Country, spec: JobSpec, cl: Claim): Boolean = when (spec.scope) {
        JobScope.COUNTRY -> true
        JobScope.TYPE -> cl.type == spec.scopeRef
        JobScope.SECTOR -> c.sectors[spec.scopeRef]?.let { Sectors.usable(it) && ChunkRef(cl.dim, cl.x, cl.z) in it.chunks } == true
    }

    internal fun held(c: Country, id: String, cl: Claim): List<Pair<Job, JobSpec>> =
        c.members[id]?.jobs.orEmpty().mapNotNull { (jobId, job) -> c.jobDefs[jobId]?.takeIf { it.active && inScope(c, it, cl) }?.let { job to it } }

    fun holds(c: Country, id: String, cl: Claim) = held(c, id, cl).isNotEmpty()

    fun covers(c: Country, cl: Claim, playerId: String, action: Action) =
        held(c, playerId, cl).any { (_, spec) -> spec.tasks.any { actions[it.kind]?.contains(action) == true } }

    fun holders(c: Country, jobId: String) = c.members.filterValues { jobId in it.jobs }.keys

    fun give(c: Country, id: String, jobId: String) {
        val spec = c.jobDefs[jobId]
        c.members.getValue(id).jobs.getOrPut(jobId) { spec?.let { Job(quota = it.quota, period = it.period, pay = it.pay) } ?: Job() }
    }

    fun coolingDown(c: Country, id: String, spec: JobSpec) =
        c.members[id]?.leftJobs?.get(spec.id)?.let { today() - it < spec.period } == true

    fun drop(c: Country, id: String, jobId: String) {
        c.members[id]?.let { if (it.jobs.remove(jobId) != null) it.leftJobs[jobId] = today() }
        c.contracts.removeAll { it.player == id && it.job == jobId }
        Realm.claims(c.id).filter { id in it.workers && !holds(c, id, it) }.forEach { it.workers -= id }
    }

    fun release(c: Country, id: String, jobId: String, paid: Boolean = true) {
        if (paid) exitDue(c, id, jobId)?.let { payOut(c, it) }
        drop(c, id, jobId)
    }

    private fun exitDue(c: Country, id: String, jobId: String): Due? {
        val job = c.members[id]?.jobs?.get(jobId) ?: return null
        val spec = c.jobDefs[jobId] ?: return null
        return Due(id, spec, job, earned(terms(c, id, spec), job.progress))
    }

    fun affordable(c: Country, exits: Collection<Pair<String, String>>, day: Long = today(), income: Long = Housing.income(c)): Boolean {
        val owed = exits.sumOf { (id, jobId) -> exitDue(c, id, jobId)?.amount?.toLong() ?: 0L }
        return owed == 0L || owed <= dailyBudget(c, income) - spent(c, day) && owed <= c.treasury
    }

    private fun spent(c: Country, day: Long) = c.payBudget.takeIf { it.day >= day }?.spent ?: 0L

    private fun payOut(c: Country, d: Due) {
        if (d.amount == 0) return
        if (settle(c, d, today(), Housing.income(c))) Mail.direct(d.id, Phrase.of("kami_claims.mail.wage_paid", money(d.amount), Words.job(d.spec.name)), Tone.OK)
        else Mail.direct(d.id, Phrase.of("kami_claims.mail.wage_forfeited", Words.job(d.spec.name)), Tone.BAD)
    }

    private fun settle(c: Country, d: Due, day: Long, income: Long): Boolean {
        val owed = d.amount.toLong()
        if (owed == 0L) return true
        val spent = spent(c, day)
        if (owed > dailyBudget(c, income) - spent || owed > c.treasury || !transfer(c, d.id, d.spec, d.amount)) return false
        c.payBudget.day = maxOf(c.payBudget.day, day)
        c.payBudget.spent = spent + owed
        return true
    }

    fun forget(c: Country, id: String, reason: Leave) {
        val held = c.members[id]?.jobs?.keys.orEmpty()
        if (reason != Leave.LEAVE) held.mapNotNull { exitDue(c, id, it) }.forEach { payOut(c, it) }
        Realm.claims(c.id).forEach { it.workers -= id }
        c.contracts.removeAll { it.player == id || it.offeredBy == id && !it.accepted }
        c.sectors.values.filter { it.manager == id }.forEach { it.manager = null }
    }

    fun retyped(c: Country, cl: Claim) {
        cl.workers.clear()
        Sectors.chunkLeft(c, ChunkRef(cl.dim, cl.x, cl.z))
    }

    fun forecast(c: Country, replacing: JobSpec, hiring: Int = 0): Long =
        (c.jobDefs.values.filter { it.active && it.id != replacing.id } + replacing).sumOf { spec ->
            val holders = holders(c, spec.id)
            val seats = maxOf(1, holders.size + if (spec.id == replacing.id) hiring else 0)
            holders.sumOf { terms(c, it, spec).pay.toLong() } + spec.pay.toLong() * (seats - holders.size)
        }

    fun dailyBudget(c: Country, todayIncome: Long): Long {
        val inflow = c.history.takeLast(BUDGET_DAYS).map { it.income + it.tributeIn + it.deposits }
        val average = if (inflow.isEmpty()) 0L else inflow.average().toLong()
        val treasuryShare = if (c.government.levers.land.let { it == Land.STATE || it == Land.COMMUNAL }) c.treasury / TREASURY_DAYS else 0L
        return floor(maxOf(todayIncome, average, treasuryShare) * jobShare(c)).toLong()
    }

    private fun jobShare(c: Country): Double =
        if (c.government.levers.treasury == TreasuryControl.BUDGET) minOf(1.0, Config.s.jobShare + BUDGET_JOB_BONUS) else Config.s.jobShare

    fun payroll(c: Country): Long = c.members.entries.sumOf { (id, m) ->
        m.jobs.keys.sumOf { jobId -> c.jobDefs[jobId]?.takeIf { it.active }?.let { terms(c, id, it).pay.toLong() } ?: 0 }
    }

    fun pay(c: Country, day: Long, income: Long) {
        c.members.values.forEach { m -> m.leftJobs.values.removeAll { day - it >= JobRules.MAX_PERIOD } }
        unpaidMailed.values.removeAll { it < today() }
        expire(c, day, income)
        due(c, day).sortedBy { it.job.start }.forEach { d ->
            if (!settle(c, d, day, income)) {
                if (unpaidMailed.put(d.id, today()) != today()) Mail.direct(d.id, Phrase.of("kami_claims.mail.wage_unpaid", Words.job(d.spec.name)), Tone.BAD)
                return@forEach
            }
            if (missedDuty(c, d)) fine(c, d)
            d.job.progress = 0
            d.job.start = day
            if (d.amount == 0) return@forEach
            Progress.report(c, "wage", "", 1)
            Mail.direct(d.id, Phrase.of("kami_claims.mail.wage_paid", money(d.amount), Words.job(d.spec.name)), Tone.OK)
        }
    }

    private fun missedDuty(c: Country, d: Due): Boolean {
        val quota = terms(c, d.id, d.spec).quota
        return c.government.levers.labour == Labour.DUTY && d.spec.active && quota > 0 && d.amount == 0 && d.job.progress < quota && !inGrace(c, d.job, d.spec)
    }

    internal fun inGrace(c: Country, job: Job, spec: JobSpec): Boolean {
        val changedAt = c.government.changedAt
        return job.start <= job.took || spec.changedDay >= job.start || changedAt > 0 && changedAt / Config.s.dayMillis >= job.start
    }

    private fun fine(c: Country, d: Due) {
        val fine = minOf(terms(c, d.id, d.spec).pay / 2L, Treasury.room(c)).toInt()
        val taken = runCatching { Bank.take(UUID.fromString(d.id), fine, Flow.TREASURY, CountryParty(c.id).key, "kami_libs.note.fine") }.getOrDefault(false)
        if (fine <= 0 || !taken) return
        Treasury.move(c, LedgerKind.FEE, fine.toLong(), d.id, d.spec.name)
        Mail.direct(d.id, Phrase.of("kami_claims.mail.duty_fined", Words.job(d.spec.name), money(fine)), Tone.BAD)
    }

    private fun expire(c: Country, day: Long, income: Long) {
        c.contracts.filter { it.until <= day }.forEach { k ->
            val waiting = k.accepted && day < k.until + EXPIRY_SETTLE_DAYS && !affordable(c, listOf(k.player to k.job), day, income)
            if (waiting) return@forEach
            if (k.accepted) release(c, k.player, k.job)
            c.contracts.remove(k)
        }
    }

    private fun due(c: Country, day: Long): List<Due> {
        val ready = c.members.flatMap { (id, m) ->
            m.jobs.mapNotNull { (jobId, job) -> c.jobDefs[jobId]?.takeIf { day - job.start >= terms(c, id, it).period }?.let { Triple(id, it, job) } }
        }
        val (quota, single) = ready.partition { it.second.active && c.government.levers.labour == Labour.QUOTA }
        return single.map { (id, spec, job) -> individual(c, id, spec, job) } + quota.groupBy { it.second }.flatMap { (_, group) -> pool(c, group) }
    }

    private fun individual(c: Country, id: String, spec: JobSpec, job: Job): Due {
        val t = terms(c, id, spec)
        val amount = when {
            !spec.active || spec.payModel == PayModel.PIECE -> earned(t, job.progress)
            t.quota == 0 -> if (c.government.levers.labour == Labour.DUTY) t.pay else 0
            job.progress >= t.quota -> t.pay
            job.progress * 2 >= t.quota -> floor(t.pay * c.government.wageFloor).toInt()
            else -> 0
        }
        return Due(id, spec, job, amount)
    }

    private fun pool(c: Country, group: List<Triple<String, JobSpec, Job>>): List<Due> {
        val held = group.map { (id, spec, _) -> terms(c, id, spec) }
        val need = held.sumOf { it.quota.toLong() }
        val ratio = if (need == 0L) 0.0 else (group.sumOf { it.third.progress.toLong() }.toDouble() / need).coerceAtMost(1.0)
        return group.zip(held) { (id, spec, job), t -> Due(id, spec, job, floor(t.pay * ratio).toInt()) }
    }

    private fun transfer(c: Country, id: String, spec: JobSpec, amount: Int): Boolean {
        Treasury.move(c, LedgerKind.JOB_PAY, -amount.toLong(), id, spec.name)
        val ok = runCatching { wage(id, amount, c.id) }.getOrDefault(false)
        if (!ok) Treasury.move(c, LedgerKind.JOB_PAY, amount.toLong(), id, spec.name, capped = false)
        return ok
    }
}
