package kami.economy.client.ui

import kami.economy.client.admin.MarketSection
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Column
import kami.libs.ui.widget.itemCell
import kami.libs.ui.widget.textOrder

internal fun <T> itemColumn(
    item: (T) -> String,
    key: (T) -> String,
    color: (T) -> Int = { Palette.text },
    area: Ui.(Rect, T) -> Rect = { c, _ -> c },
    extra: Ui.(Rect, T) -> Unit = { _, _ -> },
    open: (T) -> Unit
) = Column<T>(tr("kami_libs.common.item"), -1, sort = textOrder { MarketSection.stack(item(it)).hoverName.string }) { _, c, row ->
    val stack = MarketSection.stack(item(row))
    val cell = area(c, row)
    if (itemCell(cell, stack, stack.hoverName.string, key(row), color(row))) open(row)
    extra(cell, row)
}
