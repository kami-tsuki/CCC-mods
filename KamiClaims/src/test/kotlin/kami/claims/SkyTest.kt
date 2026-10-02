package kami.claims

import kami.claims.world.Cover
import kami.claims.world.SkyColumn
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkyTest {
    private fun column(vararg at: Pair<Int, Cover>): (Int) -> Cover = { y -> at.toMap()[y] ?: Cover.CLEAR }

    @Test
    fun openSky() = assertTrue(SkyColumn.clear(65, 66, column()))

    @Test
    fun glassRoofIsClear() = assertTrue(SkyColumn.clear(65, 70, column(68 to Cover.CLEAR, 69 to Cover.CLEAR)))

    @Test
    fun stoneOrLeavesBlock() {
        assertFalse(SkyColumn.clear(65, 70, column(69 to Cover.SOLID)))
        assertFalse(SkyColumn.clear(65, 70, column(65 to Cover.SOLID)))
    }

    @Test
    fun ownTallPartsAndWaterDoNotBlock() = assertTrue(SkyColumn.clear(65, 72, column(65 to Cover.SELF, 66 to Cover.SELF, 67 to Cover.CLEAR, 68 to Cover.CLEAR)))
}
