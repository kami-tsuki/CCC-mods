package kami.libs.ui.graph

import kami.libs.ui.Theme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Ohlc(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double = 0.0, val at: Long = 0L)

enum class ChartStyle { LINE, CANDLE }

object PriceChart {
    private const val AXIS_W = 48
    private const val TIME_AXIS_H = 11
    private const val VOLUME_FRACTION = 0.22
    private const val GRID_LINES = 4
    private const val MAX_POINTS = 56

    private val font get() = Minecraft.getInstance().font

    private fun bounds(data: List<Ohlc>): Pair<Double, Double> {
        if (data.isEmpty()) return 0.0 to 1.0
        val lo = data.minOf { it.low }
        val hi = data.maxOf { it.high }
        return if (hi > lo) lo to hi else lo - 1.0 to hi + 1.0
    }

    private fun downsample(data: List<Ohlc>, max: Int): List<Ohlc> {
        if (data.size <= max) return data
        val groupSize = ceil(data.size / max.toDouble()).toInt()
        return data.chunked(groupSize).map { chunk ->
            Ohlc(
                chunk.first().open, chunk.maxOf { it.high }, chunk.minOf { it.low },
                chunk.last().close, chunk.sumOf { it.volume }, chunk.last().at
            )
        }
    }

    fun draw(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, rawData: List<Ohlc>, style: ChartStyle, mouseX: Int, mouseY: Int): Ohlc? {
        g.fill(x, y, x + w, y + h, Theme.ROW)

        val plotW = (w - AXIS_W).coerceAtLeast(10)
        val plotBottom = y + h - TIME_AXIS_H
        val volumeH = ((plotBottom - y) * VOLUME_FRACTION).toInt().coerceAtLeast(10)
        val priceBottom = plotBottom - volumeH - 2
        val volumeTop = priceBottom + 2
        frame(g, x, y, plotW + AXIS_W, h)

        val data = downsample(rawData, MAX_POINTS)
        if (data.isEmpty()) {
            g.drawCenteredString(font, "No trade history yet.", x + plotW / 2, (y + priceBottom) / 2 - 4, Theme.DIM)
            return null
        }

        val (lo, hi) = bounds(data)
        fun py(v: Double) = y + (priceBottom - y) - ((v - lo) / (hi - lo) * (priceBottom - y)).toInt()

        drawPriceGrid(g, x, y, plotW, priceBottom, lo, hi)
        g.fill(x, priceBottom, x + plotW, priceBottom + 1, Theme.BORDER)
        when (style) {
            ChartStyle.LINE -> drawArea(g, x, y, plotW, priceBottom, data, ::py)
            ChartStyle.CANDLE -> drawCandles(g, x, y, plotW, priceBottom, data, ::py)
        }
        drawVolume(g, x, volumeTop, plotW, volumeH, data)
        drawTimeAxis(g, x, plotBottom, plotW, data)

        val stepX = plotW.toDouble() / data.size
        val hovered = if (mouseX in x until x + plotW && mouseY in y..plotBottom + volumeH) {
            val idx = ((mouseX - x) / stepX).toInt().coerceIn(0, data.size - 1)
            drawCrosshair(g, x, y, plotW, priceBottom, volumeTop + volumeH, data, idx, stepX, ::py)
            data[idx]
        } else null

        return hovered
    }

    private fun frame(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        g.fill(x - 1, y - 1, x + w + 1, y, Theme.BORDER)
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, Theme.BORDER)
        g.fill(x - 1, y - 1, x, y + h + 1, Theme.BORDER)
        g.fill(x + w, y - 1, x + w + 1, y + h + 1, Theme.BORDER)
    }

    private fun drawPriceGrid(g: GuiGraphics, x: Int, y: Int, plotW: Int, priceBottom: Int, lo: Double, hi: Double) {
        for (i in 0..GRID_LINES) {
            val frac = i.toDouble() / GRID_LINES
            val gy = y + ((priceBottom - y) * frac).toInt()
            if (i in 1 until GRID_LINES) dottedH(g, x, x + plotW, gy, Theme.LINE)
            val value = hi - (hi - lo) * frac
            val label = Theme.fmt(value.roundToInt())
            g.drawString(font, label, x + plotW + 4, (gy - 4).coerceIn(y, priceBottom - 8), Theme.DIM, true)
        }
    }

    private fun drawTimeAxis(g: GuiGraphics, x: Int, plotBottom: Int, plotW: Int, data: List<Ohlc>) {
        val labelY = plotBottom + 2
        val first = data.first()
        val last = data.last()
        if (first.at > 0) g.drawString(font, Theme.ago(first.at), x, labelY, Theme.DIM, true)
        if (last.at > 0) {
            val text = Theme.ago(last.at)
            g.drawString(font, text, x + plotW - font.width(text), labelY, Theme.DIM, true)
        }
    }

    private fun drawArea(g: GuiGraphics, x: Int, y: Int, plotW: Int, priceBottom: Int, data: List<Ohlc>, py: (Double) -> Int) {
        if (data.size < 2) {
            val cy = py(data.first().close).coerceIn(y, priceBottom)
            g.fill(x, cy - 1, x + plotW, cy + 2, Theme.ACCENT)
            return
        }
        val stepX = plotW.toDouble() / (data.size - 1)
        for (i in 0 until data.size - 1) {
            val x0 = x + (i * stepX).toInt()
            val x1 = x + ((i + 1) * stepX).toInt()
            val y0 = py(data[i].close).coerceIn(y, priceBottom)
            val y1 = py(data[i + 1].close).coerceIn(y, priceBottom)
            fillSlope(g, x0, y0, x1, y1, priceBottom, Theme.alpha(Theme.ACCENT, 0x30))
        }
        for (i in 0 until data.size - 1) {
            val x0 = x + (i * stepX).toInt()
            val x1 = x + ((i + 1) * stepX).toInt()
            val y0 = py(data[i].close).coerceIn(y, priceBottom)
            val y1 = py(data[i + 1].close).coerceIn(y, priceBottom)
            thickLine(g, x0, y0, x1, y1, Theme.ACCENT)
        }
    }

    private fun fillSlope(g: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, bottom: Int, color: Int) {
        val steps = max(1, x1 - x0)
        for (s in 0..steps) {
            val px = x0 + s
            val t = s.toDouble() / steps
            val topY = (y0 + (y1 - y0) * t).roundToInt()
            if (topY < bottom) g.fill(px, topY, px + 1, bottom, color)
        }
    }

    private fun thickLine(g: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val steps = max(1, max(abs(dx), abs(dy)))
        for (s in 0..steps) {
            val px = x0 + dx * s / steps
            val py = y0 + dy * s / steps
            g.fill(px, py - 1, px + 1, py + 2, color)
        }
    }

    private fun drawCandles(g: GuiGraphics, x: Int, y: Int, plotW: Int, priceBottom: Int, data: List<Ohlc>, py: (Double) -> Int) {
        val cw = plotW.toDouble() / data.size
        val bodyW = max(2, (cw * 0.7).toInt())
        val inset = ((cw - bodyW) / 2).toInt()
        data.forEachIndexed { i, c ->
            val cx = x + (i * cw).toInt() + inset
            val color = if (c.close >= c.open) Theme.GOOD else Theme.BAD
            val bodyTop = py(max(c.open, c.close)).coerceIn(y, priceBottom)
            val bodyBot = py(min(c.open, c.close)).coerceIn(y, priceBottom)
            val wickX = cx + bodyW / 2
            g.fill(wickX, py(c.high).coerceIn(y, priceBottom), wickX + 1, py(c.low).coerceIn(y, priceBottom), color)
            g.fill(cx, bodyTop, cx + bodyW, max(bodyTop + 1, bodyBot), color)
        }
    }

    private fun drawVolume(g: GuiGraphics, x: Int, volumeTop: Int, plotW: Int, volumeH: Int, data: List<Ohlc>) {
        val maxV = data.maxOf { it.volume }.coerceAtLeast(1.0)
        val cw = plotW.toDouble() / data.size
        val barW = max(1, (cw * 0.7).toInt())
        val inset = ((cw - barW) / 2).toInt()
        data.forEachIndexed { i, c ->
            val bh = ((c.volume / maxV) * volumeH).toInt()
            val cx = x + (i * cw).toInt() + inset
            val color = if (c.close >= c.open) Theme.GOOD else Theme.BAD
            g.fill(cx, volumeTop + volumeH - bh, cx + barW, volumeTop + volumeH, Theme.alpha(color, 0x90))
        }
    }

    private fun drawCrosshair(
        g: GuiGraphics, x: Int, y: Int, plotW: Int, priceBottom: Int, bottom: Int,
        data: List<Ohlc>, idx: Int, stepX: Double, py: (Double) -> Int
    ) {
        val c = data[idx]
        val cx = (x + (idx + 0.5) * stepX).toInt().coerceIn(x, x + plotW)
        val cy = py(c.close).coerceIn(y, priceBottom)
        dottedV(g, cx, y, bottom, Theme.TEXT)
        dottedH(g, x, x + plotW, cy, Theme.TEXT)

        val priceLabel = Theme.fmt(c.close.roundToInt())
        val plw = font.width(priceLabel) + 5
        val ply = (cy - 4).coerceIn(y, priceBottom - 8)
        g.fill(x + plotW, ply - 1, x + plotW + plw, ply + 9, Theme.ACCENT)
        g.drawString(font, priceLabel, x + plotW + 2, ply, 0xFF101014.toInt(), false)

        val tooltip = listOf(
            "O ${Theme.fmt(c.open.roundToInt())}  H ${Theme.fmt(c.high.roundToInt())}",
            "L ${Theme.fmt(c.low.roundToInt())}  C ${Theme.fmt(c.close.roundToInt())}",
            "Vol ${Theme.fmt(c.volume.roundToInt())}" + if (c.at > 0) "  ·  ${Theme.ago(c.at)}" else ""
        )
        val tw = tooltip.maxOf { font.width(it) } + 8
        val th = tooltip.size * 10 + 4
        var tx = cx + 6
        if (tx + tw > x + plotW) tx = cx - tw - 6
        val ty = y + 2
        g.fill(tx, ty, tx + tw, ty + th, 0xF0101014.toInt())
        frame(g, tx, ty, tw, th)
        tooltip.forEachIndexed { i, line -> g.drawString(font, line, tx + 4, ty + 3 + i * 10, Theme.TEXT, true) }
    }

    private fun dottedV(g: GuiGraphics, x: Int, y0: Int, y1: Int, color: Int) {
        var py = y0
        while (py < y1) { g.fill(x, py, x + 1, min(py + 1, y1), color); py += 3 }
    }

    private fun dottedH(g: GuiGraphics, x0: Int, x1: Int, y: Int, color: Int) {
        var px = x0
        while (px < x1) { g.fill(px, y, min(px + 1, x1), y + 1, color); px += 3 }
    }
}
