package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.NameRules
import kami.claims.service.Fail
import kami.claims.today

internal object JobArgs {
    private const val COLOR_MAX = 0xFFFFFF
    private const val ACTIVE = 11
    private const val PERMS = 12

    fun scope(a: List<String>) = ActionArgs.choice<JobScope>(ActionArgs.arg(a, 2))

    fun ref(a: List<String>) = a.getOrElse(3) { "" }

    fun spec(c: Country, a: List<String>, existing: JobSpec?, scope: JobScope, ref: String, definer: String = ""): JobSpec {
        val payModel = ActionArgs.choice<PayModel>(ActionArgs.arg(a, 8))
        val quota = ActionArgs.int(a, 6).coerceAtLeast(0)
        val assignment = ActionArgs.choice<Assignment>(ActionArgs.arg(a, 9))
        JobRules.validate(quota, payModel, assignment, c.government.levers.labour)
        val spec = JobSpec(
            existing?.id ?: ActionArgs.newId(),
            ActionArgs.label(ActionArgs.arg(a, 1)),
            a.getOrNull(10)?.toIntOrNull()?.coerceIn(0, COLOR_MAX) ?: existing?.color ?: 0,
            scope,
            ref,
            JobRules.tasks(ActionArgs.arg(a, 4), scope),
            ActionArgs.int(a, 5).coerceIn(0, Config.s.maxJobPay),
            quota,
            ActionArgs.int(a, 7).coerceIn(1, JobRules.MAX_PERIOD),
            payModel,
            assignment,
            a.getOrNull(ACTIVE)?.toBooleanStrictOrNull() ?: existing?.active ?: true,
            existing?.definedBy ?: definer
        )
        spec.perms = a.getOrNull(PERMS)?.let(JobRules::perms) ?: existing?.perms.orEmpty()
        if (c.jobDefs.values.any { it.id != spec.id && NameRules.key(it.name) == NameRules.key(spec.name) }) throw Fail("kami_claims.error.name_taken")
        spec.basePay = existing?.takeIf { it.pay == spec.pay }?.basePay ?: spec.pay
        spec.changedDay = if (existing != null && !JobRules.altersEarnings(existing, spec)) existing.changedDay else today()
        return spec
    }
}
