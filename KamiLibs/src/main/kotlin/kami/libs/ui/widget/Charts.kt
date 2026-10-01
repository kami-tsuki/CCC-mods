package kami.libs.ui.widget

import kami.libs.ui.text.tr
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import kami.libs.ui.graph.ChartStyle
import kami.libs.ui.graph.Ohlc
import kami.libs.ui.graph.PriceChart
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class Series(val label: String, val values: List<Long>, val color: Int, val area: Boolean = false, val dashedFrom: Int = Int.MAX_VALUE)
class Slice(val label: String, val value: Long, val color: Int)

fun Ui.sparkline(r: Rect, values: List<Long>, color: Int) {
    if (values.size < 2) return
    val lo = values.min()
    val hi = values.max().let { if (it == lo) lo + 1 else it }
    fun py(v: Long) = r.bottom - 1 - ((v - lo) * (r.h - 1) / (hi - lo)).toInt()
    for (i in 1 until values.size) {
        val x0 = r.x + (i - 1) * (r.w - 1) / (values.size - 1)
        val x1 = r.x + i * (r.w - 1) / (values.size - 1)
        Draw.line(g, x0, py(values[i - 1]), x1, py(values[i]), Palette.alpha(color, 0xD0))
    }
}

private fun niceStep(range: Double, ticks: Int): Double {
    val raw = range / max(1, ticks)
    val mag = 10.0.pow(floor(log10(max(raw, 1e-9))))
    val norm = raw / mag
    return mag * when {
        norm < 1.5 -> 1.0
        norm < 3 -> 2.0
        norm < 7 -> 5.0
        else -> 10.0
    }
}

fun Ui.lineChart(r: Rect, series: List<Series>, labels: List<String>, format: (Long) -> String = { Format.compact(it) }, zeroLine: Boolean = true, key: Any = "chart") {
    val all = series.flatMap { it.values }
    val count = series.maxOfOrNull { it.values.size } ?: 0
    Draw.fill(g, r, Palette.sunken)
    if (count < 2 || all.isEmpty()) {
        Draw.textCentered(g, tr("kami_libs.chart.no_history"), r, Palette.textMuted)
        return
    }
    var lo = min(all.min(), if (zeroLine) 0 else all.min()).toDouble()
    var hi = all.max().toDouble()
    if (hi <= lo) hi = lo + 1
    val step = niceStep(hi - lo, 4)
    lo = floor(lo / step) * step
    hi = ceil(hi / step) * step
    val axisW = max(Draw.width(format(hi.toLong())), Draw.width(format(lo.toLong()))) + 6
    val plot = Rect(r.x + axisW, r.y + 6, r.w - axisW - 6, r.h - 18)
    fun py(v: Double) = plot.bottom - ((v - lo) / (hi - lo) * plot.h).toInt()
    fun px(i: Int) = plot.x + i * plot.w / (count - 1)
    var t = lo
    while (t <= hi + 1e-6) {
        val y = py(t)
        Draw.hline(g, plot.x, y, plot.w, if (t == 0.0) Palette.borderStrong else Palette.alpha(Palette.border, 0x80))
        Draw.textRight(g, format(t.toLong()), plot.x - 4, y - 4, Palette.textMuted)
        t += step
    }
    labels.takeIf { it.isNotEmpty() }?.let { ls ->
        val every = max(1, ceil(ls.size / (plot.w / 40.0)).toInt())
        ls.forEachIndexed { i, l -> if (i % every == 0 && i < count) Draw.text(g, l, (px(i) - Draw.width(l) / 2).coerceIn(plot.x, plot.right - Draw.width(l)), plot.bottom + 4, Palette.textMuted) }
    }
    series.forEach { s ->
        if (s.area) {
            for (i in 1 until s.values.size) {
                val x0 = px(i - 1)
                val x1 = px(i)
                for (x in x0 until x1) {
                    val f = (x - x0).toDouble() / max(1, x1 - x0)
                    val v = s.values[i - 1] + (s.values[i] - s.values[i - 1]) * f
                    val y = py(v)
                    val base = py(max(lo, 0.0))
                    g.fill(x, min(y, base), x + 1, max(y, base), Palette.alpha(s.color, 0x30))
                }
            }
            g.flush()
        }
        for (i in 1 until s.values.size) {
            val x0 = px(i - 1); val y0 = py(s.values[i - 1].toDouble())
            val x1 = px(i); val y1 = py(s.values[i].toDouble())
            if (i > s.dashedFrom) Draw.dashed(g, x0, y0, x1, y1, s.color) else Draw.line(g, x0, y0, x1, y1, s.color)
        }
    }
    if (hovering(plot)) {
        val i = ((mouseX - plot.x) * (count - 1) + plot.w / 2) / max(1, plot.w)
        if (i in 0 until count) {
            val x = px(i)
            Draw.vline(g, x, plot.y, plot.h, Palette.alpha(Palette.text, 0x60))
            series.forEach { s -> s.values.getOrNull(i)?.let { v -> Draw.fill(g, Rect(x - 1, py(v.toDouble()) - 1, 3, 3), s.color) } }
            tooltip(key, plot) {
                Tip(labels.getOrNull(i), series.mapNotNull { s -> s.values.getOrNull(i)?.let { tr(if (i > s.dashedFrom) "kami_libs.chart.point.forecast" else "kami_libs.format.pair", s.label, format(it)) to s.color } })
            }
        }
    }
}

fun Ui.barChart(r: Rect, positive: List<Long>, negative: List<Long>, labels: List<String>, format: (Long) -> String = { Format.compact(it) }, key: Any = "bars") {
    val count = max(positive.size, negative.size)
    Draw.fill(g, r, Palette.sunken)
    if (count == 0) { Draw.textCentered(g, tr("kami_libs.chart.no_data"), r, Palette.textMuted); return }
    val top = max(1L, max(positive.maxOrNull() ?: 0, negative.maxOrNull() ?: 0))
    val plot = r.inset(6, 4, 6, 4)
    val mid = plot.centerY
    val slot = plot.w.toFloat() / count
    val barW = max(1, (slot * 0.7f).toInt())
    Draw.hline(g, plot.x, mid, plot.w, Palette.borderStrong)
    for (i in 0 until count) {
        val x = plot.x + (i * slot).toInt() + ((slot - barW) / 2).toInt()
        val up = positive.getOrElse(i) { 0 }
        val down = negative.getOrElse(i) { 0 }
        val uh = (up * (plot.h / 2 - 1) / top).toInt()
        val dh = (down * (plot.h / 2 - 1) / top).toInt()
        Draw.fill(g, Rect(x, mid - uh, barW, uh), Palette.success)
        Draw.fill(g, Rect(x, mid + 1, barW, dh), Palette.danger)
        val cell = Rect(plot.x + (i * slot).toInt(), plot.y, max(1, slot.toInt()), plot.h)
        tooltip("$key:$i", cell) { Tip(labels.getOrNull(i), listOf(tr("kami_libs.chart.in", "+" + format(up)) to Palette.success, tr("kami_libs.chart.out", "-" + format(down)) to Palette.danger, tr("kami_libs.chart.net", format(up - down)) to Palette.text)) }
    }
}

fun Ui.stackedBar(r: Rect, slices: List<Slice>, key: Any = "stack") {
    val total = slices.sumOf { it.value }.takeIf { it > 0 } ?: return Draw.fill(g, r, Palette.alpha(Palette.border, 0x80))
    var x = r.x
    slices.forEachIndexed { i, s ->
        val w = if (i == slices.lastIndex) r.right - x else (r.w * s.value / total).toInt()
        val cell = Rect(x, r.y, w, r.h)
        Draw.fill(g, cell, s.color)
        tooltip("$key:$i", cell) { Tip.text(tr("kami_libs.format.paren", Format.number(s.value), Format.percent(s.value.toDouble() / total)), s.label) }
        x += w
    }
}

fun Ui.donut(r: Rect, slices: List<Slice>, center: String? = null, key: Any = "donut") {
    val size = min(r.w, r.h)
    val cx = r.x + size / 2.0
    val cy = r.y + size / 2.0
    val outer = size / 2.0
    val inner = outer * 0.58
    val total = slices.sumOf { it.value }.toDouble()
    val hoverAngle = atan2(mouseY - cy, mouseX - cx).let { (it + PI / 2 + 2 * PI) % (2 * PI) }
    val hoverDist = sqrt((mouseX - cx).let { it * it } + (mouseY - cy).let { it * it })
    val hovered = if (total > 0 && hoverDist in inner..outer && hovering(r)) {
        var acc = 0.0
        slices.indexOfFirst { acc += it.value / total * 2 * PI; hoverAngle < acc }
    } else -1
    for (py in 0 until size) for (px in 0 until size) {
        val dx = px + 0.5 - size / 2.0
        val dy = py + 0.5 - size / 2.0
        val d = sqrt(dx * dx + dy * dy)
        if (d > outer || d < inner) continue
        val color = if (total <= 0) Palette.border else {
            val a = (atan2(dy, dx) + PI / 2 + 2 * PI) % (2 * PI)
            var acc = 0.0
            var idx = slices.lastIndex
            for ((i, s) in slices.withIndex()) { acc += s.value / total * 2 * PI; if (a < acc) { idx = i; break } }
            if (idx == hovered) Palette.lighten(slices[idx].color, 0.25f) else slices[idx].color
        }
        g.fill(r.x + px, r.y + py, r.x + px + 1, r.y + py + 1, color)
    }
    g.flush()
    center?.let { Draw.textCentered(g, it, Rect(r.x, r.y, size, size), Palette.text) }
    if (hovered >= 0) {
        val s = slices[hovered]
        tooltip(key, r) { Tip.text(tr("kami_libs.format.paren", Format.number(s.value), Format.percent(s.value / total)), s.label) }
    }
}

fun Ui.legend(x: Int, y: Int, width: Int, entries: List<Pair<Int, String>>): Int {
    var cx = x
    var cy = y
    entries.forEach { (color, label) ->
        val w = Draw.width(label) + 16
        if (cx + w > x + width && cx > x) { cx = x; cy += 11 }
        legendItem(cx, cy, color, label)
        cx += w
    }
    return cy + 11 - y
}

fun forecast(start: Long, perDay: Long, days: Int): List<Long> = List(days) { start + perDay * (it + 1) }


fun Ui.gradientLegend(
    r: Rect, colors: List<Int>, min: Double, max: Double, format: (Double) -> String = { Format.compact(it.toLong()) },
    ticks: List<Double> = listOf(min, max), title: String? = null, key: Any = "gradient"
) {
    var y = r.y
    title?.let { Draw.text(g, Draw.fit(it, r.w), r.x, y, Palette.textMuted); y += Draw.LINE }
    val bar = Rect(r.x, y, r.w, 6)
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    val last = (bar.w - 1).coerceAtLeast(1)
    for (px in 0 until bar.w) Draw.fill(g, Rect(bar.x + px, bar.y, 1, bar.h), Palette.opaque(gradientAt(colors, px.toDouble() / last)))
    Draw.outline(g, bar.grow(1), Palette.border)
    for (v in ticks) {
        val px = bar.x + ((v - min) / span * last).roundToInt().coerceIn(0, last)
        Draw.vline(g, px, bar.bottom + 1, 2, Palette.textSecondary)
        val label = format(v)
        val lw = Draw.width(label)
        Draw.text(g, label, (px - lw / 2).coerceIn(r.x, (r.right - lw).coerceAtLeast(r.x)), bar.bottom + 4, Palette.textMuted)
    }
    tooltip(key, bar.grow(2), delay = 0) { Tip.text(format(min + ((mouseX - bar.x).toDouble() / last).coerceIn(0.0, 1.0) * span)) }
}

fun gradientAt(colors: List<Int>, fraction: Double): Int {
    if (colors.size < 2) return colors.firstOrNull() ?: 0
    val p = fraction.coerceIn(0.0, 1.0) * (colors.size - 1)
    val i = floor(p).toInt().coerceAtMost(colors.size - 2)
    return Palette.mix(colors[i], colors[i + 1], (p - i).toFloat())
}

fun Ui.priceChart(r: Rect, data: List<Ohlc>, style: ChartStyle, key: Any = "price"): Ohlc? {
    val over = hover(key, r)
    val hovered = PriceChart.draw(g, r.x, r.y, r.w, r.h, data, style, if (over) mouseX else Int.MIN_VALUE, if (over) mouseY else Int.MIN_VALUE, readout = false)
    if (hovered != null) tooltip(key, r, delay = 0) { Tip(null, PriceChart.readout(hovered).map { it to Palette.textSecondary }) }
    return hovered
}
