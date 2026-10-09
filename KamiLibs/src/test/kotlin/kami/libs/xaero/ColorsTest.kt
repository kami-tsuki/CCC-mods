package kami.libs.xaero

import kami.libs.xaero.impl.Colors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ColorsTest {
    @Test
    fun argbPacksToRgba() {
        assertEquals(0x11223344, Colors.toRgba(0x44112233))
        assertEquals(0xFF0000FF.toInt(), Colors.toRgba(0xFFFF0000.toInt()))
    }

    @Test
    fun blendKeepsOpaqueAndTransparent() {
        assertEquals(0xFF112233.toInt(), Colors.over(0x80445566.toInt(), 0xFF112233.toInt()))
        assertEquals(0x80445566.toInt(), Colors.over(0x80445566.toInt(), 0))
        assertEquals(0x80112233.toInt(), Colors.over(0, 0x80112233.toInt()))
    }

    @Test
    fun blendMixesHalfAlpha() {
        val out = Colors.over(0xFF000000.toInt(), 0x80FFFFFF.toInt())
        assertEquals(255, out ushr 24)
        assertEquals(0x80, out shr 16 and 255)
    }

    @Test
    fun regionHashCombine() {
        assertEquals(0, Colors.combine(intArrayOf(0, 0)))
        assertNotEquals(0, Colors.combine(intArrayOf(0, 5)))
        assertNotEquals(Colors.combine(intArrayOf(5, 0)), Colors.combine(intArrayOf(0, 5)))
        assertNotEquals(Colors.combine(intArrayOf(5, 6)), Colors.combine(intArrayOf(5, 7)))
    }

    @Test
    fun nearestChatColor() {
        assertEquals(15, Colors.nearestChatColor(0xFFFFFF))
        assertEquals(4, Colors.nearestChatColor(0xB00000))
        assertEquals(12, Colors.nearestChatColor(0xFF6060))
        assertEquals(0, Colors.nearestChatColor(0x010101))
    }

    @Test
    fun waypointDiff() {
        val d = Colors.diff(mapOf("a" to 1, "b" to 2, "c" to 3), mapOf("b" to 2, "c" to 9, "d" to 4))
        assertEquals(setOf("a"), d.removed)
        assertEquals(setOf("c", "d"), d.upserted)
    }
}
