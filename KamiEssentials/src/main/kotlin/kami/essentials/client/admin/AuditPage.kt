package kami.essentials.client.admin

import kami.libs.ui.app.Paged
import kami.libs.ui.app.pagedTable
import kami.libs.admin.Audit
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.Page
import kami.libs.ui.app.SELECT_W
import kami.libs.ui.app.TimeRange
import kami.libs.ui.app.segmentWidth
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kami.libs.ui.widget.*

internal fun auditSeverity(e: Audit.Entry) = if (e.ok) null else Severity.DANGER

private val PRIVATE_ARGS = setOf("ips", "ip")

internal fun actionLabel(name: String) = trOr("kami_essentials.admin.action.$name", name)

internal fun filterLabel(id: String) = tr(if (id == "all" || id == "online") "kami_libs.common.$id" else "kami_essentials.admin.filter.$id")

private fun argLabel(key: String) = key.toIntOrNull()?.let { tr("kami_essentials.admin.arg.n", it + 1) } ?: trOr(if (key == "amount") "kami_libs.common.amount" else "kami_essentials.admin.arg.$key", key)

private fun result(ok: Boolean) = tr(if (ok) "kami_essentials.admin.audit.ok" else "kami_essentials.admin.audit.failed")

private val auditMemo = arrayOf(Memo(), Memo())

internal fun auditColumns(withTarget: Boolean = true) = auditMemo[if (withTarget) 1 else 0].of(Format.locale) { buildAuditColumns(withTarget) }

private fun buildAuditColumns(withTarget: Boolean) = listOfNotNull(
    Column.number<Audit.Entry>(tr("kami_libs.common.when"), 56, format = { Format.ago(it) }) { it.at },
    Column.text<Audit.Entry>(tr("kami_essentials.admin.col.actor"), 70) { it.actorName },
    Column.text<Audit.Entry>(tr("kami_essentials.admin.col.action"), 90) { actionLabel(it.action) },
    if (withTarget) whoColumn<Audit.Entry>(tr("kami_essentials.admin.col.target"), 70) { it.target } else null,
    Column.text<Audit.Entry>(tr("kami_libs.admin.reason"), -1) { it.reason },
    Column.text<Audit.Entry>(tr("kami_libs.common.status"), 44, color = { if (it.ok) Palette.success else Palette.danger }) { result(it.ok) }
)

internal fun compactAuditColumns() = listOf(
    Column.number<Audit.Entry>(tr("kami_libs.common.when"), 56, format = { Format.ago(it) }) { it.at },
    Column.text<Audit.Entry>(tr("kami_essentials.admin.col.actor"), 70) { it.actorName },
    Column.text<Audit.Entry>(tr("kami_essentials.admin.col.action"), -1) { actionLabel(it.action) }
)

internal fun AdminApp.auditDialog(e: Audit.Entry) = open(Dialog(actionLabel(e.action), Format.exact(e.at), Icons.SCROLL, DialogKind.INFO, 320) { s ->
    val b = s.body
    var y = b.y
    fun prop(label: String, value: String, color: Int = Palette.text, private: Boolean = false) {
        val line = Rect(b.x, y, b.w, 11)
        if (private) secretProperty(line, label, value, color, key = "audit-secret:$label:$value") else property(line, label, value, color, copy = true, key = "audit-prop:$label")
        y += 13
    }
    prop(tr("kami_essentials.admin.col.actor"), e.actorName)
    if (AdminData.uuidIn(e.actor) != null) prop("", e.actor, Palette.textSecondary, true)
    val targetId = AdminData.uuidIn(e.target)
    val targetName = targetId?.let(AdminData::known)
    if (e.target.isNotEmpty() && (targetId == null || targetName != null)) prop(tr("kami_essentials.admin.col.target"), targetName ?: e.target)
    if (targetId != null) prop(if (targetName == null) tr("kami_essentials.admin.col.target") else "", e.target, Palette.textSecondary, true)
    prop(tr("kami_libs.common.status"), result(e.ok), if (e.ok) Palette.success else Palette.danger)
    y += 4
    fieldLabel(Rect(b.x, y, b.w, 9), tr("kami_libs.admin.reason"))
    y += 11
    val reason = e.reason.ifEmpty { tr("kami_libs.common.none") }
    Draw.paragraph(g, reason, b.x, y, b.w, Palette.text)
    y += Draw.paragraphHeight(reason, b.w) + 6
    if (e.args.isNotEmpty()) {
        fieldLabel(Rect(b.x, y, b.w, 9), tr("kami_essentials.admin.audit.args"))
        y += 11
        e.args.forEach { (k, v) ->
            val id = AdminData.uuidIn(v)
            val known = id?.let(AdminData::known)
            if (known != null) prop(argLabel(k), known)
            prop(if (known != null) "" else argLabel(k), if (id == null) party(v) else v, if (known != null) Palette.textSecondary else Palette.text, k in PRIVATE_ARGS || id != null)
        }
    }
    s.used = y - b.y
})

internal class AuditPage(private val app: AdminApp) : Page() {
    override val title get() = tr("kami_essentials.admin.nav.audit")
    private val actor = TextState()
    private val action = TextState()
    private val target = TextState()
    private var ok = "all"
    private val range = TimeRange("audit:range")
    private val paged = Paged("audit.query", Audit.Entry.serializer()) {
        val t = target.text.trim()
        listOf(actor.text.trim(), action.text.trim(), AdminData.uuidOf(t) ?: t, range.from?.toString().orEmpty(), "", if (ok == "all") "" else (ok == "ok").toString())
    }
    private val table = TableState<Audit.Entry>()

    override fun draw(ui: Ui, r: Rect) {
        val body = ui.filterBar(r, "audit:filters") { bar ->
            val row = Row(bar, 6)
            val narrow = bar.w < NARROW_W
            range.draw(ui, row.takeFromRight(range.width(narrow)))
            val results = listOf("all", "ok", "failed").map { Option(it, if (it == "all") tr("kami_libs.common.all") else tr("kami_essentials.admin.audit.$it")) }
            if (narrow) ui.select(row.takeFromRight(SELECT_W), results, ok, key = "audit:ok")?.let { ok = it }
            else ui.segmented(row.takeFromRight(segmentWidth(results)), results, ok, key = "audit:ok")?.let { ok = it }
            val w = (row.rest.w - 12) / 3
            ui.textField(row.take(w), actor, tr("kami_essentials.admin.col.actor"), Icons.PERSON, key = "audit:actor", clearable = true)
            ui.textField(row.take(w), action, tr("kami_essentials.admin.col.action"), Icons.FILTER, key = "audit:action", clearable = true)
            ui.textField(row.remaining(), target, tr("kami_essentials.admin.col.target"), Icons.PERSON, key = "audit:target", clearable = true)
        }
        ui.pagedTable(body, paged, auditColumns(), table, { it.id }, tr("kami_essentials.admin.audit.empty"), "audit", ::auditSeverity)
            .opened?.let { app.auditDialog(it) }
    }
}
