package kami.claims.client.compat.xaero

import kami.claims.client.VisibilityState
import kami.claims.service.View
import kami.claims.war.Entry
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XaeroClaimsTest {
    private class Fake(
        entries: List<View.Entry>,
        val countries: List<View.CountryView> = listOf(View.CountryView("Aria", 0x112233, 1), View.CountryView("Borea", 0x445566, 0)),
        val reservedAt: Set<Long> = emptySet(),
        override var enabled: Boolean = true
    ) : ClaimData {
        val map = entries.associateBy { chunkKey(it.x, it.z) }
        override val rev = 1
        override fun at(dim: String, x: Int, z: Int) = map[chunkKey(x, z)]
        override fun view(dim: String): Map<Long, View.Entry>? = map
        override fun country(e: View.Entry) = countries.getOrNull(e.country)
        override fun typeName(e: View.Entry) = if (e.type >= 0) "farm" else null
        override fun reserved(dim: String, x: Int, z: Int) = chunkKey(x, z) in reservedAt
        override fun regionHash(dim: String, rx: Int, rz: Int) = 7
    }

    private fun e(x: Int, z: Int, country: Int = 0, flags: Int = View.OWN, type: Int = 0) = View.Entry(0, x, z, country, type, flags or View.SCOPE)

    private fun style(mode: String = "political") = ClaimStyle({ mode }, { 0xAA0000 + it }, { it })

    @Test
    fun politicalUsesCountryColourAndEdgesOnlyAtBorders() {
        val data = Fake(listOf(e(0, 0), e(1, 0), e(0, 1, 1, 0)))
        val p = ClaimsChunkLayer(data, style()).paint("d", 0, 0)!!
        assertEquals(argb(0x112233, 0x70), p.fill)
        assertEquals(p.fill, p.east)
        assertEquals(argb(0x112233, 0xD0), p.north)
        assertEquals(argb(0x112233, 0xD0), p.south)
    }

    @Test
    fun relationsModeUsesRelationColour() {
        val data = Fake(listOf(e(0, 0, 0, View.TAKEN)))
        val p = ClaimsChunkLayer(data, style("relations")).paint("d", 0, 0)!!
        assertEquals(argb(0xAA0001, 0x28), p.fill)
    }

    @Test
    fun foreignPlotsUseNeutralEdgesAndNoCountryName() {
        val rented = View.Entry(0, 4, 0, 1, -1, View.MINE)
        val layer = ClaimsChunkLayer(Fake(listOf(rented)), style())
        val p = layer.paint("d", 4, 0)!!
        assertEquals(argb(GOLD, 0x90), p.fill)
        assertEquals(argb(0xFFFFFF, 0xB0), p.north)
    }

    @Test
    fun onlyScopeAlliancesAndOwnPlotsAreDrawn() {
        val enemy = View.Entry(0, 0, 0, 1, -1, View.ENEMY)
        val stranger = View.Entry(0, 1, 0, 1, -1, 0)
        val ally = View.Entry(0, 2, 0, 1, -1, View.ALLIANCE)
        val family = View.Entry(0, 3, 0, 1, -1, View.ALLY)
        val rented = View.Entry(0, 4, 0, 1, -1, View.MINE)
        val layer = ClaimsChunkLayer(Fake(listOf(enemy, stranger, ally, family, rented)), style())
        assertNull(layer.paint("d", 0, 0))
        assertNull(layer.paint("d", 1, 0))
        assertNull(layer.paint("d", 3, 0))
        assertTrue(layer.paint("d", 4, 0) != null)
        assertTrue(layer.paint("d", 2, 0) != null)
    }

    @Test
    fun marksMineCapitalAndReserved() {
        val data = Fake(listOf(e(0, 0, 0, View.OWN or View.MINE or View.CAPITAL)), reservedAt = setOf(chunkKey(5, 5), chunkKey(5, 6)))
        val layer = ClaimsChunkLayer(data, style())
        val p = layer.paint("d", 0, 0)!!
        assertEquals(argb(GOLD, 0x90), p.fill)
        assertEquals(argb(0xFFFFFF, 0xFF), p.north)
        val a = layer.paint("d", 5, 5)!!
        val b = layer.paint("d", 5, 6)!!
        assertTrue(a.fill != b.fill)
        assertNull(layer.paint("d", 9, 9))
    }

    @Test
    fun disabledPaintsNothing() {
        val data = Fake(listOf(e(0, 0)), enabled = false)
        assertNull(ClaimsChunkLayer(data, style()).paint("d", 0, 0))
        assertEquals(0, ClaimsChunkLayer(data, style()).regionHash("d", 0, 0))
    }

    @Test
    fun regionHashChangesWithMode() {
        val data = Fake(listOf(e(0, 0)))
        assertTrue(ClaimsChunkLayer(data, style("political")).regionHash("d", 0, 0) != ClaimsChunkLayer(data, style("relations")).regionHash("d", 0, 0))
    }

    @Test
    fun warLayerPaintsSafeStakedAndEnemy() {
        val data = Fake(listOf(e(0, 0, 0, View.OWN or View.SAFE), e(1, 0, 0, View.OWN or View.STAKED), e(2, 0, 1, View.ENEMY, -1), e(3, 0)))
        val layer = WarChunkLayer(data)
        assertEquals(argb(SAFE_GREEN, 0xFF), layer.paint("d", 0, 0)!!.north)
        assertEquals(argb(WAR_ORANGE, 0x70), layer.paint("d", 1, 0)!!.fill)
        assertEquals(argb(WAR_RED, 0xFF), layer.paint("d", 2, 0)!!.south)
        assertNull(layer.paint("d", 3, 0))
    }

    @Test
    fun bordersMergeCollinearRuns() {
        val entries = listOf(e(0, 0), e(1, 0), e(2, 0), e(4, 0))
        val lines = BorderRuns.lines(entries, { x, z -> entries.firstOrNull { it.x == x && it.z == z } }) { 0x123456 }
        val north = lines.filter { it.z1 == 0 && it.z2 == 0 }.map { it.x1 to it.x2 }.sortedBy { it.first }
        assertEquals(listOf(0 to 48, 64 to 80), north)
        val west = lines.filter { it.x1 == 0 && it.x2 == 0 }
        assertEquals(1, west.size)
        assertEquals(0 to 16, west[0].z1 to west[0].z2)
        assertEquals(argb(0x123456, 0xFF), west[0].color)
    }

    @Test
    fun sharedBorderBetweenCountriesDrawnOnce() {
        val entries = listOf(e(0, 0, 0), e(1, 0, 1))
        val lines = BorderRuns.lines(entries, { x, z -> entries.firstOrNull { it.x == x && it.z == z } }) { 1 }
        assertEquals(1, lines.count { it.x1 == 16 && it.x2 == 16 })
    }

    @Test
    fun centroidLabelsPerCountry() {
        val entries = listOf(e(0, 0, 0), e(2, 0, 0), e(10, 10, 1))
        val labels = BorderRuns.labels(entries, { listOf("A", "B")[it] }) { 0xFFFFFF }.associateBy { it.text }
        assertEquals(1 * 16 + 8, labels["A"]!!.x)
        assertEquals(8, labels["A"]!!.z)
        assertEquals(168, labels["B"]!!.x)
    }

    @Test
    fun trackedSkipsLoadedUnlessFairPlay() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val list = listOf(Entry(a, "d", 1, 2, 3), Entry(b, "d", 4, 5, 6))
        assertEquals(listOf(b), VisibilityState.select(list, { it == a }, false).map { it.id })
        assertEquals(listOf(a, b), VisibilityState.select(list, { it == a }, true).map { it.id })
        assertEquals(1.5, VisibilityState.select(list, { false }, false)[0].x)
    }
}
