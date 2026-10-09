package kami.essentials.client.admin

import kami.libs.ui.app.Paged
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.adminConfirmAct
import kami.libs.ui.app.pagedTable
import kami.essentials.admin.BanRow
import kami.essentials.admin.MuteRow
import kami.essentials.admin.WarnRow
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminPending
import kami.libs.ui.style.Draw
import kami.libs.ui.widget.buttonWidth
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.widget.ChipSpec
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.chipFlow
import kami.libs.ui.widget.filterBar
import kami.libs.ui.widget.searchField
import kami.libs.ui.app.Page
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.button
import kotlinx.serialization.KSerializer

private class Restriction(val uuid: String, val name: String, val reason: String, val by: String, val since: Long, val until: Long?, val ip: Boolean = false)

private class RestrictionPage<T>(
    private val app: AdminApp, private val titleKey: String, private val liftKey: String, private val liftTitleKey: String, private val emptyKey: String,
    private val liftAct: String, view: String, item: KSerializer<T>, private val map: (T) -> Restriction
) : Page() {
    override val title get() = tr(titleKey)
    private val paged = Paged(view, item) { emptyList() }
    private val table = TableState<T>()

    override fun draw(ui: Ui, r: Rect) {
        val lock = lockUnless(adminManager)
        val lift = tr(liftKey)
        val ip = tr("kami_essentials.admin.col.ip")
        val columns = listOf(
            playerColumn<T>(tr("kami_libs.common.name"), { map(it).uuid }, { map(it).name }),
            Column.text<T>(tr("kami_libs.admin.reason"), -1) { map(it).reason },
            Column.text<T>(tr("kami_libs.common.by"), 70) { map(it).by },
            Column.number<T>(tr("kami_essentials.admin.col.since"), 56, format = { Format.ago(it) }) { map(it).since },
            Column.number<T>(tr("kami_essentials.admin.col.until"), 64, format = { if (it == Long.MAX_VALUE) tr("kami_essentials.admin.permanent") else Format.until(it) }) { map(it).until ?: Long.MAX_VALUE },
            Column.text<T>(ip, Draw.width(ip) + 4, color = { Palette.warning }) { if (map(it).ip) ip else "" },
            Column<T>("", buttonWidth(lift)) { _, cell, row ->
                val x = map(row)
                if (button(cell.inset(0, 1), lift, style = ButtonStyle.SECONDARY, enabled = lock == null, disabledReason = lock, pending = adminPending(liftAct, listOf(x.uuid)), key = "lift:${x.uuid}"))
                    app.adminConfirmAct(tr(liftTitleKey, x.name), liftAct, listOf(x.uuid))
            }
        )
        paged.rows.forEach { map(it).let { x -> AdminData.remember(x.uuid, x.name) } }
        ui.pagedTable(r, paged, columns, table, { map(it).uuid }, tr(emptyKey), titleKey).opened?.let { app.openPlayer(map(it).uuid) }
    }
}

internal fun banPage(app: AdminApp): Page = RestrictionPage(
    app, "kami_essentials.admin.nav.bans", "kami_essentials.admin.unban", "kami_essentials.admin.unban.title", "kami_essentials.admin.bans.empty",
    "players.unban", "moderation.bans", BanRow.serializer()
) { Restriction(it.uuid, it.name, it.reason, it.by, it.since, it.until, it.ip) }

internal fun mutePage(app: AdminApp): Page = RestrictionPage(
    app, "kami_essentials.admin.nav.mutes", "kami_essentials.admin.unmute", "kami_essentials.admin.unmute.title", "kami_essentials.admin.mutes.empty",
    "players.unmute", "moderation.mutes", MuteRow.serializer()
) { Restriction(it.uuid, it.name, it.reason, it.by, it.since, it.until) }

private val WARN_FILTERS = listOf("active", "revoked", "all")

internal fun warnColumns(app: AdminApp, lock: String?, withPlayer: Boolean) = listOfNotNull(
    if (withPlayer) playerColumn<WarnRow>(tr("kami_libs.common.name"), { it.uuid }, { it.name }) else null,
    Column.text<WarnRow>(tr("kami_libs.admin.reason"), -1) { it.reason },
    Column.text<WarnRow>(tr("kami_libs.common.by"), 70) { it.by },
    Column.number<WarnRow>(tr("kami_libs.common.when"), 56, format = { Format.ago(it) }) { it.at },
    Column.text<WarnRow>(tr("kami_libs.common.state"), 110, color = { if (it.revoked == null) Palette.warning else Palette.textMuted }) { w ->
        w.revoked?.let { tr("kami_essentials.admin.warn.revoked", it.by, it.reason) } ?: tr("kami_essentials.admin.filter.active")
    },
    tr("kami_essentials.admin.unwarn").let { label -> Column<WarnRow>("", buttonWidth(label)) { _, cell, w ->
        if (w.revoked == null && button(cell.inset(0, 1), label, style = ButtonStyle.SECONDARY, enabled = lock == null, disabledReason = lock,
                pending = adminPending("players.unwarn", listOf(w.uuid, w.id.toString())), key = "unwarn:${w.id}"))
            reasonDialog({ app.open(it) }, tr("kami_essentials.admin.unwarn.title", w.name)) { why, _, _ -> app.adminAct("players.unwarn", listOf(w.uuid, w.id.toString()), why) }
    } }
)

internal class WarnPage(private val app: AdminApp) : Page() {
    override val title get() = tr("kami_essentials.admin.nav.warns")
    private val search = TextState()
    private var filter = WARN_FILTERS[0]
    private val paged = Paged("moderation.warns", WarnRow.serializer()) { listOf(filter, search.text.trim()) }
    private val table = TableState<WarnRow>()

    override fun draw(ui: Ui, r: Rect) {
        val body = ui.filterBar(r, "warns:filters") { bar ->
            val row = Row(bar, 6)
            val chips = row.takeFromRight(170)
            ui.chipFlow(Stack(chips.x, chips.y + 2, chips.w), WARN_FILTERS.map { ChipSpec(filterLabel(it), selected = it == filter) }, "warns:filter")
                ?.let { filter = WARN_FILTERS[it] }
            ui.searchField(row.remaining(), search, key = "warns:search")
        }
        paged.rows.forEach { AdminData.remember(it.uuid, it.name) }
        ui.pagedTable(body, paged, warnColumns(app, lockUnless(adminManager), true), table, { it.id }, tr("kami_essentials.admin.warns.empty"), "warns")
            .opened?.let { app.openPlayer(it.uuid) }
    }
}
