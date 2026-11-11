package kami.claims.client.app.pages

import kami.claims.client.app.Vocabulary
import kami.claims.gov.JobRules
import kami.claims.gov.Perm
import kami.claims.gov.SectorRules
import kami.claims.net.Info
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr
import kami.libs.ui.widget.COLOR_PICKER_H
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.SMALL_H
import kami.libs.ui.widget.button
import kami.libs.ui.widget.colorPicker
import kami.libs.ui.style.Palette
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.numberField
import kami.libs.ui.widget.select
import kami.libs.ui.widget.textField
import kami.libs.ui.widget.toggle

internal class JobFields(private val info: Info, private val nameMax: Int, private val maxPay: Long, private val enabled: Boolean, private val key: String) {
    private val labour = JobsView.labour(info)

    fun draw(ui: Ui, s: Stack, d: JobDraft) {
        name(ui, s, d)
        color(ui, s, d)
        scope(ui, s, d)
        tasks(ui, s, d)
        model(ui, s, d)
        numbers(ui, s, d)
        journalist(ui, s, d)
    }

    private fun name(ui: Ui, s: Stack, d: JobDraft) {
        ui.fieldLabel(s.take(LABEL_H), tr("kami_libs.common.name"), "${d.name.text.length}/$nameMax")
        ui.textField(s.take(CONTROL_H), d.name, tr("kami_libs.common.name"), Icons.EDIT, enabled, nameMax, NameCheck::allows, key = "$key:name")
    }

    private fun color(ui: Ui, s: Stack, d: JobDraft) {
        ui.fieldLabel(s.take(LABEL_H), tr("kami_claims.jobs.field.color"))
        ui.colorPicker(s.take(COLOR_PICKER_H), d.color, "$key:color", enabled)?.let { d.color = it }
    }

    private fun scope(ui: Ui, s: Stack, d: JobDraft) {
        val labels = s.take(LABEL_H).columns(2, GAP)
        val cells = s.take(CONTROL_H).columns(2, GAP)
        ui.fieldLabel(labels[0], tr("kami_claims.jobs.field.scope"))
        ui.fieldLabel(labels[1], tr("kami_claims.jobs.field.scope_ref"))
        val sectorLock = if (JobsView.can(info, Perm.SCOPE_JOBS)) null else tr("kami_claims.jobs.reason.scope_perm")
        val scopes = listOf(
            Option("country", tr("kami_claims.jobs.scope.country")),
            Option("type", tr("kami_claims.jobs.scope.type")),
            Option("sector", tr("kami_claims.jobs.scope.sector"), disabledReason = sectorLock)
        )
        ui.select(cells[0], scopes, d.scope, enabled = enabled, key = "$key:scope")?.let { d.changeScope(it) }
        ui.select(cells[1], refOptions(d.scope), d.scopeRef.takeIf { it.isNotEmpty() }, enabled = enabled && d.scope != "country", key = "$key:ref")?.let { d.scopeRef = it }
    }

    private fun refOptions(scope: String) = when (scope) {
        "type" -> SectorRules.workTypes.map { Option(it, Vocabulary.type(it).label, Vocabulary.type(it).icon, color = Vocabulary.type(it).color) }
        "sector" -> info.sectors.map { Option(it.id, it.name, color = JobsView.opaque(it.color)) }
        else -> emptyList()
    }

    private fun tasks(ui: Ui, s: Stack, d: JobDraft) {
        ui.fieldLabel(s.take(LABEL_H), tr("kami_claims.jobs.field.tasks"), "${d.tasks.size}/${JobRules.MAX_TASKS}")
        val kinds = JobsView.taskKinds()
        d.tasks.toList().forEachIndexed { i, t ->
            val row = Row(s.take(CONTROL_H), GAP)
            if (enabled && d.tasks.size > 1 && ui.iconButton(row.takeFromRight(SMALL_H), Icons.REMOVE, tr("kami_libs.common.remove"), key = "$key:task:$i:remove")) d.tasks.remove(t)
            ui.numberField(row.takeFromRight(CAP_W), t.cap, 1, JobRules.MAX_TASK_CAP.toLong(), 16, enabled = enabled, key = "$key:task:$i:cap")
            ui.select(row.rest, kinds, t.kind, enabled = enabled, key = "$key:task:$i:kind")?.let { t.kind = it }
            if (t.filtered) ui.textField(s.take(CONTROL_H), t.filter, tr("kami_claims.jobs.task.filter"), Icons.SEARCH, enabled, FILTER_MAX, key = "$key:task:$i:filter")
        }
        if (enabled && d.tasks.size < JobRules.MAX_TASKS && ui.button(s.take(CONTROL_H), tr("kami_claims.jobs.task.add"), Icons.ADD, key = "$key:task:add")) d.tasks += TaskDraft("harvest", "", 0)
    }

    private fun model(ui: Ui, s: Stack, d: JobDraft) {
        val labels = s.take(LABEL_H).columns(2, GAP)
        val cells = s.take(CONTROL_H).columns(2, GAP)
        ui.fieldLabel(labels[0], tr("kami_claims.jobs.field.pay_model"))
        ui.fieldLabel(labels[1], tr("kami_claims.jobs.field.assignment"))
        val models = (LabourRules.payModels(labour) + d.payModel).distinct().map { Option(it, tr("kami_claims.jobs.paymodel.$it")) }
        val modes = (LabourRules.assignments(labour) + d.assignment).distinct().map { Option(it, tr("kami_claims.jobs.assignment.$it")) }
        ui.select(cells[0], models, d.payModel, enabled = enabled, key = "$key:model")?.let { d.payModel = it }
        ui.select(cells[1], modes, d.assignment, enabled = enabled, key = "$key:mode")?.let { d.assignment = it }
    }

    private fun numbers(ui: Ui, s: Stack, d: JobDraft) {
        val labels = s.take(LABEL_H).columns(3, GAP)
        val cells = s.take(CONTROL_H).columns(3, GAP)
        ui.fieldLabel(labels[0], LabourRules.payLabel(labour))
        ui.fieldLabel(labels[1], tr("kami_claims.jobs.field.quota"))
        ui.fieldLabel(labels[2], tr("kami_claims.jobs.field.days"))
        ui.numberField(cells[0], d.pay, 0, maxPay, enabled = enabled, key = "$key:pay")
        ui.numberField(cells[1], d.quota, 0, MAX_QUOTA, enabled = enabled, key = "$key:quota")
        ui.numberField(cells[2], d.period, 1, JobRules.MAX_PERIOD.toLong(), enabled = enabled, key = "$key:period")
    }

    private fun journalist(ui: Ui, s: Stack, d: JobDraft) {
        ui.toggle(s.take(CONTROL_H), d.journalist, tr("kami_claims.jobs.news.toggle"), enabled, key = "$key:news")?.let { d.journalist = it }
        ui.fitText(s.x, s.take(LABEL_H + 1).y, s.w, tr("kami_claims.jobs.news.hint"), Palette.textMuted, key = "$key:news-hint")
    }

    private companion object {
        const val LABEL_H = 9
        const val GAP = 6
        const val CAP_W = 84
        const val FILTER_MAX = 200
    }
}
