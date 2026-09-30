package kami.geology.client

import kami.libs.ui.core.Rect
import kami.libs.ui.map.Viewport
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object HeatmapDraw {
    val ramp = intArrayOf(0x440154, 0x3B528B, 0x21918C, 0x5EC962, 0xFDE725)
    const val BRIGHT = 0xC0C8D0
    private const val GRID = 0x30FFFFFF
    private const val AXIS = 0x70FFFFFF

    fun abgr(rgb: Int) = 0xFF shl 24 or ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)

    fun blend(base: Int, top: Int, t: Double): Int = Palette.mix(base, top, t.toFloat()) and 0xFFFFFF or (0xFF shl 24)

    fun heatColor(t: Double): Int {
        val scaled = t.coerceIn(0.0, 1.0) * (ramp.size - 1)
        val i = min(scaled.toInt(), ramp.size - 2)
        return blend(0xFF000000.toInt() or ramp[i], ramp[i + 1], scaled - i) and 0xFFFFFF
    }

    fun short(v: Double) = when {
        v >= 1_000_000 -> oneDecimal(v / 1_000_000) + "M"
        v >= 1_000 -> oneDecimal(v / 1_000) + "k"
        else -> "%.0f".format(v)
    }

    private fun oneDecimal(v: Double) = "%.1f".format(v).removeSuffix(".0").removeSuffix(",0")

    fun grid(g: GuiGraphics, view: Viewport, r: Rect) {
        val step = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384).firstOrNull { it / view.unitsPerPx >= 80 } ?: 16384
        var gx = Math.floorDiv(floor(view.worldX(r.x.toDouble())).toInt(), step) * step
        while (gx <= view.worldX(r.right.toDouble())) {
            val sx = view.screenX(gx.toDouble()).roundToInt()
            Draw.vline(g, sx, r.y, r.h, if (gx == 0) AXIS else GRID)
            Draw.text(g, gx.toString(), sx + 3, r.y + 3, Palette.textMuted)
            gx += step
        }
        var gz = Math.floorDiv(floor(view.worldZ(r.y.toDouble())).toInt(), step) * step
        while (gz <= view.worldZ(r.bottom.toDouble())) {
            val sy = view.screenY(gz.toDouble()).roundToInt()
            Draw.hline(g, r.x, sy, r.w, if (gz == 0) AXIS else GRID)
            Draw.text(g, gz.toString(), r.x + 3, sy + 3, Palette.textMuted)
            gz += step
        }
    }

    fun scale(g: GuiGraphics, view: Viewport, r: Rect) {
        val bpp = view.unitsPerPx
        val length = intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000).lastOrNull { it / bpp <= 140 } ?: 10
        val px = max(2, (length / bpp).roundToInt())
        val x = r.x + 10
        val y = r.bottom - 12
        Draw.fill(g, Rect(x - 1, y - 14, px + 2, 18), Palette.alpha(Palette.canvas, 0xB0))
        Draw.hline(g, x, y, px, Palette.text)
        Draw.vline(g, x, y - 3, 4, Palette.text)
        Draw.vline(g, x + px - 1, y - 3, 4, Palette.text)
        Draw.text(g, tr("kami_geology.map.scale", length), x + 2, y - 12)
        Draw.text(g, "N", r.right - 14, r.y + 14)
        Draw.vline(g, r.right - 11, r.y + 24, 16, Palette.text)
    }

    fun marker(g: GuiGraphics, view: Viewport) {
        val player = Minecraft.getInstance().player ?: return
        val sx = view.screenX(player.x).roundToInt()
        val sy = view.screenY(player.z).roundToInt()
        Draw.box(g, Rect(sx - 3, sy - 3, 7, 7), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        Draw.text(g, tr("kami_geology.map.you"), sx + 6, sy - 4, Palette.text, shadow = true)
    }
}
