package kami.libs.ui.style

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShapeTest {
    @Test
    fun steppedCorners() {
        assertContentEquals(intArrayOf(), Geometry.corner(0, 0))
        assertContentEquals(intArrayOf(1), Geometry.corner(1, 0))
        assertContentEquals(intArrayOf(1, 0), Geometry.corner(2, 0))
        assertContentEquals(intArrayOf(2, 1, 0, 0), Geometry.corner(4, 0))
    }

    @Test
    fun chamferWinsOverRadius() {
        assertContentEquals(intArrayOf(3, 2, 1), Geometry.corner(2, 3))
    }

    @Test
    fun cornersNeverExceedHalfTheSmallSide() {
        assertEquals(2, Geometry.fit(Geometry.corner(0, 4), 40, 5).size)
        assertEquals(0, Geometry.fit(Geometry.corner(0, 4), 40, 1).size)
    }

    @Test
    fun insetAppliesToTopAndBottomRowsOnly() {
        val c = intArrayOf(3, 2, 1)
        assertEquals(listOf(3, 2, 1, 0, 0, 1, 2, 3), (0 until 8).map { Geometry.inset(c, 8, it) })
    }

    @Test
    fun squareShapeIsFiveRectangles() {
        val spans = Geometry.spans(10, 8, intArrayOf(), 1)
        assertEquals(5, spans.size)
        assertEquals(80, spans.sumOf { it.w * it.h })
        assertEquals(48, spans.filter { !it.border }.sumOf { it.w * it.h })
    }

    @Test
    fun spansTileTheShapeWithoutOverlap() {
        val w = 12
        val h = 9
        val c = Geometry.corner(0, 3)
        val grid = Array(h) { IntArray(w) }
        Geometry.spans(w, h, c, 2).forEach { s -> for (y in s.y until s.y + s.h) for (x in s.x until s.x + s.w) grid[y][x]++ }
        grid.forEachIndexed { y, row -> row.forEachIndexed { x, n ->
            val inside = x >= Geometry.inset(c, h, y) && x < w - Geometry.inset(c, h, y)
            assertEquals(if (inside) 1 else 0, n, "cell $x,$y")
        } }
    }

    @Test
    fun noBorderLeavesOnlyFill() {
        assertTrue(Geometry.spans(8, 6, Geometry.corner(2, 0), 0).none { it.border })
    }

    @Test
    fun shapeParsingAndLimits() {
        val shape = ShapeDef.parse(mapOf("radius" to "3", "bevel" to "Glass", "control" to "1.06", "scanlines" to "true"))
        assertEquals(3, shape.radius)
        assertEquals(Bevel.GLASS, shape.bevel)
        assertTrue(shape.scanlines)
        assertEquals(19, shape.height(18))
        assertEquals(ShapeDef(), ShapeDef.parse(emptyMap()))
        assertFailsWith<IllegalArgumentException> { ShapeDef.parse(mapOf("radius" to "9")) }
        assertFailsWith<IllegalArgumentException> { ShapeDef.parse(mapOf("bevel" to "wavy")) }
    }

    @Test
    fun controlHeightStaysWithinTwoPixels() {
        assertEquals(20, ShapeDef(control = 1.15f).height(18))
        assertEquals(16, ShapeDef(control = 0.9f).height(18))
        assertEquals(18, ShapeDef().height(18))
    }

    @Test
    fun soundSpecs() {
        assertEquals(SoundSpec("minecraft:ui.button.click", 1.2f, 0.3f), SoundSpec.parse("minecraft:ui.button.click 1.2 0.3"))
        assertNull(SoundSpec.parse("ui.button.click 1 0.3"))
        assertNull(SoundSpec.parse("minecraft:x 3 0.3"))
        assertNull(SoundSpec.parse("minecraft:x 1"))
    }

    @Test
    fun everyBuiltInSoundAndShapeResolves() {
        Theme.families().flatMap { listOfNotNull(it.light, it.dark) }.forEach {
            val resolved = ThemeLoader.resolve(it, Theme::lookup)
            resolved.sound.values.forEach { spec -> assertNotNull(spec, it.id) }
        }
    }
}
