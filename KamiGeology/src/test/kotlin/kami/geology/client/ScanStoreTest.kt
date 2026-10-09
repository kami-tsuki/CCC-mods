package kami.geology.client

import kami.geology.client.xaero.GlobalHeat
import kami.geology.client.xaero.Scans
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanStoreTest {
    private fun layer(w: Int, h: Int, vararg cells: Pair<Int, Float>) = FloatArray(w * h).also { a -> cells.forEach { (k, v) -> a[k] = v } }

    private fun Scans.scan(x0: Int, z0: Int, at: Long, layers: Map<String, FloatArray>, dim: String = "minecraft:overworld") =
        record(dim, mapOf("iron" to 0xAA0000, "gold" to 0xFFD700), x0, z0, 16, 16, 0, 64, at, layers)

    @Test
    fun roundTripKeepsScansAndLegend() {
        val scans = Scans()
        scans.scan(0, 0, 10, mapOf("iron" to layer(16, 16, 5 to 2f)))
        scans.scan(32, 0, 20, mapOf("gold" to layer(16, 16, 17 to 3f)))
        val copy = Scans().also { it.fromJson(scans.toJson()) }
        assertEquals(2, copy.scans.size)
        assertEquals(scans.legend, copy.legend)
        assertEquals(scans.scans.map { it.at }, copy.scans.map { it.at })
        assertEquals(scans.cell("minecraft:overworld", 0, 0)?.ore, copy.cell("minecraft:overworld", 0, 0)?.ore)
        assertEquals(scans.lastFind()?.second?.value, copy.lastFind()?.second?.value)
    }

    @Test
    fun capDropsOldest() {
        val scans = Scans(cap = 3)
        for (i in 0 until 5) scans.scan(i * 16, 0, i.toLong(), mapOf("iron" to layer(16, 16, 0 to 1f)))
        assertEquals(listOf(2L, 3L, 4L), scans.scans.map { it.at })
        assertNull(scans.cell("minecraft:overworld", 0, 0))
        assertNotNull(scans.cell("minecraft:overworld", 4, 0))
    }

    @Test
    fun sameRegionIsReplaced() {
        val scans = Scans()
        scans.scan(0, 0, 1, mapOf("iron" to layer(16, 16, 0 to 1f)))
        scans.scan(0, 0, 2, mapOf("gold" to layer(16, 16, 0 to 1f)))
        assertEquals(1, scans.scans.size)
        assertEquals("gold", scans.cell("minecraft:overworld", 0, 0)?.ore)
    }

    @Test
    fun chunkAggregationPicksDominantOre() {
        val scans = Scans()
        val iron = layer(16, 16, 0 to 1f)
        val gold = FloatArray(256) { 1f }
        scans.scan(0, 0, 1, mapOf("iron" to iron, "gold" to gold))
        val cell = scans.cell("minecraft:overworld", 0, 0)!!
        assertEquals("gold", cell.ore)
        assertTrue(cell.intensity > 0.9f)
        assertNull(scans.cell("minecraft:overworld", 1, 0))
        assertNull(scans.cell("minecraft:nether", 0, 0))
    }

    @Test
    fun emptyLayersAreNotStored() {
        val scans = Scans()
        scans.scan(0, 0, 1, mapOf("iron" to FloatArray(256)))
        assertTrue(scans.scans.single().ores.isEmpty())
        assertNull(scans.lastFind())
    }

    @Test
    fun lastFindIsStrongestCellOfNewestScan() {
        val scans = Scans()
        scans.scan(0, 0, 1, mapOf("iron" to layer(16, 16, 0 to 9f)))
        scans.scan(32, 48, 2, mapOf("iron" to layer(16, 16, 20 to 1f), "gold" to layer(16, 16, 35 to 6f)))
        val (scan, find) = scans.lastFind()!!
        assertEquals(2L, scan.at)
        assertEquals("gold", find.ore)
        assertEquals(32 + 35 % 16, find.x)
        assertEquals(48 + 35 / 16, find.z)
    }

    @Test
    fun globalHeatOwnsOnlyItsSeqRange() {
        assertTrue(GlobalHeat.owns(GlobalHeat.BASE))
        assertTrue(GlobalHeat.owns(GlobalHeat.BASE + 123))
        assertFalse(GlobalHeat.owns(0))
        assertFalse(GlobalHeat.owns(GlobalHeat.BASE - 1))
    }

    @Test
    fun globalHeatRadiusHalvesOnEmptyUntilFloor() {
        assertEquals(64, GlobalHeat.nextRadius(128, true))
        assertEquals(128, GlobalHeat.nextRadius(128, false))
        assertEquals(GlobalHeat.MIN_RADIUS, GlobalHeat.nextRadius(GlobalHeat.MIN_RADIUS, true))
        assertTrue(GlobalHeat.moved(0, 0, 65, 0))
        assertFalse(GlobalHeat.moved(0, 0, 64, 64))
    }
}
