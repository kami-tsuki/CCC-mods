package kami.claims.client.app.pages

import kami.claims.gov.JobRules
import kami.claims.gov.SectorRules
import kami.claims.net.JobSpecLine
import kami.claims.net.Limits
import kami.claims.net.TaskLine
import kami.libs.ui.text.tr
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.colorProblem

internal const val MAX_QUOTA = 100_000L
internal const val JOURNALIST_PERM = "NEWS_WRITE"
internal val JobSpecLine.grantsNews get() = JOURNALIST_PERM in perms
private const val DEFAULT_COLOR = 0xC9A227
private val FILTERLESS = setOf("herd", "fish")
private val FILTER_REQUIRED = setOf("build", "craft", "smelt")

internal class TaskDraft(var kind: String, filter: String, cap: Int) {
    val filter = TextState(filter)
    val cap = NumberState(if (cap > 0) cap.toLong() else JobRules.MAX_TASK_CAP.toLong())

    val filtered get() = kind !in FILTERLESS
    private val filterText get() = if (filtered) filter.text.trim() else ""

    val missingFilter get() = kind in FILTER_REQUIRED && filterText.isEmpty()
    val invalidFilter get() = !JobRules.validFilter(filterText)

    fun wire() = "${kind.uppercase()}:${filterText.replace(';', ' ')}:${cap.value}"
    fun differs(line: TaskLine) = kind != line.kind || filterText != line.filter || cap.value != line.cap.toLong()
}

internal class JobDraft(var base: JobSpecLine?, private val labour: String) {
    val name = TextState(base?.name.orEmpty())
    var color = base?.color ?: DEFAULT_COLOR
    var scope = base?.scope ?: "country"
        private set
    var scopeRef = base?.scopeRef.orEmpty()
    val tasks = (base?.tasks ?: listOf(TaskLine("harvest", "", 0))).map { TaskDraft(it.kind, it.filter, it.cap) }.toMutableList()
    val pay = NumberState((base?.pay ?: 0).toLong())
    val quota = NumberState((base?.quota ?: 0).toLong())
    val period = NumberState((base?.period ?: 1).toLong())
    var payModel = base?.payModel ?: LabourRules.payModels(labour).first()
    var assignment = base?.assignment ?: LabourRules.assignments(labour).first()
    var journalist = base?.grantsNews ?: false

    fun changeScope(next: String) {
        scope = next
        scopeRef = if (next == "type") SectorRules.workTypes.first() else ""
    }

    fun differs(line: JobSpecLine) = name.text.trim() != line.name || color != line.color || scope != line.scope || scopeRef != line.scopeRef ||
        pay.value != line.pay.toLong() || quota.value != line.quota.toLong() || period.value != line.period.toLong() ||
        payModel != line.payModel || assignment != line.assignment || journalist != line.grantsNews || tasks.size != line.tasks.size || tasks.zip(line.tasks).any { (d, t) -> d.differs(t) }

    fun dirty(line: JobSpecLine?) = if (line == null) name.text.isNotBlank() else differs(line)

    fun problem(limits: Limits?): String? = NameCheck.problem(name.text, limits) ?: colorProblem(color) ?: when {
        scope != "country" && scopeRef.isEmpty() -> tr("kami_claims.jobs.reason.scope")
        tasks.size !in 1..JobRules.MAX_TASKS -> tr("kami_claims.jobs.reason.tasks", JobRules.MAX_TASKS)
        scope != "sector" && tasks.any { it.missingFilter } -> tr("kami_claims.jobs.reason.filter_needed")
        tasks.any { it.invalidFilter } -> tr("kami_claims.jobs.reason.filter_invalid")
        payModel == "piece" && quota.value == 0L -> tr("kami_claims.jobs.reason.piece")
        quota.value == 0L && payModel != "ration" && labour != "duty" -> tr("kami_claims.jobs.reason.quota")
        else -> null
    }

    fun args(): Array<String> = arrayOf(
        base?.id ?: "new", name.text.trim(), scope, scopeRef, tasks.joinToString(";") { it.wire() },
        pay.value.toString(), quota.value.toString(), period.value.toString(), payModel, assignment, color.toString(),
        (base?.active ?: true).toString(), if (journalist) JOURNALIST_PERM else ""
    )
}
