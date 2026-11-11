package kami.claims.client.admin

import kami.claims.admin.NewsFull
import kami.claims.admin.NewsRequests
import kami.claims.admin.NewsRow
import kami.claims.press.Corrections
import kami.libs.press.Markup
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Paged
import kami.libs.ui.app.Remote
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.pagedTable
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.app.remoteFailed
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.press.Article
import kami.libs.ui.press.Newsprint
import kami.libs.ui.press.newsprintView
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.card
import kami.libs.ui.widget.checkbox
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.filterBar
import kami.libs.ui.widget.property
import kami.libs.ui.widget.searchField
import kami.libs.ui.widget.select

private const val GAP = 6
private const val DETAIL_GAP = 4
private val DAYS = listOf(0, 1, 7, 30, 90)
private val KINDS = listOf("", "system", "country", "editorial")

internal class PressArticlesPage(private val app: KamiApp) : Page() {
    private val search = TextState()
    private var kind = ""
    private var country = ""
    private var days = 0
    private var deleted = false
    private val paged = Paged(NewsRequests.LIST, NewsRow.serializer()) { listOf(kind, country, days.toString(), search.text.trim(), deleted.toString()) }
    private val table = TableState<NewsRow>()
    private var selected: Long? = null
    private val detail = Remote(NewsRequests.GET, NewsFull.serializer()) { listOfNotNull(selected?.toString()) }
    private var editing = false
    private val editor = PressEditor(false)
    private val columnsMemo = Memo()
    private val previewMemo = Memo()

    override val title get() = tr("kami_claims.admin.press.articles")

    override fun draw(ui: Ui, r: Rect) {
        PressAdminSection.names.poll()
        val body = ui.filterBar(r, "press:filters") { filters(ui, it) }
        val (left, right) = body.columns(listOf(1.2f, 1f), 8)
        val events = ui.pagedTable(left, paged, columns(), table, { it.id }, tr("kami_claims.admin.empty"), "press-articles", severity = { if (it.deleted) Severity.NEUTRAL else null })
        if (events.selectionChanged) (table.selected.lastOrNull() as? Long)?.let {
            selected = it
            editing = false
        }
        detailPane(ui, right)
    }

    private fun filters(ui: Ui, bar: Rect) {
        val row = Row(bar, GAP)
        ui.select(row.take(KIND_W), KINDS.map { Option(it, if (it.isEmpty()) tr("kami_claims.admin.press.all_kinds") else tr("kami_claims.admin.press.kind.$it")) }, kind, key = "press-kind")?.let { kind = it }
        val names = PressAdminSection.names.run { data ?: latest }.orEmpty()
        val countries = listOf(Option("", tr("kami_claims.admin.press.all_countries"))) + names.entries.sortedBy { it.value.lowercase() }.map { Option(it.key, it.value) }
        ui.select(row.take(COUNTRY_W), countries, country, key = "press-country")?.let { country = it }
        ui.select(row.take(DAYS_W), DAYS.map { Option(it, if (it == 0) tr("kami_claims.admin.press.any_time") else tr("kami_claims.admin.press.days", it)) }, days, key = "press-days")?.let { days = it }
        ui.checkbox(row.take(DELETED_W), tr("kami_claims.admin.press.deleted"), deleted, key = "press-deleted")?.let { deleted = it }
        ui.searchField(row.remaining(), search, key = "press-search")
    }

    private fun columns() = columnsMemo.of(Format.locale) { listOf(
        Column.number<NewsRow>(tr("kami_libs.common.time"), 60, format = { Format.ago(it) }) { it.created },
        Column.text<NewsRow>(tr("kami_claims.admin.col.kind"), 62) { tr("kami_claims.admin.press.kind.${it.kind.lowercase()}") },
        Column.text<NewsRow>(tr("kami_claims.admin.press.title"), -1, color = { if (it.deleted) Palette.textMuted else Palette.text }) { it.title },
        Column.text<NewsRow>(tr("kami_libs.common.country"), 70) { it.country }
    ) }

    private fun detailPane(ui: Ui, r: Rect) {
        val id = selected
        if (id == null) {
            ui.card(r, tr("kami_claims.admin.press.detail"), Icons.SCROLL, key = "press-detail")
            return
        }
        detail.poll()
        val d = detail.data?.takeIf { it.id == id }
        if (d == null) {
            if (detail.error != null && !detail.loading) ui.remoteFailed(r, detail, "press-detail")
            return
        }
        val body = ui.card(r, d.title.ifEmpty { "#${d.id}" }, Icons.SCROLL, trailing = tr("kami_claims.admin.press.kind.${d.kind.lowercase()}"), key = "press-detail")
        if (editing) edit(ui, body, d) else view(ui, body, d)
    }

    private fun view(ui: Ui, body: Rect, d: NewsFull) {
        var y = body
        fun prop(label: String, value: String, color: Int = Palette.text) {
            y = ui.property(y, label, value, color)
        }
        prop(tr("kami_libs.common.country"), d.country.ifEmpty { "-" })
        prop(tr("kami_claims.admin.press.authors"), d.authors.joinToString(", ").ifEmpty { "-" })
        prop(tr("kami_libs.common.time"), Format.exact(d.created))
        if (d.deletedAt > 0) prop(tr("kami_claims.admin.press.deleted_by", d.deletedBy), d.deletedReason, Palette.danger)
        if (d.corrected.isNotEmpty()) prop(tr("kami_claims.admin.press.corrected"), Corrections.date(d.corrected), Palette.warning)
        val bar = Row(body.bottom(CONTROL_H), GAP)
        val lock = lockUnless(adminManager)
        if (ui.edgeButton(bar, tr("kami_claims.admin.press.delete"), Icons.REMOVE, ButtonStyle.DANGER, lock == null && d.deletedAt == 0L, lock ?: tr("kami_claims.admin.press.already_deleted"),
                pending = adminPending(NewsRequests.DELETE, listOf(d.id.toString())), key = "press-delete"))
            reasonDialog({ app.open(it) }, tr("kami_claims.admin.press.delete.title", d.title), true) { reason, _, _ -> app.adminAct(NewsRequests.DELETE, listOf(d.id.toString()), reason) }
        val locked = lock ?: tr("kami_claims.admin.press.not_editable").takeIf { d.system || d.deletedAt > 0 }
        if (ui.edgeButton(bar, tr("kami_claims.admin.press.modify"), Icons.EDIT, enabled = locked == null, disabledReason = locked, key = "press-modify")) {
            editor.load(d)
            editing = true
        }
        val preview = previewMemo.of(d) { Article(d.title, d.subtitle, Markup.parse(d.body), Newsprint.signature(Format.exact(d.created), d.country, d.authors)) }
        ui.newsprintView(Rect(y.x, y.y + DETAIL_GAP, y.w, (body.bottom - CONTROL_H - GAP - y.y - DETAIL_GAP).coerceAtLeast(20)), preview, "press-preview:${d.id}")
    }

    private fun edit(ui: Ui, body: Rect, d: NewsFull) {
        editor.draw(ui, body.dropBottom(CONTROL_H, GAP), "press-edit:${d.id}")
        val args = listOf(d.id.toString(), editor.title.text.trim(), editor.subtitle.text.trim(), editor.body.text.trim())
        val why = lockUnless(adminManager) ?: editor.problem()
        val bar = Row(body.bottom(CONTROL_H), GAP)
        if (ui.edgeButton(bar, tr("kami_libs.common.save"), Icons.EDIT, enabled = why == null, disabledReason = why, pending = adminPending(NewsRequests.MODIFY, args), key = "press-save"))
            reasonDialog({ app.open(it) }, tr("kami_claims.admin.press.modify.title", d.title)) { reason, _, _ -> app.adminAct(NewsRequests.MODIFY, args, reason) { editing = false } }
        if (ui.edgeButton(bar, tr("kami_libs.common.cancel"), Icons.CLOSE, key = "press-cancel")) editing = false
    }

    private companion object {
        const val KIND_W = 90
        const val COUNTRY_W = 110
        const val DAYS_W = 90
        const val DELETED_W = 100
    }
}
