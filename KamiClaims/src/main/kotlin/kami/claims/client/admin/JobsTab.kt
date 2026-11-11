package kami.claims.client.admin

import kami.claims.admin.CountryDetail
import kami.claims.admin.CountryRequests
import kami.claims.client.app.pages.JobDraft
import kami.claims.client.app.pages.JobsView
import kami.claims.client.app.pages.MAX_QUOTA
import kami.claims.client.app.pages.TaskDraft
import kami.claims.gov.Assignment
import kami.claims.gov.JobRules
import kami.claims.gov.JobScope
import kami.claims.gov.PayModel
import kami.claims.gov.SectorRules
import kami.claims.net.ContractLine
import kami.claims.net.JobSpecLine
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.COLOR_PICKER_H
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.SMALL_H
import kami.libs.ui.widget.button
import kami.libs.ui.widget.checkbox
import kami.libs.ui.widget.colorPicker
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.numberField
import kami.libs.ui.widget.select
import kami.libs.ui.widget.textField

private const val NEW = "new"
private const val CHECK_H = 12
private const val CAP_W = 84
private const val FILTER_MAX = 200

internal class JobsTab(kit: GovernanceKit) : GovernanceTab(kit, "gov-jobs") {
    private var selected = NEW
    private var draft = JobDraft(null, "")
    private var active = true
    private var holder: String? = null

    override fun revision(d: CountryDetail): Any? = d.governance.jobs.map {
        listOf(it.id, it.name, it.color, it.scope, it.scopeRef, it.tasks.map { t -> listOf(t.kind, t.filter, t.cap) }, it.pay, it.quota, it.period, it.payModel, it.assignment, it.active)
    }

    override fun load(d: CountryDetail) {
        select(d.governance.jobs.firstOrNull { it.id == selected }, d)
    }

    private fun select(job: JobSpecLine?, d: CountryDetail) {
        selected = job?.id ?: NEW
        draft = JobDraft(job, d.governance.government.labour)
        active = job?.active ?: true
        holder = null
    }

    override fun lines(ui: Ui, f: Flow, d: CountryDetail) {
        val jobs = d.governance.jobs
        val job = jobs.firstOrNull { it.id == selected }
        val options = jobs.map { Option(it.id, it.name) } + Option(NEW, tr("kami_claims.admin.jobs.new"))
        ui.select(ui.labeled(f.take(LABELED_H), tr("kami_claims.admin.jobs.list")), options, selected, key = "gov-job-pick")?.let { id -> select(jobs.firstOrNull { it.id == id }, d) }
        form(ui, f, d, job)
        if (job != null) {
            holders(ui, f, d, job)
            contracts(ui, f, d, job)
        }
    }

    private fun form(ui: Ui, f: Flow, d: CountryDetail, job: JobSpecLine?) {
        ui.textField(ui.labeled(f.take(LABELED_H), tr("kami_claims.admin.jobs.name")), draft.name, maxLength = 32, key = "gov-job-name")
        ui.colorPicker(f.take(COLOR_PICKER_H), draft.color, "gov-job-color")?.let { draft.color = it }
        val scopes = f.take(LABELED_H).columns(2, FIELD_GAP)
        ui.select(ui.labeled(scopes[0], tr("kami_claims.admin.jobs.scope")), JobScope.entries.map { Option(it.name.lowercase(), tr("kami_claims.jobs.scope.${it.name.lowercase()}")) }, draft.scope, key = "gov-job-scope")?.let { draft.changeScope(it) }
        refPicker(ui, ui.labeled(scopes[1], tr("kami_claims.admin.jobs.ref")), d)
        tasks(ui, f)
        val numbers = f.take(LABELED_H).columns(3, FIELD_GAP)
        ui.numberField(ui.labeled(numbers[0], tr("kami_claims.admin.jobs.pay")), draft.pay, 0, JobsView.maxPay(limits), key = "gov-job-pay")
        ui.numberField(ui.labeled(numbers[1], tr("kami_claims.admin.jobs.quota")), draft.quota, 0, MAX_QUOTA, key = "gov-job-quota")
        ui.numberField(ui.labeled(numbers[2], tr("kami_claims.admin.jobs.period")), draft.period, 1, JobRules.MAX_PERIOD.toLong(), key = "gov-job-period")
        val models = f.take(LABELED_H).columns(2, FIELD_GAP)
        ui.select(ui.labeled(models[0], tr("kami_claims.admin.jobs.model")), PayModel.entries.map { Option(it.name.lowercase(), tr("kami_claims.jobs.paymodel.${it.name.lowercase()}")) }, draft.payModel, key = "gov-job-model")?.let { draft.payModel = it }
        ui.select(ui.labeled(models[1], tr("kami_claims.admin.jobs.assignment")), Assignment.entries.map { Option(it.name.lowercase(), tr("kami_claims.jobs.assignment.${it.name.lowercase()}")) }, draft.assignment, key = "gov-job-assignment")?.let { draft.assignment = it }
        ui.checkbox(f.take(CHECK_H), tr("kami_claims.admin.jobs.active"), active, key = "gov-job-active")?.let { active = it }
        actions(ui, f, d, job)
    }

    private fun tasks(ui: Ui, f: Flow) {
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.jobs.tasks"), "${draft.tasks.size}/${JobRules.MAX_TASKS}")
        val kinds = JobsView.taskKinds()
        draft.tasks.toList().forEachIndexed { i, t ->
            val row = Row(f.take(CONTROL_H))
            if (draft.tasks.size > 1 && ui.iconButton(row.takeFromRight(SMALL_H), Icons.REMOVE, tr("kami_libs.common.remove"), key = "gov-job-task:$i:remove")) draft.tasks.remove(t)
            ui.numberField(row.takeFromRight(CAP_W), t.cap, 1, JobRules.MAX_TASK_CAP.toLong(), key = "gov-job-task:$i:cap")
            ui.select(row.rest, kinds, t.kind, key = "gov-job-task:$i:kind")?.let { t.kind = it }
            if (t.filtered) ui.textField(f.take(CONTROL_H), t.filter, tr("kami_claims.jobs.task.filter"), Icons.SEARCH, maxLength = FILTER_MAX, key = "gov-job-task:$i:filter")
        }
        if (draft.tasks.size < JobRules.MAX_TASKS && ui.button(f.take(CONTROL_H), tr("kami_claims.jobs.task.add"), Icons.ADD, key = "gov-job-task:add")) draft.tasks += TaskDraft("harvest", "", 0)
    }

    private fun refPicker(ui: Ui, r: Rect, d: CountryDetail) {
        val options = when (draft.scope) {
            "type" -> SectorRules.workTypes.map { Option(it, tr("kami_claims.chunk_type.$it")) }
            "sector" -> d.governance.sectors.map { Option(it.id, it.name) }
            else -> emptyList()
        }
        ui.select(r, options, draft.scopeRef, enabled = options.isNotEmpty(), disabledReason = tr("kami_claims.admin.jobs.ref.none"), key = "gov-job-ref")?.let { draft.scopeRef = it }
    }

    private fun actions(ui: Ui, f: Flow, d: CountryDetail, job: JobSpecLine?) {
        val row = Row(f.take(CONTROL_H))
        kit.button(ui, row, d, tr("kami_claims.admin.jobs.save"), Icons.EDIT, CountryRequests.JOB_SAVE, draft.args().toList() + active.toString(), tr("kami_claims.admin.jobs.save.title", draft.name.text.trim(), d.row.name), draft.problem(limits))
        job?.let { kit.button(ui, row, d, tr("kami_claims.admin.jobs.delete"), Icons.REMOVE, CountryRequests.JOB_DELETE, listOf(it.id), tr("kami_claims.admin.jobs.delete.title", it.name, d.row.name), danger = true) }
    }

    private fun holders(ui: Ui, f: Flow, d: CountryDetail, job: JobSpecLine) {
        val names = kit.names(d)
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.jobs.holders", job.holders.size))
        job.holders.forEach { id ->
            val row = Row(f.take(CONTROL_H))
            val who = names[id] ?: id
            kit.button(ui, row, d, tr("kami_claims.admin.jobs.unassign"), Icons.REMOVE, CountryRequests.JOB_REMOVE, listOf(id, job.id), tr("kami_claims.admin.jobs.unassign.title", who, job.name))
            ui.fitText(row.rest.x, row.rest.y + (CONTROL_H - 8) / 2, row.rest.w, who, key = "gov-holder:${job.id}:$id")
        }
        val row = Row(f.take(CONTROL_H))
        val who = holder?.let(names::get)
        kit.button(ui, row, d, tr("kami_claims.admin.jobs.assign"), Icons.ADD, CountryRequests.JOB_ASSIGN, listOfNotNull(holder, job.id), tr("kami_claims.admin.jobs.assign.title", who.orEmpty(), job.name),
            tr("kami_claims.admin.input_missing").takeIf { who == null } ?: tr("kami_claims.admin.jobs.has").takeIf { holder in job.holders })
        ui.select(row.remaining(), d.members.map { Option(it.uuid, it.name) }, holder, tr("kami_claims.admin.leader.placeholder"), key = "gov-job-holder")?.let { holder = it }
    }

    private fun contracts(ui: Ui, f: Flow, d: CountryDetail, job: JobSpecLine) {
        val names = kit.names(d)
        val mine = d.governance.contracts.filter { it.job == job.id }
        if (mine.isEmpty()) return
        ui.fieldLabel(f.take(SECTION_H), tr("kami_claims.admin.jobs.contracts", mine.size))
        mine.forEach { contract -> contractRow(ui, f, d, contract, names[contract.player] ?: contract.player) }
    }

    private fun contractRow(ui: Ui, f: Flow, d: CountryDetail, contract: ContractLine, who: String) {
        val row = Row(f.take(CONTROL_H))
        kit.button(ui, row, d, tr("kami_claims.admin.jobs.cancel"), Icons.REMOVE, CountryRequests.CONTRACT_CANCEL, listOf(contract.id), tr("kami_claims.admin.jobs.cancel.title", who, d.row.name), danger = true)
        val state = tr(if (contract.accepted) "kami_claims.admin.jobs.contract.accepted" else "kami_claims.admin.jobs.contract.pending")
        ui.fitText(row.rest.x, row.rest.y + (CONTROL_H - 8) / 2, row.rest.w, "$who  ${Format.money(contract.pay.toLong())}  $state", key = "gov-contract:${contract.id}")
    }
}
