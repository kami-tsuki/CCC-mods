package kami.libs.ui.core

import net.minecraft.client.gui.GuiGraphics
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

object UiScale {
    const val DEFAULT = 0.9f
    val choices = listOf(0.8f, 0.9f, 1.0f)

    @Volatile
    var factor = DEFAULT
        set(v) { field = v.coerceIn(0.5f, 1.5f) }

    fun snap(v: Float) = choices.minByOrNull { abs(it - v) } ?: DEFAULT

    fun unscale(v: Int, scale: Float = factor) = (v / scale).toInt()

    fun layout(px: Int) = ceil(px / factor).toInt()

    fun enableScissor(g: GuiGraphics, r: Rect) {
        val s = factor
        g.enableScissor(floor(r.x * s).toInt(), floor(r.y * s).toInt(), ceil(r.right * s).toInt(), ceil(r.bottom * s).toInt())
    }
}
