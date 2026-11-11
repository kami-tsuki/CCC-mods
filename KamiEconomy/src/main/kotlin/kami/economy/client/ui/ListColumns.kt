package kami.economy.client.ui

import kami.economy.client.admin.MarketSection
import kami.economy.net.HeldRow
import kami.economy.net.OrderRow
import kami.economy.net.Row
import kami.economy.economy.ClientVariants as Variants
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.text.SearchQuery
import kami.libs.ui.text.ItemSearch
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import net.minecraft.world.item.ItemStack

private const val CHILD_H = 14
private const val CHILD_INDENT = 10

internal fun Ui.questDot(cell: Rect, rarity: Int, item: String) {
    val dot = Rect(cell.right + 2, cell.centerY - 3, 6, 6)
    Draw.fill(g, dot, questColor(rarity))
    Draw.outline(g, dot.grow(1), Palette.alpha(Palette.outline, 0x80))
    tooltip("quest-dot:$item", dot.grow(2), tr("kami_economy.quest.ui.row.tip", questName(rarity)))
}

internal fun Row.isChild() = Variants.of(item) != null

internal fun childHeight(row: Row) = if (row.isChild()) CHILD_H else ROW_H

internal fun groupRows(parents: List<Row>): List<Row> =
    if (parents.none { it.variants.isNotEmpty() }) parents
    else parents.flatMap { p -> listOf(p) + p.variants.map { p.copy(item = it.item, lot = it.lot, buy = it.buy, sell = it.sell, variants = emptyList(), course = (if (p.course > 0) p.course else p.last.toDouble()) / p.lot.coerceAtLeast(1) * it.mul / it.div * it.lot, last = 0) } }

internal fun factorText(item: String): String {
    val v = Variants.of(item) ?: return ""
    return if (v.mul >= v.div) "×" + Format.number((v.mul / v.div).toLong()) else "÷" + Format.number((v.div / v.mul).toLong())
}

internal fun variantRatio(item: String): String {
    val v = Variants.of(item) ?: return ""
    val big = v.mul >= v.div
    return tr(if (big) "kami_economy.variant.ratio.big" else "kami_economy.variant.ratio.small", MarketSection.stack(item).hoverName.string, MarketSection.stack(v.core).hoverName.string, Format.number((if (big) v.mul / v.div else v.div / v.mul).toLong()))
}

internal fun Ui.drawSuffixed(r: Rect, text: String, suffix: String, color: Int) {
    val y = r.y + (r.h - 8) / 2
    val sw = if (suffix.isEmpty()) 0 else Draw.width(suffix) + 2
    if (sw > 0 && Draw.width(text) + sw <= r.w) {
        Draw.textRight(g, suffix, r.right, y, Palette.textMuted)
        Draw.textRight(g, text, r.right - sw, y, color)
    } else Draw.textRight(g, text, r.right, y, color)
}

internal fun Ui.drawUnit(r: Rect, price: Long, lot: Int, color: Int) =
    drawSuffixed(r, Format.unitMoney(price, lot), if (price > 0 && lot > 1) "/" + Format.number(lot.toLong()) else "", color)

internal fun Row.perItem(): Double = (if (course > 0) course else last.toDouble()) / lot.coerceAtLeast(1)

internal fun lotText(lot: Int) = if (lot > 1) "×" + Format.number(lot.toLong()) else ""

internal fun Ui.drawLot(r: Rect, price: Long, lot: Int, color: Int) {
    if (price <= 0) Draw.textRight(g, "–", r.right, r.y + (r.h - 8) / 2, Palette.textMuted)
    else drawSuffixed(r, Format.money(price), lotText(lot), color)
}

internal fun <T> lotPriceColumn(title: String, width: Int, color: (T) -> Int = { Palette.money }, empty: Long = Long.MAX_VALUE, lot: (T) -> Int, price: (T) -> Long) =
    Column<T>(title, width, Align.RIGHT, compareBy<T> { price(it).takeIf { p -> p > 0 }?.let { p -> p * 1000 / lot(it).coerceAtLeast(1) } ?: empty }, tr("kami_economy.col.per_lot_now")) { _, r, row -> drawLot(r, price(row), lot(row), color(row)) }

internal fun <T> rateColumn(title: String, width: Int, color: (T) -> Int = { Palette.text }, rate: (T) -> Double) =
    Column<T>(title, width, Align.RIGHT, compareBy<T> { rate(it) }, tr("kami_economy.col.per_item")) { _, r, row -> Draw.textRight(g, Format.rateMoney(rate(row)), r.right, r.y + (r.h - 8) / 2, if (rate(row) > 0) color(row) else Palette.textMuted) }

internal fun <T> unitPriceColumn(title: String, width: Int, color: (T) -> Int = { Palette.text }, lot: (T) -> Int, price: (T) -> Long) =
    Column<T>(title, width, Align.RIGHT, compareBy<T> { price(it) * 1000 / lot(it).coerceAtLeast(1) }, tr("kami_economy.col.per_item")) { _, r, row -> drawUnit(r, price(row), lot(row), color(row)) }

internal fun ownAware(c: Column<Row>, side: String, own: (Row) -> Int) = Column<Row>(c.title, c.width, c.align, c.sort, c.tip) { g, cell, row ->
    c.cell(this, g, cell, row)
    val mine = own(row).takeIf { it > 0 && !row.isChild() } ?: return@Column
    Draw.text(g, "•", cell.x + 1, cell.y + (cell.h - 8) / 2, Palette.info)
    tooltip("own-$side:${row.item}", cell) { Tip(tr("kami_economy.own.$side"), listOf(tr("kami_economy.own.tip", Format.unitMoney(mine.toLong(), row.lot)) to Palette.textSecondary)) }
}

internal fun childTip(row: Row) = Tip(MarketSection.stack(row.item).hoverName.string, listOfNotNull(
    variantRatio(row.item).takeIf { it.isNotEmpty() }?.let { it to Palette.textSecondary },
    tr("kami_economy.variant.price", Format.unitMoney(row.buy.toLong(), row.lot), Format.unitMoney(row.sell.toLong(), row.lot)) to Palette.textSecondary,
    tr("kami_economy.tip.lot", row.lot) to Palette.textMuted
))

internal fun childColumns(app: MarketApp, cols: List<Column<Row>>, price: Int, buy: Int, sell: Int): List<Column<Row>> = cols.mapIndexed { i, c ->
    Column<Row>(c.title, c.width, c.align, c.sort, c.tip) { gg, cell, row ->
        if (!row.isChild()) c.cell(this, gg, cell, row)
        else {
            Draw.fill(g, Rect(cell.x - 3, cell.y, cell.w + 6, cell.h), Palette.alpha(Palette.washDark, 0x24))
            when (i) {
                0 -> {
                    val tag = factorText(row.item)
                    val tagW = Draw.width(tag) + 4
                    val stack = MarketSection.stack(row.item)
                    val area = Rect(cell.x + CHILD_INDENT, cell.y, cell.w - CHILD_INDENT, cell.h)
                    Draw.text(g, "└", cell.x + 2, cell.y + (cell.h - 8) / 2, Palette.textMuted)
                    if (itemCell(area.dropRight(tagW), stack, stack.hoverName.string, "child:${row.item}", Palette.textSecondary)) app.openItem(row.item)
                    Draw.textRight(g, tag, area.right, cell.y + (cell.h - 8) / 2, Palette.textMuted)
                    tooltip("child-tip:${row.item}", area.dropLeft(cell.h)) { childTip(row) }
                }
                price -> Draw.textRight(g, Format.rateMoney(row.perItem()), cell.right, cell.y + (cell.h - 8) / 2, Palette.textSecondary)
                buy -> drawLot(cell, row.buy.toLong(), row.lot, Palette.textSecondary)
                sell -> drawLot(cell, row.sell.toLong(), row.lot, Palette.textSecondary)
                else -> Unit
            }
        }
    }
}

internal fun groupHeld(rows: List<HeldRow>): List<HeldRow> {
    if (rows.none { Variants.of(it.item) != null }) return rows
    val byCore = rows.groupBy { Variants.core(it.item) }
    return rows.map { Variants.core(it.item) }.distinct().flatMap { core ->
        byCore.getValue(core).sortedBy { r -> Variants.of(r.item)?.let { it.mul.toDouble() / it.div } ?: 1.0 }
    }
}

internal fun supplyColumn() = Column<Row>(tr("kami_economy.col.supply"), 70, sort = compareBy { if (it.available < 0) Int.MAX_VALUE else it.available }) { _, c, row ->
    val y = c.y + (c.h - 8) / 2
    val avail = countText(row.available, "–")
    val demand = countText(row.demand, "–")
    val dw = Draw.width(demand)
    Draw.textRight(g, demand, c.right, y, if (row.demand != 0) Palette.danger else Palette.textMuted)
    Draw.textRight(g, "/", c.right - dw - 2, y, Palette.textMuted)
    Draw.textRight(g, avail, c.right - dw - 8, y, if (row.available != 0) Palette.success else Palette.textMuted)
    tooltip("supply:${row.item}", c) {
        val stack = MarketSection.stack(row.item).maxStackSize
        fun units(v: Int) = if (v < 0) countText(v) else unitsText((v.toLong() * row.lot).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), stack)
        Tip(tr("kami_economy.col.supply"), listOfNotNull(
            tr("kami_economy.tip.available", units(row.available)) to Palette.success,
            tr("kami_economy.tip.demand", units(row.demand)) to Palette.danger,
            row.left.takeIf { it >= 0 && row.demand != 0 }?.let { tr("kami_economy.tip.left", units(it)) to Palette.textSecondary }
        ))
    }
}

internal class SearchBox(private val stack: (String) -> ItemStack) {
    val text = TextState()
    private var raw = ""
    private var parsed = SearchQuery.EMPTY
    private var idsFor: String? = null
    private var ids = ""

    val query: SearchQuery get() {
        if (text.text != raw) { raw = text.text; parsed = SearchQuery.parse(raw) }
        return parsed
    }

    fun hit(item: String, vararg extra: String) = ItemSearch.matches(query, stack(item), *extra)

    fun ids(extra: () -> Collection<String>, max: Int): String {
        val q = query
        if (idsFor != raw) { idsFor = raw; ids = searchIds(q, extra(), max) }
        return ids
    }

    fun <T> filter(ui: Ui, key: String, rows: List<T>, test: (T) -> Boolean): List<T> =
        if (query.isEmpty) rows else ui.filtered(key, listOf(text.text, rows)) { rows.filter(test) }
}

internal fun orderOpen(app: MarketApp, o: OrderRow) = app.openItem(o.item, if (o.bid == o.mine) "buy" else "sell", if (o.mine) "order" else "instant")

internal fun orderColumns(app: MarketApp, side: Boolean, owner: Boolean, fill: Boolean, open: (OrderRow) -> Unit = { orderOpen(app, it) }) = buildList {
    add(itemColumn<OrderRow>({ it.item }, { "order:${it.id}" }, { if (it.mine) Palette.brass else Palette.text }, open = open))
    if (side) add(Column.text<OrderRow>(tr("kami_libs.common.side"), 44, color = { if (it.bid) Palette.success else Palette.danger }) { tr(if (it.bid) "kami_economy.side.buy" else "kami_economy.side.sell") })
    if (owner) {
        add(Column.text<OrderRow>(tr("kami_libs.common.owner"), 80, color = { if (it.mine) Palette.brass else Palette.textSecondary }) { it.ownerName })
        add(Column<OrderRow>(tr("kami_libs.common.country"), 90, sort = textOrder { it.country }) { _, c, o ->
            val y = c.y + (c.h - 8) / 2
            flag(Rect(c.x, y, 11, 8), o.color, o.flag)
            fitText(c.x + 14, y, c.w - 14, o.country, if (o.relation == "embargo") Palette.danger else Palette.textSecondary, key = "oc:${o.id}")
            if (o.country.isNotEmpty()) tooltip("order-country:${o.id}", c, app.countryTip(o.country, o.relation))
        })
    }
    add(unitPriceColumn<OrderRow>(tr("kami_libs.common.price"), 64, lot = { it.lot }) { it.price.toLong() })
    add(unitsColumn<OrderRow>(tr("kami_libs.common.amount"), 90, { MarketSection.stack(it.item).maxStackSize }) { it.amount })
    add(Column.number<OrderRow>(tr("kami_economy.col.age"), 50, format = { Format.ago(it) }) { it.placedAt })
    add(Column<OrderRow>(tr("kami_libs.common.status"), 64, sort = compareBy { !it.best }) { _, c, o ->
        val label = tr(if (o.best) "kami_economy.status.best" else if (o.mine) "kami_economy.status.undercut" else "kami_economy.status.none")
        statusPill(c.x, c.y + (c.h - 13) / 2, label, if (o.best) Severity.SUCCESS else if (o.mine) Severity.WARNING else Severity.NEUTRAL, key = "os:${o.id}", maxWidth = c.w)
    })
    if (fill) {
        val label = tr("kami_economy.orders.fill")
        add(Column<OrderRow>("", buttonWidth(label)) { _, c, o ->
            if (!o.mine && button(Rect(c.x, c.y + 1, c.w, c.h - 2), label, key = "fill:${o.id}")) orderOpen(app, o)
        })
    }
}
