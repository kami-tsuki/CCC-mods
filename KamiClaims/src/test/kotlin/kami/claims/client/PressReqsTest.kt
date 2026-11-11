package kami.claims.client

import kami.claims.client.app.pages.PressReqs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PressReqsTest {
    @Test
    fun hidesZeroNeedAndFee() {
        val cells = PressReqs.cells(mapOf("minecraft:paper" to 3, "minecraft:ink_sac" to 0), mapOf("minecraft:paper" to 5), 0, 10, 1, 5)
        assertEquals(listOf(PressReqs.Kind.ITEM, PressReqs.Kind.CAP), cells.map { it.kind })
        assertEquals(5L, cells[0].have)
    }

    @Test
    fun okFlagsPerKind() {
        val cells = PressReqs.cells(mapOf("a" to 4), mapOf("a" to 3), 50, 50, 5, 5)
        assertFalse(cells[0].ok)
        assertTrue(cells[1].ok)
        assertFalse(cells[2].ok)
    }

    @Test
    fun wrapsOnlyWhenNeeded() {
        val one = PressReqs.wrap(listOf(30, 30, 30), 200)
        assertEquals(listOf(0, 0, 0), one.map { it.row })
        assertEquals(PressReqs.CELL_H, PressReqs.height(one))
        val two = PressReqs.wrap(listOf(30, 30, 30), 70)
        assertEquals(listOf(0, 0, 1), two.map { it.row })
        assertEquals(0, two[2].x)
        assertEquals(0, PressReqs.height(emptyList()))
    }
}
