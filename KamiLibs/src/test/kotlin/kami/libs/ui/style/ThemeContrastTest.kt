package kami.libs.ui.style

import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeContrastTest {
    private val legacy = "minecraft"
    private val surfaces = listOf("bg.canvas", "bg.surface", "bg.raised", "bg.field")
    private val statuses = listOf("sem.info", "sem.success", "sem.warning", "sem.danger", "sem.money", "sem.focus")

    private fun defs() = Theme.families().flatMap { listOfNotNull(it.light, it.dark) }.map { ThemeLoader.resolve(it, Theme::lookup) }

    private fun channel(c: Int): Double {
        val x = c / 255.0
        return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }

    private fun lum(c: Int) = 0.2126 * channel((c shr 16) and 0xFF) + 0.7152 * channel((c shr 8) and 0xFF) + 0.0722 * channel(c and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val x = lum(a)
        val y = lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    private fun Resolved.ratio(a: String, b: String) = contrast(values.getValue(a), values.getValue(b))

    private fun Resolved.check(id: String, a: String, b: String, min: Double) =
        assertTrue(ratio(a, b) >= min, "${def.id}: $a on $b is ${"%.2f".format(ratio(a, b))}, needs $min ($id)")

    @Test
    fun textContrast() {
        defs().forEach { theme ->
            val old = theme.def.id == legacy
            surfaces.forEach { surface ->
                theme.check("primary", "text.primary", surface, 4.5)
                theme.check("secondary", "text.secondary", surface, if (old && surface != "bg.field" && surface != "bg.raised") 4.0 else 4.5)
                theme.check("muted", "text.muted", surface, if (old) 2.9 else 3.0)
            }
            theme.check("inverse", "text.inverse", "border.brass", 4.5)
            theme.check("inverse", "text.inverse", "sem.danger", 4.5)
            theme.check("focus", "sem.focus", "bg.canvas", 3.0)
            theme.check("tooltip", "tip.text", "tip.bg", 7.0)
            statuses.forEach { theme.check("status", it, "bg.surface", if (old) 2.9 else 3.0) }
        }
    }

    @Test
    fun categoricalColoursReadOnEverySurface() {
        defs().forEach { theme ->
            Tokens.categorical.forEach { token ->
                surfaces.forEach { theme.check("categorical", token, it, if (theme.def.family == "high_contrast") 4.5 else 3.0) }
            }
        }
    }

    @Test
    fun highContrastMeetsEnhancedRatios() {
        defs().filter { it.def.family == "high_contrast" }.also { assertTrue(it.size == 2) }.forEach { theme ->
            surfaces.forEach { surface ->
                theme.check("primary", "text.primary", surface, 7.0)
                theme.check("secondary", "text.secondary", surface, 7.0)
                theme.check("muted", "text.muted", surface, 4.5)
            }
            theme.check("inverse", "text.inverse", "border.brass", 7.0)
        }
    }

    private fun linear(c: Int, shift: Int) = channel((c shr shift) and 0xFF)

    private fun encode(v: Double): Double {
        val x = v.coerceIn(0.0, 1.0)
        return if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
    }

    private val matrices = mapOf(
        Palette.Vision.PROTANOPIA to arrayOf(doubleArrayOf(0.152286, 1.052583, -0.204868), doubleArrayOf(0.114503, 0.786281, 0.099216), doubleArrayOf(-0.003882, -0.048116, 1.051998)),
        Palette.Vision.DEUTERANOPIA to arrayOf(doubleArrayOf(0.367322, 0.860646, -0.227968), doubleArrayOf(0.280085, 0.672501, 0.047413), doubleArrayOf(-0.011820, 0.042940, 0.968881)),
        Palette.Vision.TRITANOPIA to arrayOf(doubleArrayOf(1.255528, -0.076749, -0.178779), doubleArrayOf(-0.078411, 0.930809, 0.147602), doubleArrayOf(0.004733, 0.691367, 0.303900))
    )

    private fun lab(r: Double, g: Double, b: Double): DoubleArray {
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
        val lr = channel(Math.round(r * 255).toInt())
        val lg = channel(Math.round(g * 255).toInt())
        val lb = channel(Math.round(b * 255).toInt())
        val x = f((0.4124 * lr + 0.3576 * lg + 0.1805 * lb) / 0.95047)
        val y = f(0.2126 * lr + 0.7152 * lg + 0.0722 * lb)
        val z = f((0.0193 * lr + 0.1192 * lg + 0.9505 * lb) / 1.08883)
        return doubleArrayOf(116 * y - 16, 500 * (x - y), 200 * (y - z))
    }

    private fun seen(c: Int, m: Array<DoubleArray>): DoubleArray {
        val v = doubleArrayOf(linear(c, 16), linear(c, 8), linear(c, 0))
        val out = m.map { row -> encode(row[0] * v[0] + row[1] * v[1] + row[2] * v[2]) }
        return lab(out[0], out[1], out[2])
    }

    private fun delta(a: DoubleArray, b: DoubleArray) = sqrt((0..2).sumOf { (a[it] - b[it]).pow(2) })

    @Test
    fun visionStatusColoursStayDistinguishable() {
        val minimum = 25.0
        matrices.forEach { (vision, matrix) ->
            listOf(Variant.LIGHT, Variant.DARK).forEach { variant ->
                val colours = vision.of(variant).values.toList()
                assertTrue(colours.size >= 3)
                colours.indices.forEach { i ->
                    (i + 1 until colours.size).forEach { j ->
                        val d = delta(seen(colours[i], matrix), seen(colours[j], matrix))
                        assertTrue(d >= minimum, "$vision $variant: colours $i/$j differ by ${"%.1f".format(d)}, needs $minimum")
                    }
                }
            }
        }
    }
}
