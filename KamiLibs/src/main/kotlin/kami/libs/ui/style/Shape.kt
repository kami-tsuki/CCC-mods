package kami.libs.ui.style

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Bevel { NONE, RAISED, GLASS, NEON }

data class ShapeDef(
    val radius: Int = 0,
    val chamfer: Int = 0,
    val border: Int = 1,
    val bevel: Bevel = Bevel.RAISED,
    val shadow: Int = 0,
    val glow: Int = 0,
    val focus: Int = 1,
    val control: Float = 1f,
    val scanlines: Boolean = false,
    val underlineTabs: Boolean = false
) {
    fun height(base: Int): Int = (base * control).roundToInt().coerceIn(base - 2, base + 2)

    companion object {
        fun parse(values: Map<String, String>): ShapeDef {
            fun int(key: String, default: Int, range: IntRange) = (values[key]?.let { requireNotNull(it.toIntOrNull()) { "shape.$key must be a number" } } ?: default)
                .also { require(it in range) { "shape.$key out of range $range" } }
            val control = values["control"]?.let { requireNotNull(it.toFloatOrNull()) { "shape.control must be a number" } } ?: 1f
            require(control in 0.9f..1.15f) { "shape.control out of range" }
            val bevel = values["bevel"]?.let { name -> requireNotNull(Bevel.entries.firstOrNull { it.name.equals(name, true) }) { "unknown bevel '$name'" } } ?: Bevel.RAISED
            return ShapeDef(
                int("radius", 0, 0..4), int("chamfer", 0, 0..4), int("border", 1, 1..3), bevel, int("shadow", 0, 0..2), int("glow", 0, 0..3), int("focus", 1, 1..3), control,
                values["scanlines"].toBoolean(), values["underline_tabs"].toBoolean()
            )
        }
    }
}

object Geometry {
    class Span(val x: Int, val y: Int, val w: Int, var h: Int, val border: Boolean)

    fun corner(radius: Int, chamfer: Int): IntArray =
        if (chamfer > 0) IntArray(chamfer) { chamfer - it }
        else IntArray(radius) { i ->
            val dy = radius - i - 0.5
            val step = (radius - sqrt(radius * radius - dy * dy)).roundToInt()
            if (i == 0) max(1, step) else step
        }

    fun fit(corner: IntArray, w: Int, h: Int): IntArray = corner.copyOf(min(corner.size, min(w, h) / 2))

    fun inset(corner: IntArray, h: Int, y: Int): Int {
        val edge = min(y, h - 1 - y)
        return if (edge < corner.size) corner[edge] else 0
    }

    fun spans(w: Int, h: Int, corner: IntArray, border: Int): List<Span> {
        val out = ArrayList<Span>()
        val open = HashMap<Long, Span>()
        fun add(x: Int, width: Int, y: Int, edge: Boolean) {
            if (width <= 0) return
            val key = (x.toLong() shl 33) or (width.toLong() shl 1) or (if (edge) 1L else 0L)
            val last = open[key]
            if (last != null && last.y + last.h == y) last.h++ else Span(x, y, width, 1, edge).also { out += it; open[key] = it }
        }
        for (y in 0 until h) {
            val i = inset(corner, h, y)
            if (y < border || y >= h - border) add(i, w - 2 * i, y, true)
            else {
                val inner = i + border
                add(i, border, y, true)
                add(w - inner, border, y, true)
                add(inner, w - 2 * inner, y, false)
            }
        }
        return out
    }
}
