package kami.libs.ui.anim

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import net.minecraft.client.gui.GuiGraphics
import kotlin.math.abs

private const val SHIMMER_BAND = 14
private const val SHIMMER_PERIOD = 1.6
private const val SHIMMER_PEAK = 0.55f

object Effects {
    private val dot = IntArray(2)

    fun drawParticles(g: GuiGraphics, sparks: Sparks, floaters: Floaters, glows: Glows) {
        for (i in 0 until sparks.capacity) {
            if (sparks.life[i] <= 0f) continue
            val fade = sparks.fade(i)
            val color = Palette.fade(sparks.color[i], fade)
            if (color == 0) continue
            val size = if (fade > 0.5f) 2 else 1
            val px = sparks.x[i].toInt()
            val py = sparks.y[i].toInt()
            g.fill(px, py, px + size, py + size, color)
        }
        for (i in 0 until glows.capacity) {
            val fade = glows.fade(i)
            val color = Palette.fade(glows.color[i], fade * 0.4f)
            if (color != 0) g.fill(glows.x[i] - 4, glows.y[i] - 4, glows.x[i] + 4, glows.y[i] + 4, color)
        }
        for (i in 0 until floaters.capacity) {
            val label = floaters.text[i] ?: continue
            val color = Palette.fade(floaters.color[i], floaters.fade(i))
            if (color != 0) Draw.text(g, label, floaters.x[i] - Draw.font.width(label) / 2, floaters.y[i].toInt(), color, true)
        }
    }

    fun dots(g: GuiGraphics, x0: Int, y0: Int, mid: Int, x1: Int, y1: Int, color: Int, spacing: Int, shift: Int) {
        val length = EdgePath.length(x0, y0, mid, x1, y1)
        var d = shift
        while (d <= length) {
            EdgePath.pointAt(d, x0, y0, mid, x1, y1, dot)
            g.fill(dot[0], dot[1], dot[0] + 2, dot[1] + 2, color)
            d += spacing
        }
    }
}

fun Ui.shimmer(r: Rect, color: Int) {
    if (reduceMotion || r.w <= SHIMMER_BAND || r.h <= 0) return
    val head = r.x - SHIMMER_BAND + ((time % SHIMMER_PERIOD) / SHIMMER_PERIOD * (r.w + SHIMMER_BAND)).toInt()
    val half = SHIMMER_BAND / 2f
    for (i in 0 until SHIMMER_BAND) {
        val x = head + i
        if (x < r.x || x >= r.right) continue
        val shade = Palette.fade(color, (1f - abs(i - half) / half) * SHIMMER_PEAK)
        if (shade != 0) g.fill(x, r.y, x + 1, r.bottom, shade)
    }
}

fun Ui.flowDots(x0: Int, y0: Int, mid: Int, x1: Int, y1: Int, color: Int, spacing: Int = 12, speed: Float = 24f) {
    val shift = if (reduceMotion) 0 else ((time * speed) % spacing).toInt()
    Effects.dots(g, x0, y0, mid, x1, y1, color, spacing, shift)
}

fun Ui.burst(x: Int, y: Int, color: Int, count: Int = 24) {
    if (reduceMotion) glows.emit(x, y, color) else sparks.emit(x.toFloat(), y.toFloat(), color, count)
}

fun Ui.floatText(x: Int, y: Int, text: String, color: Int) {
    if (!reduceMotion) floaters.emit(x, y, text, color)
}
