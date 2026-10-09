package kami.economy.client.admin

import kami.economy.admin.FrozenRow
import kami.economy.client.ui.ROW_H
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Remote
import kami.libs.ui.app.adminConfirmAct
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.remoteTable
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kami.libs.ui.widget.cellText
import kami.libs.ui.widget.Align
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.textOrder
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.filterBar
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.searchField
import kami.libs.ui.widget.secret
import kotlinx.serialization.builtins.ListSerializer

private fun FrozenRow.typeText() = if (type.isEmpty()) "-" else trOr("kami_economy.admin.intent.${type.lowercase()}", type)

private fun FrozenRow.stateText() = if (state.isEmpty()) "-" else trOr("kami_economy.admin.state.${state.lowercase()}", state)

private fun FrozenRow.payeeText() = if (owed <= 0) "" else payees.split(", ").joinToString(", ") { it.ifEmpty { tr("kami_economy.admin.frozen.unknown_payee") } }

internal class FrozenPage(private val app: KamiApp) : Page() {
    override val title get() = tr("kami_economy.admin.nav.frozen")
    override val subtitle get() = tr("kami_economy.admin.frozen.help")
    private val remote = Remote("market.frozen", ListSerializer(FrozenRow.serializer()))
    private val table = TableState<FrozenRow>().apply { sortColumn = 4; descending = true }
    private val box = MarketSection.searchBox()

    private fun columns(lock: String?) = listOf(
        tipColumn<FrozenRow>(tr("kami_libs.common.type"), 84, compareBy { it.id }, { Tip("#${it.id} ${it.typeText()}", listOf(it.stateText() to Palette.textSecondary)) }) { it.typeText() },
        Column<FrozenRow>(tr("kami_libs.common.owner"), 64, sort = textOrder { it.actorName }) { _, r, row ->
            if (row.actorName.isEmpty()) secret(r, row.actor, "frozen-owner:${row.actor}") else cellText(r, row.actorName, key = "frozen-name:${row.actor}")
        },
        itemColumn<FrozenRow>(tr("kami_libs.common.item"), { it.item }),
        Column.number<FrozenRow>(tr("kami_economy.personal.col.qty"), 32) { it.qty.toLong() },
        tipColumn<FrozenRow>(tr("kami_economy.admin.col.owed"), 56, compareBy { it.owed }, { row -> row.payeeText().takeIf { it.isNotEmpty() }?.let { Tip.text(it, tr("kami_economy.admin.col.payees")) } },
            Align.RIGHT, { Palette.money }) { Format.money(it.owed) },
        Column<FrozenRow>("", 2 * ROW_H + 2) { _, cell, row ->
            val (release, close) = cell.columnsFixed(cell.h, cell.h, gap = 2)
            val args = listOf(row.id.toString())
            val releaseLock = lock ?: tr("kami_economy.admin.frozen.nothing_owed").takeIf { row.owed <= 0 }
            if (iconButton(release, Icons.CHECK, tr("kami_economy.admin.frozen.release"), releaseLock == null, key = "frozen-release:${row.id}", disabledReason = releaseLock, pending = adminPending("market.frozen_release", args)))
                app.adminConfirmAct(tr("kami_economy.admin.frozen.release.title", row.id, Format.money(row.owed)), "market.frozen_release", args)
            if (iconButton(close, Icons.TREASURY, tr("kami_libs.common.close"), lock == null, key = "frozen-close:${row.id}", disabledReason = lock, pending = adminPending("market.frozen_close", args)))
                app.adminConfirmAct(tr("kami_economy.admin.frozen.close.title", row.id), "market.frozen_close", args, danger = true)
        }
    )

    override fun draw(ui: Ui, r: Rect) {
        remote.poll()
        val body = ui.filterBar(r, "market-frozen:filters") { bar -> ui.searchField(bar, box.text, key = "market-frozen:search") }
        val rows = box.filter(ui, "market-frozen:rows", remote.data.orEmpty()) { box.hit(it.item, it.actorName, it.payees, it.typeText(), it.stateText()) }
        val empty = if (box.text.text.isBlank()) tr("kami_economy.admin.frozen.empty") else tr("kami_libs.common.nothing_found")
        ui.remoteTable(body, remote, columns(lockUnless(adminManager)), rows, table, { it.id }, empty, "market-frozen", rowHeight = ROW_H)
    }
}
