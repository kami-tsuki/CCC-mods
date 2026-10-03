package kami.libs.ui.graph

import kami.libs.ui.anim.anim
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.niceStep
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class Ohlc(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double = 0.0, val at: Long = 0L)

enum class ChartStyle { LINE, CANDLE }

object PriceChart {
    private const val AXIS_PAD = 10
    private const val TIME_H = 12
    private const val VOLUME_ZONE = 0.18
    private const val BAR_MIN = 4
    private const val AREA_ALPHA = 0x58
    private const val GLIDE = 10f
    private const val TICKS = 4
    private const val LABEL_SPACING = 64

    private fun num(v: Double) = Format.number(v.roundToInt())

    private fun slotX(plot: Rect, i: Int, count: Int) = plot.x + (2 * i + 1) * plot.w / (2 * count)

    internal fun draw(ui: Ui, r: Rect, data: List<Ohlc>, style: ChartStyle, reference: Double?, start: Long, timeFormat: (Long) -> String, key: Any): Ohlc? {
        val g = ui.g
        val line = style == ChartStyle.LINE
        val n = data.size
        Draw.fill(g, r, Palette.field)
        val plot = Rect(r.x + 4, r.y + 8, r.w - 8 - Draw.width(num(data.maxOfOrNull { it.high } ?: 0.0)) - AXIS_PAD, r.h - 8 - TIME_H)
        val volumeH = (plot.h * VOLUME_ZONE).toInt()
        val price = plot.dropBottom(volumeH, 2)
        val volume = plot.bottom(volumeH)

        val perBar = plot.w.toDouble() / max(1, n)
        val group = if (perBar >= BAR_MIN) 1 else ceil(BAR_MIN / perBar).toInt()
        val bars = ui.filtered("$key:bars", data to group) {
            data.chunked(group) { c -> Ohlc(c.first().open, c.maxOf { it.high }, c.minOf { it.low }, c.last().close, c.sumOf { it.volume }, c.first().at) }
        }
        val series = if (line) data else bars
        val count = series.size

        var lo = if (count == 0) 0.0 else series.minOf { if (line) it.close else it.low }
        var hi = if (count == 0) 1.0 else series.maxOf { if (line) it.close else it.high }
        reference?.let { lo = min(lo, it); hi = max(hi, it) }
        val pad = if (hi > lo) (hi - lo) * 0.08 else max(1.0, hi * 0.05)
        val bottom = ui.anim("$key:lo", (lo - pad).toFloat(), GLIDE).toDouble()
        val top = ui.anim("$key:hi", (hi + pad).toFloat(), GLIDE).toDouble()
        fun py(v: Double) = price.bottom - ((v - bottom) / (top - bottom) * price.h).roundToInt()

        val step = max(1.0, niceStep(top - bottom, TICKS))
        var tick = ceil(bottom / step) * step
        while (tick <= top) {
            val y = py(tick)
            Draw.dashed(g, plot.x, y, plot.right, y, Palette.borderSubtle, 1)
            Draw.text(g, num(tick), plot.right + 6, y - 4, Palette.textMuted)
            tick += step
        }

        val last = series.lastOrNull()
        val base = reference ?: series.firstOrNull()?.open ?: 0.0
        val color = Palette.mix(Palette.danger, Palette.success, ui.anim("$key:up", if (last == null || last.close >= base) 1f else 0f))
        val real = if (start <= 0) count else series.indexOfLast { it.at <= start }.coerceAtLeast(0)
        if (count == 0 || real == count) Draw.textCentered(g, tr("kami_libs.chart.no_trades"), price, Palette.textMuted)
        if (count == 0) return null

        val xs = IntArray(count) { i ->
            if (!line) slotX(plot, i, count) else plot.x + if (count < 2) plot.w - 1 else i * (plot.w - 1) / (count - 1)
        }

        ui.clip(price) {
            reference?.let {
                val y = py(it)
                Draw.dashed(g, plot.x, y, plot.right, y, Palette.alpha(Palette.textMuted, 0xC0), 3)
                Draw.text(g, num(it), plot.x + 2, y - 9, Palette.textMuted)
            }
            if (line) drawLine(g, plot, price, series, real, color, ::py) else drawCandles(g, series, xs, plot.w / count, ::py)
        }

        val barCount = bars.size
        val maxVolume = bars.maxOf { it.volume }
        if (maxVolume > 0) {
            val barW = max(1, plot.w / barCount - 1)
            bars.forEachIndexed { i, b ->
                val h = (b.volume / maxVolume * volume.h).roundToInt()
                val x = slotX(plot, i, barCount) - barW / 2
                Draw.fill(g, Rect(x, volume.bottom - h, barW, h), Palette.alpha(if (b.close >= b.open) Palette.success else Palette.danger, 0x70))
            }
        }

        if (count > 1) {
            val labels = (plot.w / LABEL_SPACING).coerceIn(2, 5)
            for (k in 0 until labels) {
                val i = k * (count - 1) / (labels - 1)
                val text = timeFormat(series[i].at)
                Draw.text(g, text, (xs[i] - Draw.width(text) / 2).coerceIn(plot.x, max(plot.x, plot.right - Draw.width(text))), plot.bottom + 3, Palette.textMuted)
            }
        }

        if (real < count) markers(g, plot, price, series, xs, real, line, ::py)
        if (last != null) tag(g, plot, price, num(last.close), py(last.close), color)

        if (!ui.hover(key, r)) return null
        val idx = series.indices.minBy { abs(xs[it] - ui.mouseX) }
        val hovered = series[idx]
        val hx = xs[idx]
        val hy = py(hovered.close).coerceIn(price.y, price.bottom)
        val cross = Palette.alpha(Palette.text, 0xA0)
        Draw.dashed(g, hx, price.y, hx, volume.bottom, cross, 2)
        Draw.dashed(g, plot.x, hy, plot.right, hy, cross, 2)
        if (line) dot(g, hx, hy, color)
        tag(g, plot, price, num(hovered.close), hy, Palette.textSecondary)
        ui.tooltip(key, r, delay = 0) { tip(hovered, base, line, timeFormat) }
        return hovered
    }

    private fun drawLine(g: GuiGraphics, plot: Rect, price: Rect, data: List<Ohlc>, real: Int, color: Int, py: (Double) -> Int) {
        val n = data.size
        val ys = IntArray(plot.w) { c ->
            if (n < 2) py(data[0].close) else {
                val pos = c.toDouble() * (n - 1) / max(1, plot.w - 1)
                val i = min(floor(pos).toInt(), n - 2)
                py(data[i].close + (data[i + 1].close - data[i].close) * (pos - i))
            }
        }
        val from = if (real >= n) plot.w else if (n < 2) 0 else real * (plot.w - 1) / (n - 1)
        val muted = Palette.alpha(Palette.textMuted, 0xB0)
        for (c in 0 until min(from, plot.w) step 2) g.fill(plot.x + c, ys[c], plot.x + c + 1, ys[c] + 1, muted)
        for (c in from until plot.w) {
            val x = plot.x + c
            val top = ys[c].coerceIn(price.y, price.bottom)
            g.fillGradient(x, top, x + 1, price.bottom, Palette.alpha(color, AREA_ALPHA * (price.bottom - top) / max(1, price.h)), Palette.alpha(color, 0))
            val prev = ys[max(c - 1, from)]
            g.fill(x, min(prev, ys[c]), x + 1, max(prev, ys[c]) + 2, color)
        }
        if (from < plot.w) dot(g, plot.right - 1, ys[plot.w - 1], color)
    }

    private fun drawCandles(g: GuiGraphics, data: List<Ohlc>, xs: IntArray, slot: Int, py: (Double) -> Int) {
        val bodyW = (slot - 1).coerceIn(3, 9) or 1
        data.forEachIndexed { i, c ->
            val color = when {
                c.volume == 0.0 && c.open == c.close -> Palette.alpha(Palette.textMuted, 0xA0)
                c.close >= c.open -> Palette.success
                else -> Palette.danger
            }
            val bodyTop = py(max(c.open, c.close))
            g.fill(xs[i], py(c.high), xs[i] + 1, py(c.low) + 1, color)
            g.fill(xs[i] - bodyW / 2, bodyTop, xs[i] - bodyW / 2 + bodyW, max(bodyTop + 1, py(min(c.open, c.close))), color)
        }
    }

    private fun markers(g: GuiGraphics, plot: Rect, price: Rect, data: List<Ohlc>, xs: IntArray, real: Int, line: Boolean, py: (Double) -> Int) {
        val range = real until data.size
        val hiIdx = range.maxBy { if (line) data[it].close else data[it].high }
        val loIdx = range.minBy { if (line) data[it].close else data[it].low }
        val hiV = if (line) data[hiIdx].close else data[hiIdx].high
        val loV = if (line) data[loIdx].close else data[loIdx].low
        if (hiV == loV) return
        fun label(i: Int, v: Double, y: Int) {
            val text = num(v)
            Draw.text(g, text, (xs[i] - Draw.width(text) / 2).coerceIn(plot.x, max(plot.x, plot.right - Draw.width(text))), y, Palette.textSecondary)
        }
        label(hiIdx, hiV, (py(hiV) - 10).coerceAtLeast(price.y))
        label(loIdx, loV, (py(loV) + 3).coerceAtMost(price.bottom - 8))
    }

    private fun dot(g: GuiGraphics, x: Int, y: Int, color: Int) {
        g.fill(x - 2, y - 2, x + 3, y + 3, color)
        g.fill(x - 1, y - 1, x + 2, y + 2, Palette.field)
    }

    private fun tag(g: GuiGraphics, plot: Rect, price: Rect, text: String, y: Int, color: Int) {
        val cy = y.coerceIn(price.y + 5, price.bottom - 5)
        g.fill(plot.right + 1, cy - 5, plot.right + Draw.width(text) + 8, cy + 5, color)
        Draw.text(g, text, plot.right + 4, cy - 4, Palette.textInverse)
    }

    private fun tip(c: Ohlc, base: Double, line: Boolean, timeFormat: (Long) -> String): Tip {
        val diff = c.close - base
        val pct = if (base > 0) (if (diff > 0) "+" else "") + Format.percent(diff / base, 1) else "–"
        val tone = if (diff > 0) Palette.success else if (diff < 0) Palette.danger else Palette.textSecondary
        val lines = if (line) listOf(tr("kami_libs.chart.price", num(c.close)) to Palette.textSecondary) else listOf(
            tr("kami_libs.chart.candle.open_high", num(c.open), num(c.high)) to Palette.textSecondary,
            tr("kami_libs.chart.candle.low_close", num(c.low), num(c.close)) to Palette.textSecondary
        )
        return Tip(timeFormat(c.at), lines + listOf(
            tr("kami_libs.chart.candle.volume", num(c.volume)) to Palette.textSecondary,
            tr("kami_libs.chart.change", Format.signed(diff.roundToLong()), pct) to tone
        ))
    }
}
