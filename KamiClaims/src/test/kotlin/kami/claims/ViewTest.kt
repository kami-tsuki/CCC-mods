package kami.claims

import kami.claims.service.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ViewTest {
    private val dim = "minecraft:overworld"

    private fun setup(): Country {
        Realm.reset(Data())
        val c = Country("Atlas")
        Realm.data.countries[c.id] = c
        Realm.join(c, "president", Rank.PRESIDENT)
        Realm.join(c, "citizen", Rank.CITIZEN)
        c.outsiders["ally"] = Rank.ALLIED
        c.outsiders["enemy"] = Rank.BANISHED
        Realm.add(Claim(c.id, dim, 0, 0, "civic", capital = true))
        Realm.add(Claim(c.id, dim, 1, 0, "residential"))
        Realm.add(Claim(c.id, dim, 2, 0, "residential", owner = "citizen"))
        Realm.add(Claim(c.id, dim, 3, 0, "mining", debt = 2))
        return c
    }

    private fun entry(viewer: String, x: Int) = View.build(viewer).entries.first { it.x == x }

    @Test
    fun strangersOnlySeeTheCountry() {
        setup()
        listOf("nobody", "enemy").forEach { viewer ->
            View.build(viewer).entries.forEach {
                assertEquals(0, it.flags)
                assertEquals(-1, it.type)
            }
        }
        assertEquals("Atlas", View.build("nobody").countries.single().name)
    }

    @Test
    fun alliesSeeTypesButNoPlotDetails() {
        setup()
        val e = entry("ally", 1)
        assertNotEquals(-1, e.type)
        assertEquals(View.ALLY, e.flags)
        assertEquals(View.ALLY or View.CAPITAL, entry("ally", 0).flags)
    }

    @Test
    fun citizensSeeClaimableAndOwnPlots() {
        setup()
        assertTrue(entry("citizen", 1).flags and View.CLAIMABLE != 0)
        assertTrue(entry("citizen", 2).flags and View.MINE != 0)
        assertTrue(entry("president", 2).flags and View.TAKEN != 0)
        assertEquals(0, entry("citizen", 3).flags and View.DEBT)
    }

    @Test
    fun onlyPrivilegedRanksSeeDebt() {
        setup()
        assertTrue(entry("president", 3).flags and View.DEBT != 0)
        assertEquals(0, entry("citizen", 3).flags and View.DEBT)
    }

    @Test
    fun rectsAreBoundedAndCapsAreConfigurable() {
        assertEquals(Rank.CHANCELLOR, Config.s.min(Cap.CLAIM))
        assertEquals(Cap.values().size, Config.s.caps.size)
    }
}
