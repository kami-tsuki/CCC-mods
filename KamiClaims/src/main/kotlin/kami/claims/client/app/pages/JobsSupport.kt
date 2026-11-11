package kami.claims.client.app.pages

import kami.claims.client.app.Vocabulary
import kami.claims.gov.JobRules
import kami.claims.gov.Labour
import kami.claims.gov.Perm
import kami.claims.gov.TaskKind
import kami.claims.net.Info
import kami.claims.net.JobSpecLine
import kami.claims.net.Limits
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.text.trn
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.fitText
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

internal object JobsView {
    private const val OPAQUE = 0xFF000000.toInt()
    private const val DEFAULT_MAX_PAY = 100

    fun can(info: Info, perm: Perm) = perm.name in info.myPerms
    fun labour(info: Info) = info.government?.labour.orEmpty()
    fun maxPay(limits: Limits?) = (limits?.maxJobPay ?: DEFAULT_MAX_PAY).toLong()
    fun nameOf(info: Info, id: String) = info.members.firstOrNull { it.id == id }?.name ?: id.take(8)
    fun me() = Minecraft.getInstance().player?.stringUUID.orEmpty()
    fun opaque(rgb: Int) = OPAQUE or (rgb and 0xFFFFFF)
    fun job(info: Info, id: String) = info.jobSpecs.firstOrNull { it.id == id }
    fun taskKinds() = TaskKind.entries.map { Option(it.name.lowercase(), tr("kami_claims.jobs.task.${it.name.lowercase()}")) }

    fun scopeLabel(info: Info, job: JobSpecLine) = when (job.scope) {
        "type" -> Vocabulary.type(job.scopeRef).label
        "sector" -> info.sectors.firstOrNull { it.id == job.scopeRef }?.name ?: tr("kami_claims.jobs.scope.missing")
        else -> tr("kami_claims.jobs.scope.country")
    }
}

internal object LabourRules {
    private fun labourOf(labour: String) = Labour.entries.firstOrNull { it.name.equals(labour, true) } ?: Labour.WAGE

    fun assignments(labour: String) = JobRules.assignments(labourOf(labour)).map { it.name.lowercase() }

    fun payModels(labour: String) = JobRules.payModels(labourOf(labour)).map { it.name.lowercase() }

    fun hint(labour: String) = if (labour in HINTED) tr("kami_claims.jobs.hint.$labour") else null

    fun payLabel(labour: String) = if (labour == "duty") tr("kami_claims.jobs.field.stipend") else tr("kami_claims.jobs.field.pay")

    private val HINTED = setOf("market", "wage", "quota", "duty")
}

internal fun Ui.stagedBar(r: Rect, summary: String, enabled: Boolean, reason: String?, pending: Boolean, key: String, onApply: () -> Unit, onDiscard: () -> Unit) {
    Draw.fill(g, r, Palette.alpha(Palette.brass, 0x28))
    Draw.outline(g, r, Palette.alpha(Palette.brass, 0x90))
    Draw.fill(g, Rect(r.x + 6, r.centerY - 2, 4, 4), Palette.brass)
    Draw.text(g, trn("kami_claims.unit.unsaved", 1), r.x + 14, r.y + 5, TextStyle.HEADING, Palette.brass)
    fitText(r.x + 14, r.y + 15, r.w - 190, summary, Palette.textSecondary)
    val row = Row(Rect(r.x, r.y + 4, r.w - 4, CONTROL_H))
    if (edgeButton(row, tr("kami_libs.common.apply"), Icons.SAVE, ButtonStyle.PRIMARY, enabled, reason, pending = pending, key = "$key:apply")) onApply()
    if (edgeButton(row, tr("kami_libs.common.discard"), Icons.UNDO, key = "$key:discard")) onDiscard()
    if (input.takeKey(GLFW.GLFW_KEY_S) { it.ctrl } != null && enabled && !pending) onApply()
}
