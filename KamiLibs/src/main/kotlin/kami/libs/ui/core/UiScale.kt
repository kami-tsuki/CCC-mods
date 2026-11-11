package kami.libs.ui.core

import net.minecraft.client.gui.GuiGraphics
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

object UiScale {
    const val DEFAULT = 1.0f
    private const val MIN = 0.75f
    private const val MAX = 1.5f

    @Volatile
    private var desired = DEFAULT

    @Volatile
    private var gui = 2

    @Volatile
    var factor = DEFAULT
        private set

    val active: Boolean get() = factor != 1f

    val choices: List<Float> get() = choices(gui)

    fun choices(guiScale: Int): List<Float> {
        val g = max(1, guiScale)
        return (ceil(MIN * g).toInt()..floor(MAX * g).toInt()).map { it / g.toFloat() }
    }

    fun nearest(want: Float, guiScale: Int): Float = choices(guiScale).minByOrNull { abs(it - want) } ?: DEFAULT

    fun snap(v: Float) = nearest(v, gui)

    fun configure(want: Float) {
        desired = want
        factor = nearest(want, gui)
    }

    fun sync(guiScale: Int) {
        if (guiScale == gui) return
        gui = max(1, guiScale)
        factor = nearest(desired, gui)
    }

    fun unscale(v: Int, scale: Float = factor) = if (scale == 1f) v else floor(v / scale).toInt()

    fun layout(px: Int, scale: Float = factor) = if (scale == 1f) px else ceil(px / scale).toInt()

    fun enableScissor(g: GuiGraphics, r: Rect) {
        val s = factor
        if (s == 1f) g.enableScissor(r.x, r.y, r.right, r.bottom)
        else g.enableScissor(floor(r.x * s).toInt(), floor(r.y * s).toInt(), ceil(r.right * s).toInt(), ceil(r.bottom * s).toInt())
    }
}
