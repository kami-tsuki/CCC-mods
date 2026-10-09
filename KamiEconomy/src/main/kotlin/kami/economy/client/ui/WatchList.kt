package kami.economy.client.ui

import kami.economy.client.admin.MarketSection
import kami.economy.net.Row
import kami.economy.net.Snap
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

internal class WatchList(private val app: MarketApp, private val key: String, private val searchable: Boolean = false, private val open: (String) -> Unit) {
    private val table = TableState<Row>()
    private val box = SearchBox(MarketSection::stack)

    private val columns by localized { listOf(
        itemColumn<Row>({ it.item }, { "$key:${it.item}" }) { open(it.item) },
        rateColumn<Row>(tr("kami_libs.common.price"), 58, rate = { it.perItem() }),
        Column.number<Row>(tr("kami_libs.range.24h"), 52, color = { if (it.change > 0) Palette.success else if (it.change < 0) Palette.danger else Palette.textMuted }, format = { signedPercent(it / 10.0, 1) }) { it.change.toLong() },
        lotPriceColumn<Row>(tr("kami_economy.col.buy_now"), 70, lot = { it.lot }) { it.buy.toLong() },
        lotPriceColumn<Row>(tr("kami_economy.market.sell_now"), 70, empty = -1, lot = { it.lot }) { it.sell.toLong() },
        Column<Row>("", ROW_H) { _, c, row ->
            if (iconButton(Rect(c.x, c.y + 1, c.h - 2, c.h - 2), Icons.CROSS, tr("kami_economy.watch.unpin"), key = "unpin:$key:${row.item}")) app.request("watch", row.item, "off")
        }
    ) }

    private val tableColumns by localized { childColumns(app, columns, 1, 3, 4) }

    fun draw(ui: Ui, r: Rect, snap: Snap?, busy: Boolean) {
        val area = if (searchable) r.dropTop(SMALL_H, 6).also { ui.searchField(r.top(SMALL_H).left(SEARCH_MAX), box.text) } else r
        val rows = snap?.let { s -> if (searchable) box.filter(ui, "watch:rows", s.watchRows) { row -> box.hit(row.item) || row.variants.any { box.hit(it.item) } } else s.watchRows }.orEmpty()
        ui.table(area, tableColumns, rows, table, { it.item }, rowHeight = ROW_H, rowHeightOf = ::childHeight, regroup = ::groupRows, emptyText = tr("kami_libs.common.nothing_found"), key = key, loading = snap == null)
        ui.staleRows(area, busy && rows.isNotEmpty())
    }
}
