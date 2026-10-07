package kami.economy.client.ui

import kami.economy.net.Row
import kami.economy.net.Snap
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

internal class WatchList(private val app: MarketApp, private val key: String, private val open: (String) -> Unit) {
    private val table = TableState<Row>()

    private val columns = listOf(
        itemColumn<Row>({ it.item }, { "$key:${it.item}" }) { open(it.item) },
        Column.number<Row>(tr("kami_economy.personal.col.last"), 64, tip = tr("kami_economy.col.per_lot"), format = ::priceText) { it.last.toLong() },
        Column.number<Row>(tr("kami_economy.col.change"), 52, tip = tr("kami_economy.col.change.tip"), color = { if (it.change > 0) Palette.success else if (it.change < 0) Palette.danger else Palette.textMuted }, format = ::permille) { it.change.toLong() },
        Column<Row>("", ROW_H) { _, c, row ->
            if (iconButton(Rect(c.x, c.y + 1, c.h - 2, c.h - 2), Icons.CROSS, tr("kami_economy.watch.unpin"), key = "unpin:$key:${row.item}")) app.request("watch", row.item, "off")
        }
    )

    fun draw(ui: Ui, r: Rect, snap: Snap?, busy: Boolean) {
        val rows = snap?.watchRows.orEmpty()
        ui.table(r, columns, rows, table, { it.item }, rowHeight = ROW_H, emptyText = tr("kami_libs.common.nothing_found"), key = key, loading = snap == null)
        ui.staleRows(r, busy && rows.isNotEmpty())
    }
}
