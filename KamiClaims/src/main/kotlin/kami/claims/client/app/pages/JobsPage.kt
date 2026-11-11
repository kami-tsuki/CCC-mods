package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Illustrations
import kami.claims.gov.JobActions
import kami.claims.gov.Perm
import kami.claims.net.Info
import kami.claims.net.JobSpecLine
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Route
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import kotlin.math.max

class JobsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.jobs")
    override val help get() = listOf(
        Callout("jobs:payroll", tr("kami_claims.jobs.budget"), tr("kami_claims.jobs.help.budget.desc")),
        Callout("jobs:detail", tr("kami_claims.jobs.editor"), tr("kami_claims.jobs.help.detail.desc"))
    )
    private val columns = Memo()
    private val layout = MasterDetail(0.42, 170, 260)
    private val table = TableState<JobSpecLine>()
    private val drafts = HashMap<String, JobDraft>()
    private val people = JobPeople(this)
    private var creating: JobDraft? = null
    private var contentH = DETAIL_ESTIMATE

    init { onSuccess("job_define:$NEW_KEY") { discard(null) } }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        if (!JobsView.can(info, Perm.VIEW_JOBS)) {
            ui.emptyState(r, tr("kami_claims.jobs.locked.title"), tr("kami_claims.jobs.locked.desc"), Illustrations.JOBS)
            return
        }
        payroll(ui, r.bottom(PAYROLL_H), info)
        var body = r.dropBottom(PAYROLL_H, 8)
        val strip = people.stripHeight(info)
        if (strip > 0) {
            people.strip(ui, body.top(strip), info)
            body = body.dropTop(strip, 6)
        }
        if (creating?.dirty(null) == false && NEW_KEY !in table.selected) creating = null
        layout.draw(ui, body, app.compact, table.selected, { list(ui, it, info) }, { detail(ui, it, info) })
    }

    private fun payroll(ui: Ui, r: Rect, info: Info) {
        ui.anchor("jobs:payroll", r)
        val cap = info.income * snap.jobShare / 100
        val over = info.jobs > cap
        Draw.text(ui.g, tr("kami_claims.jobs.budget"), r.x, r.y + 1, TextStyle.LABEL)
        Draw.textRight(ui.g, tr("kami_libs.format.of", Format.number(info.jobs), Format.perDay(Format.money(cap))), r.right, r.y + 1, if (over) Palette.danger else Palette.textMuted)
        ui.progress(Rect(r.x, r.y + 13, r.w, 5), info.jobs.toDouble() / max(1, cap), if (over) Palette.danger else Palette.brass)
    }

    private fun list(ui: Ui, r: Rect, info: Info) {
        var area = r
        if (JobsView.can(info, Perm.DEFINE_JOBS)) {
            val full = info.jobSpecs.size >= JobActions.MAX_JOBS
            val reason = if (full) tr("kami_claims.error.job_limit", JobActions.MAX_JOBS) else null
            if (ui.button(r.top(CONTROL_H), tr("kami_claims.jobs.new"), Icons.ADD, ButtonStyle.PRIMARY, !full, reason, key = "job-new")) startCreating(info)
            area = r.dropTop(CONTROL_H, 6)
        }
        val rows = columns.of(info, app.compact, Format.locale) { jobColumns(info) }
        ui.table(area, rows, info.jobSpecs, table, { it.id }, emptyText = tr("kami_claims.jobs.none.title"), key = "jobs-table")
    }

    private fun jobColumns(info: Info): List<Column<JobSpecLine>> = listOfNotNull(
        Column<JobSpecLine>(tr("kami_libs.common.job"), -1, sort = textOrder { it.name }) { _, c, j ->
            Draw.fill(g, Rect(c.x, c.centerY - 3, 6, 6), JobsView.opaque(j.color))
            fitText(c.x + 10, c.y + 3, c.w - 10, if (j.grantsNews) "${j.name} §e[${tr("kami_claims.jobs.news.badge")}]" else j.name, if (j.active) Palette.text else Palette.textMuted)
        },
        Column.text<JobSpecLine>(tr("kami_claims.jobs.field.scope"), 70, color = { Palette.textSecondary }) { JobsView.scopeLabel(info, it) },
        Column<JobSpecLine>(tr("kami_claims.jobs.field.pay"), 52, Align.RIGHT, compareBy { it.pay }) { _, c, j -> Draw.textRight(g, Format.money(j.pay.toLong()), c.right, c.y + 3, Palette.text) },
        Column<JobSpecLine>(tr("kami_claims.jobs.field.quota"), 44, Align.RIGHT, compareBy { it.quota }) { _, c, j -> Draw.textRight(g, Format.number(j.quota), c.right, c.y + 3, Palette.textSecondary) },
        Column<JobSpecLine>(tr("kami_claims.jobs.field.days"), 34, Align.RIGHT, compareBy { it.period }) { _, c, j -> Draw.textRight(g, Format.number(j.period), c.right, c.y + 3, Palette.textMuted) }.takeUnless { app.compact },
        Column<JobSpecLine>(tr("kami_claims.common.workers"), 48, Align.RIGHT, compareBy { it.holders.size }) { _, c, j ->
            Draw.textRight(g, if (j.holders.isEmpty()) "-" else Format.number(j.holders.size), c.right, c.y + 3, if (j.holders.isEmpty()) Palette.textMuted else Palette.textSecondary)
        }
    )

    private fun startCreating(info: Info) {
        if (creating == null) creating = JobDraft(null, JobsView.labour(info))
        selectNew()
    }

    private fun selectNew() {
        table.selected.clear()
        table.selected += NEW_KEY
    }

    private fun detail(ui: Ui, r: Rect, info: Info) {
        val fresh = creating?.takeIf { NEW_KEY in table.selected }
        if (fresh != null) return panel(ui, r, info, null, fresh)
        val line = info.jobSpecs.firstOrNull { it.id in table.selected } ?: info.jobSpecs.firstOrNull()
        if (line == null) {
            ui.emptyState(r, tr("kami_claims.jobs.none.title"), tr("kami_claims.jobs.none.desc"), Illustrations.JOBS)
            return
        }
        panel(ui, r, info, line, draftFor(line, JobsView.labour(info)))
    }

    private fun draftFor(line: JobSpecLine, labour: String): JobDraft {
        val draft = drafts[line.id] ?: return JobDraft(line, labour).also { drafts[line.id] = it }
        if (!draft.differs(line)) draft.base = line
        else if (draft.base?.let { draft.differs(it) } == false) return JobDraft(line, labour).also { drafts[line.id] = it }
        return draft
    }

    private fun panel(ui: Ui, r: Rect, info: Info, line: JobSpecLine?, draft: JobDraft) {
        ui.panel(r)
        ui.anchor("jobs:detail", r)
        val canDefine = JobsView.can(info, Perm.DEFINE_JOBS)
        val inner = r.inset(8)
        val staged = canDefine && draft.dirty(line)
        val area = if (staged) inner.dropBottom(BAR_H, 6) else inner
        val fields = JobFields(info, NameCheck.max(limits), JobsView.maxPay(limits), canDefine, line?.id ?: NEW_KEY)
        ui.scroll("job-detail:${line?.id ?: NEW_KEY}", area, contentH) { content ->
            val s = Stack(content.x, content.y, content.w)
            header(ui, s, info, line, draft, canDefine)
            fields.draw(ui, s, draft)
            if (line != null) crowd(ui, s, info, line)
            contentH = s.bottom - content.y
        }
        if (staged) stagedBar(ui, inner.bottom(BAR_H), info, line, draft)
    }

    private fun header(ui: Ui, s: Stack, info: Info, line: JobSpecLine?, draft: JobDraft, canDefine: Boolean) {
        val row = Row(s.take(HEADER_H), 4)
        Draw.fill(ui.g, Rect(row.take(6).x, row.rest.y + 5, 6, 6), JobsView.opaque(draft.color))
        if (line != null && canDefine) {
            if (ui.iconButton(row.takeFromRight(SMALL_H), Icons.REMOVE, tr("kami_claims.jobs.delete"), key = "job:${line.id}:delete")) confirmDelete(line)
            ui.toggle(row.takeFromRight(TOGGLE_W), line.active, tr("kami_claims.jobs.active"), key = "job:${line.id}:active")?.let { act("job_toggle", line.id, key = "job_toggle:${line.id}") }
        }
        ui.fitText(row.rest.x, row.rest.y + 3, row.rest.w, line?.name ?: tr("kami_claims.jobs.new"), style = TextStyle.HEADING, key = "job-head")
        LabourRules.hint(JobsView.labour(info))?.let { ui.fitText(s.x, s.take(HINT_H).y, s.w, it, Palette.textMuted, key = "job-hint") }
    }

    private fun crowd(ui: Ui, s: Stack, info: Info, line: JobSpecLine) {
        if (JobTiers.applies(info)) JobTiers.draw(ui, s, this, info, line)
        people.holders(ui, s, info, line)
        if (JobsView.labour(info) == "market") people.contracts(ui, s, info, line)
        if (JobsView.can(info, Perm.ASSIGN_CHUNKS)) people.grants(ui, s, info)
    }

    private fun stagedBar(ui: Ui, r: Rect, info: Info, line: JobSpecLine?, draft: JobDraft) {
        val reason = draft.problem(limits)
        val key = "job:${line?.id ?: NEW_KEY}"
        ui.stagedBar(r, draft.name.text.trim().ifEmpty { tr("kami_claims.jobs.new") }, reason == null, reason, pending("job_define:${line?.id ?: NEW_KEY}"), key, { send(draft) }, { discard(line) })
    }

    private fun send(draft: JobDraft) {
        act("job_define", *draft.args(), key = "job_define:${draft.base?.id ?: NEW_KEY}")
    }

    private fun discard(line: JobSpecLine?) {
        if (line == null) {
            creating = null
            table.selected.clear()
        } else drafts.remove(line.id)
    }

    private fun confirmDelete(job: JobSpecLine) = Dialogs.confirm(
        app, tr("kami_claims.jobs.delete.title", job.name), null, Icons.REMOVE,
        listOf(Consequence(tr("kami_claims.jobs.delete.pay", Format.number(job.holders.size)), Severity.WARNING), Consequence(tr("kami_claims.jobs.delete.contracts"), Severity.WARNING)),
        tr("kami_claims.jobs.delete"), "job_delete", arrayOf(job.id), danger = true, key = "job_delete:${job.id}",
        after = { drafts.remove(job.id); table.selected.clear() }
    )

    override fun leaving(next: Route): Boolean {
        val info = info
        val dirty = info?.jobSpecs.orEmpty().mapNotNull { l -> drafts[l.id]?.takeIf { it.dirty(l) } } + listOfNotNull(creating?.takeIf { it.dirty(null) })
        val problem = dirty.firstNotNullOfOrNull { it.problem(limits) }
        val save: (() -> Unit)? = if (info != null && problem == null && JobsView.can(info, Perm.DEFINE_JOBS)) { { dirty.forEach(::send) } } else null
        return guardLeave(next, dirty.isNotEmpty(), save, problem) { drafts.clear(); creating = null } && super.leaving(next)
    }

    private companion object {
        const val NEW_KEY = "new"
        const val PAYROLL_H = 22
        const val BAR_H = 28
        const val HEADER_H = 16
        const val HINT_H = 10
        const val TOGGLE_W = 86
        const val DETAIL_ESTIMATE = 400
    }
}
