package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsPage
import kami.claims.gov.PayTier
import kami.claims.gov.Perm
import kami.claims.net.Info
import kami.claims.net.JobSpecLine
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.section
import kami.libs.ui.widget.segmented

internal object JobTiers {
    private const val HINT_H = 12
    private val WAGE_FLOORS = mapOf("socialism" to 50, "mixed" to 25)

    fun applies(info: Info) = JobsView.labour(info) == "wage"

    fun draw(ui: Ui, s: Stack, page: ClaimsPage, info: Info, job: JobSpecLine) {
        ui.section(s, tr("kami_claims.jobs.tier.title"))
        val control = s.take(CONTROL_H)
        val allowed = JobsView.can(info, Perm.DEFINE_JOBS) && !info.delegated
        val options = PayTier.entries.map { Option<String?>(it.wire, tr("kami_claims.jobs.tier.${it.wire}") + " (${it.percent}%)") }
        ui.segmented(control, options, current(job), allowed, "job:${job.id}:tier")?.let { page.act("job_tier", job.id, it, key = "job_tier:${job.id}") }
        if (!allowed) ui.tooltip("job:${job.id}:tier-lock", control, tr("kami_claims.jobs.tier.locked"))
        WAGE_FLOORS[info.government?.preset]?.let { ui.fitText(s.x, s.take(HINT_H).y, s.w, tr("kami_claims.jobs.wage_floor", it), Palette.textMuted, key = "job:${job.id}:floor") }
    }

    private val PayTier.wire get() = name.lowercase()

    private fun current(job: JobSpecLine): String? {
        val base = if (job.basePay > 0) job.basePay else job.pay
        return PayTier.entries.firstOrNull { base.toLong() * it.percent / 100 == job.pay.toLong() }?.wire
    }
}
