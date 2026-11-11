package kami.libs.ui.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiScaleTest {
    @Test
    fun choicesKeepWholeDevicePixels() {
        assertEquals(listOf(1f, 1.5f), UiScale.choices(2))
        assertEquals(listOf(1f), UiScale.choices(1))
        assertEquals(listOf(3 / 3f, 4 / 3f), UiScale.choices(3))
        assertEquals(listOf(0.75f, 1f, 1.25f, 1.5f), UiScale.choices(4))
        for (g in 1..8) {
            val list = UiScale.choices(g)
            assertTrue(1f in list)
            list.forEach { f ->
                assertTrue(f in 0.75f..1.5f)
                val px = g * f
                assertEquals(Math.round(px).toFloat(), px, 0.001f)
            }
        }
    }

    @Test
    fun nearestPicksClosestChoice() {
        assertEquals(1f, UiScale.nearest(0.9f, 2))
        assertEquals(1.5f, UiScale.nearest(1.4f, 2))
        assertEquals(0.75f, UiScale.nearest(0.8f, 4))
        assertEquals(1.25f, UiScale.nearest(1.2f, 4))
        assertEquals(1f, UiScale.nearest(1.5f, 1))
    }

    @Test
    fun unscaleAndLayoutRound() {
        assertEquals(37, UiScale.unscale(37, 1f))
        assertEquals(66, UiScale.unscale(100, 1.5f))
        assertEquals(66, UiScale.unscale(99, 1.5f))
        assertEquals(400, UiScale.layout(400, 1f))
        assertEquals(267, UiScale.layout(400, 1.5f))
        assertEquals(534, UiScale.layout(400, 0.75f))
    }
}
