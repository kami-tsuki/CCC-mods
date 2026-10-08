package kami.economy.client.admin

import kami.libs.ui.style.Severity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kami.economy.admin.OrderRow
import kami.economy.client.ui.ROW_H
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.Paged
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.pagedTable
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.ChipSpec
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.TableState
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.chipFlow
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.filterBar
import kami.libs.ui.widget.searchField
import kami.libs.ui.widget.textField

private val SIDES = listOf("all", "sell", "buy", "auction")
private const val MAX_CANCEL = 50

internal class OrdersPage(private val app: KamiApp) : Page() {
    override val title get() = tr("kami_economy.admin.nav.orders")
    private val box = MarketSection.searchBox()
    private val owner = TextState()
    private var side = SIDES[0]
    private val paged = Paged("market.orders", OrderRow.serializer()) { listOf(box.text.text.trim(), MarketSection.ids(box), owner.text.trim(), side) }
    private val table = TableState<OrderRow>()

    private fun columns() = listOf(
        Column.text<OrderRow>(tr("kami_economy.admin.col.side"), 48) { tr("kami_economy.admin.side.${it.side}") },
        itemColumn<OrderRow>(tr("kami_libs.common.item"), { it.item }, { it.label }),
        Column.text<OrderRow>(tr("kami_economy.admin.col.owner"), 80) { it.ownerName },
        Column.number<OrderRow>(tr("kami_economy.admin.col.price"), 60, color = { Palette.money }, format = { Format.money(it) }) { it.price.toLong() },
        Column.number<OrderRow>(tr("kami_libs.common.amount"), 50) { it.amount.toLong() },
        Column.number<OrderRow>(tr("kami_economy.admin.col.lot"), 32) { it.lot.toLong() },
        Column.number<OrderRow>(tr("kami_economy.admin.col.placed"), 56, format = { Format.ago(it) }) { it.placedAt }
    )

    override fun draw(ui: Ui, r: Rect) {
        val picked = table.selected.filterIsInstance<String>()
        val lock = lockUnless(adminManager) ?: when {
            picked.isEmpty() -> tr("kami_economy.admin.cancel.none")
            picked.size > MAX_CANCEL -> tr("kami_economy.admin.cancel.too_many", MAX_CANCEL)
            else -> null
        }
        val pending = lock == null && adminPending("market.cancel", listOf(picked.joinToString(",")))
        val shown = paged.rows.map { it.entry }.take(MAX_CANCEL)
        val shownLock = lockUnless(adminManager) ?: tr("kami_economy.admin.cancel.none").takeIf { shown.isEmpty() }
        val shownPending = shownLock == null && adminPending("market.cancel", listOf(shown.joinToString(",")))
        val body = ui.filterBar(r, "market-orders:filters") { bar ->
            val row = Row(bar, 6)
            if (ui.edgeButton(row, tr("kami_economy.admin.cancel", picked.size), Icons.CROSS, ButtonStyle.DANGER, lock == null, lock, pending = pending, key = "market-orders:cancel")) cancel(picked)
            if (ui.edgeButton(row, tr("kami_economy.admin.cancel_shown", shown.size), Icons.CROSS, ButtonStyle.DANGER, shownLock == null, shownLock, pending = shownPending, key = "market-orders:cancel-shown")) cancel(shown)
            val chips = row.takeFromRight(190)
            ui.chipFlow(Stack(chips.x, chips.y + 2, chips.w), SIDES.map { ChipSpec(tr("kami_economy.admin.side.$it"), selected = it == side) }, "market-orders:side")
                ?.let { side = SIDES[it] }
            ui.textField(row.takeFromRight(90), owner, tr("kami_economy.admin.col.owner"), Icons.PERSON, clearable = true, key = "market-orders:owner")
            ui.searchField(row.remaining(), box.text, key = "market-orders:search")
        }
        ui.pagedTable(body, paged, columns(), table, { it.entry }, tr("kami_economy.admin.orders.empty"), "market-orders", rowHeight = ROW_H, multi = true)
    }

    private fun cancel(picked: List<String>) = reasonDialog({ app.open(it) }, tr("kami_economy.admin.cancel.title", picked.size), danger = true) { reason, _, _ ->
        app.adminAct("market.cancel", listOf(picked.joinToString(",")), reason) { r ->
            table.clear()
            val failed = ((r.data as? JsonObject)?.get("failed") as? JsonArray)?.size ?: 0
            if (failed > 0) app.toast(Severity.WARNING, tr("kami_economy.admin.cancel.failed", failed))
        }
    }
}
