package kami.libs.ui.app

import kami.libs.admin.StatPoint
import kami.libs.admin.StatRow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.KpiTile
import kami.libs.ui.widget.Series
import kami.libs.ui.widget.Slice
import kami.libs.ui.widget.card
import kami.libs.ui.widget.cardColumns
import kami.libs.ui.widget.donut
import kami.libs.ui.widget.kpiRow
import kami.libs.ui.widget.legend
import kami.libs.ui.widget.lineChart
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.skeleton
import kami.libs.ui.widget.staleVeil
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.Items
import kotlin.math.roundToLong

const val STAT_TILE_H = 44
const val STAT_CHART_H = 112
const val STAT_TOP_H = 140
private const val GAP = 6
private const val TILE_W = 100
private const val CARD_W = 190
private const val NARROW_W = 560
private const val SCROLLBAR_W = 4
private const val ROW_H = 11
private const val DONUT_ROWS = 8

class StatLine(val label: String, val points: List<StatPoint>, val color: Int, val area: Boolean = false)

class StatCard(val h: Int, val draw: Ui.(Rect) -> Unit)

fun List<StatPoint>.total() = sumOf { it.v }

fun List<StatPoint>.delta(other: List<StatPoint>): List<StatPoint> {
    val a = associate { it.at to it.v }
    val b = other.associate { it.at to it.v }
    return (a.keys + b.keys).sorted().map { StatPoint(it, (a[it] ?: 0.0) - (b[it] ?: 0.0)) }
}

fun idLabel(id: String): String {
    val rl = ResourceLocation.tryParse(id)
    val named = rl?.let {
        BuiltInRegistries.ITEM.getOptional(it).orElse(null)?.takeIf { i -> i !== Items.AIR }?.description?.string
            ?: BuiltInRegistries.BLOCK.getOptional(it).orElse(null)?.name?.string
            ?: BuiltInRegistries.ENTITY_TYPE.getOptional(it).orElse(null)?.description?.string
    }
    return named ?: (rl?.path ?: id).replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun statLabel(row: StatRow) = row.name.ifEmpty { idLabel(row.key) }

fun Ui.statChart(r: Rect, title: String, icon: Icon, lines: List<StatLine>, format: (Long) -> String = { Format.compact(it) }, key: String) {
    val body = card(r, title, icon)
    val ats = lines.flatMap { l -> l.points.map { it.at } }.distinct().sorted()
    val chart = if (lines.size > 1) body.dropTop(legend(body.x, body.y, body.w, lines.map { it.color to it.label }) + 2) else body
    val series = lines.map { l ->
        val values = l.points.associate { it.at to it.v }
        Series(l.label, ats.map { (values[it] ?: 0.0).roundToLong() }, l.color, l.area)
    }
    lineChart(chart, series, ats.map { Format.ago(it) }, format, key = key)
}

fun Ui.statTop(r: Rect, title: String, icon: Icon, rows: List<StatRow>, color: Int = Palette.chart[1], format: (Long) -> String = { Format.number(it) }) {
    val body = card(r, title, icon)
    if (rows.isEmpty()) {
        Draw.textCentered(g, tr("kami_libs.chart.no_data"), body, Palette.textMuted)
        return
    }
    val top = rows.maxOf { it.v }.coerceAtLeast(1)
    rows.take((body.h / ROW_H).coerceAtLeast(1)).forEachIndexed { i, row ->
        val line = Rect(body.x, body.y + i * ROW_H, body.w, ROW_H - 1)
        Draw.fill(g, line.withWidth((line.w * row.v / top).toInt().coerceAtLeast(1)), Palette.alpha(color, 0x38))
        val value = format(row.v)
        Draw.textRight(g, value, line.right - 2, line.y + 1, Palette.text)
        Draw.text(g, Draw.fit(statLabel(row), line.w - Draw.width(value) - 10), line.x + 2, line.y + 1, Palette.text)
    }
}

fun Ui.statDonut(r: Rect, title: String, icon: Icon, rows: List<StatRow>, key: String, format: (Long) -> String = { Format.number(it) }, center: Boolean = true) {
    val body = card(r, title, icon)
    if (rows.isEmpty()) {
        Draw.textCentered(g, tr("kami_libs.chart.no_data"), body, Palette.textMuted)
        return
    }
    val slices = rows.take(DONUT_ROWS).mapIndexed { i, row -> Slice(statLabel(row), row.v, Palette.chart[i % Palette.chart.size]) }
    donut(body.left(body.h), slices, if (center) Format.compact(slices.sumOf { it.value }) else null, key = key)
    val side = body.dropLeft(body.h, GAP)
    if (side.w >= 40) legend(side.x, side.y, side.w, slices.map { it.color to tr("kami_libs.format.pair", it.label, format(it.value)) })
}

fun Ui.statPage(key: String, r: Rect, range: TimeRange, board: StatBoard, tiles: List<KpiTile>, cards: List<StatCard>) {
    board.poll()
    val bar = r.top(CONTROL_H)
    range.draw(this, bar.right(range.width(bar.w < NARROW_W)))
    val body = r.dropTop(CONTROL_H + GAP)
    if (!board.ready) {
        if (board.remote.error != null && !board.remote.loading) remoteFailed(body, board.remote, key) else skeleton(body)
        return
    }
    val width = body.w - SCROLLBAR_W
    val tileCols = cardColumns(width, TILE_W, tiles.size.coerceAtLeast(1))
    val cardCols = cardColumns(width, CARD_W, 3)
    val tileRows = tiles.chunked(tileCols)
    val cardRows = cards.chunked(cardCols)
    val height = tileRows.size * (STAT_TILE_H + GAP) + cardRows.sumOf { row -> row.maxOf { it.h } + GAP }
    scroll(key, body, height) { c ->
        var y = c.y
        tileRows.forEach { row ->
            val cells = Rect(c.x, y, c.w, STAT_TILE_H).columns(tileCols, GAP)
            row.forEachIndexed { i, t -> kpiRow(cells[i], listOf(t), key = "$key:kpi") }
            y += STAT_TILE_H + GAP
        }
        cardRows.forEach { row ->
            val h = row.maxOf { it.h }
            val cells = Rect(c.x, y, c.w, h).columns(cardCols, GAP)
            row.forEachIndexed { i, card -> card.draw(this, cells[i]) }
            y += h + GAP
        }
    }
    if (board.stale) staleVeil(body)
}

fun statChartCard(title: String, icon: Icon, key: String, lines: List<StatLine>, format: (Long) -> String = { Format.compact(it) }) =
    StatCard(STAT_CHART_H) { statChart(it, title, icon, lines, format, key) }

fun statTopCard(title: String, icon: Icon, rows: List<StatRow>, format: (Long) -> String = { Format.number(it) }) =
    StatCard(STAT_TOP_H) { statTop(it, title, icon, rows, format = format) }

fun statDonutCard(title: String, icon: Icon, key: String, rows: List<StatRow>, format: (Long) -> String = { Format.number(it) }, center: Boolean = true) =
    StatCard(STAT_TOP_H) { statDonut(it, title, icon, rows, key, format, center) }
