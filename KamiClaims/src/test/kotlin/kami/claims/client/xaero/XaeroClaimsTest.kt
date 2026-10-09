package kami.claims.client.xaero

import kami.claims.client.VisibilityState
import kami.claims.service.View
import kami.claims.war.Entry
import net.minecraft.network.chat.contents.TranslatableContents
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private fun e(x: Int, z: Int, country: Int = 0, flags: Int = View.OWN, type: Int = 0) = View.Entry(0, x, z, country, type, flags)

    private fun style(mode: String = "political", tint: Boolean = true) = ClaimStyle({ mode }, { tint }, { 0xAA0000 + it }, { it })

    @Test
    fun politicalUsesCountryColourAndEdgesOnlyAtBorders() {
        val data = Fake(listOf(e(0, 0), e(1, 0), e(0, 1, 1, 0)))
        val p = ClaimsChunkLayer(data, style()).paint("d", 0, 0)!!
        assertEquals(argb(0x112233, 0x58), p.fill)
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
    fun enemyTintAndToggle() {
        val data = Fake(listOf(e(0, 0, 1, View.ENEMY, -1)))
        assertEquals(argb(WAR_RED, 0x70), ClaimsChunkLayer(data, style(tint = true)).paint("d", 0, 0)!!.fill)
        assertEquals(argb(0x445566, 0x38), ClaimsChunkLayer(data, style(tint = false)).paint("d", 0, 0)!!.fill)
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

    private fun key(c: net.minecraft.network.chat.Component) = (c.contents as TranslatableContents).key

    @Test
    fun infoLineComposition() {
        val data = Fake(listOf(e(0, 0, 0, View.OWN or View.SAFE), e(1, 0, 1, View.ENEMY, -1)))
        val info = TerritoryInfo(data, style()) { _, _, _ -> true }
        assertEquals(listOf("kami_claims.xaero.territory", "kami_claims.xaero.build.yes", "kami_claims.xaero.war.safe"), info.lines("d", 3, 64, 3).map(::key))
        assertEquals(listOf("kami_claims.xaero.territory", "kami_claims.xaero.war.enemy"), info.lines("d", 20, 64, 3).map(::key))
        assertTrue(info.lines("d", 400, 64, 400).isEmpty())
        assertEquals("kami_claims.xaero.build.no", key(TerritoryInfo(data, style()) { _, _, _ -> false }.lines("d", 3, 64, 3)[1]))
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
    fun pointsFromPayload() {
        val data = Fake(listOf(e(0, 0, 0, View.OWN or View.CAPITAL), e(1, 0, 0, View.OWN or View.MINE), e(2, 0, 0, View.OWN or View.SAFE), e(3, 0, 1, View.ENEMY, -1), e(4, 0, 0, View.OWN)))
        val pts = ClaimPoints("claims.waypoints", "waypoints", data, { it }) {}.points("d").sortedBy { it.x }
        assertEquals(listOf("kami_claims.xaero.point.capital", "kami_claims.xaero.point.plot", "kami_claims.xaero.point.safe"), pts.map { it.name })
        assertEquals(8, pts[0].x)
        assertEquals(3, pts[0].actions.size)
        assertEquals(3, pts[1].actions.size)
        assertEquals(2, pts[2].actions.size)
        assertTrue(ClaimPoints("m", "markers", Fake(emptyList(), enabled = false), { it }) {}.points("d").isEmpty())
        assertNotNull(pts[0].id)
    }

    @Test
    fun trackedSkipsLoadedUnlessFairPlay() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val list = listOf(Entry(a, "d", 1, 2, 3), Entry(b, "d", 4, 5, 6))
        assertEquals(listOf(b), VisibilityState.select(list, setOf(a), false).map { it.id })
        assertEquals(listOf(a, b), VisibilityState.select(list, setOf(a), true).map { it.id })
        assertEquals(1.5, VisibilityState.select(list, emptySet(), false)[0].x)
    }
}
