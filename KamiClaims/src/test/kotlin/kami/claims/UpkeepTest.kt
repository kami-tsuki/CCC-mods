package kami.claims

import kami.claims.service.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpkeepTest {
    private val dim = "minecraft:overworld"

    private fun setup(paid: Int, treasury: Long): Country {
        Realm.reset(Data())
        val c = Country("test", treasury = treasury)
        Realm.data.countries[c.id] = c
        Realm.join(c, "p1", Rank.PRESIDENT)
        (0 until Config.s.freeChunks + paid).forEach { i ->
            Realm.add(Claim(c.id, dim, i, 0, "civic", at = i.toLong(), since = 0, capital = i == 0))
        }
        Realm.refreshFree(c)
        return c
    }

    @Test
    fun partialPaymentIndebtsNewestChunks() {
        val c = setup(65, 60)
        Upkeep.process(1)
        val claims = Realm.claims(c.id).sortedBy { it.at }
        assertEquals(9, claims.count { it.free })
        assertEquals(0L, c.treasury)
        assertEquals((69..73).toList(), claims.filter { it.debt > 0 }.map { it.x })
    }

    @Test
    fun chunksAtMaxDebtBecomeReservedNomansland() {
        val c = setup(65, 60)
        (1L..3L).forEach { Upkeep.process(it) }
        assertEquals(69, Realm.claims(c.id).size)
        assertNull(Realm.at(dim, 73, 0))
        assertNotNull(Realm.at(dim, 68, 0))
        assertEquals(c.id, Realm.reservedFor(dim, 73, 0))
        assertTrue(Realm.claims(c.id).any { it.capital })
    }

    @Test
    fun payingClearsDebt() {
        val c = setup(10, 5)
        Upkeep.process(1)
        assertEquals(5, Realm.claims(c.id).count { it.debt > 0 })
        c.treasury = 100
        Upkeep.process(2)
        assertEquals(0, Realm.claims(c.id).count { it.debt > 0 })
        assertEquals(85L, c.treasury)
    }

    @Test
    fun wildernessBillsEverySevenDays() {
        val c = setup(0, 10)
        Realm.add(Claim(c.id, dim, 9, 0, "wilderness", at = 99, since = 0))
        Realm.refreshFree(c)
        (1L..6L).forEach { Upkeep.process(it) }
        assertEquals(10L, c.treasury)
        Upkeep.process(7)
        assertEquals(9L, c.treasury)
    }

    @Test
    fun unclaimNeverSplitsTerritory() {
        setup(3, 0)
        assertTrue(!Realm.removable(Realm.at(dim, 10, 0)!!))
        assertTrue(Realm.removable(Realm.at(dim, 11, 0)!!))
    }

    @Test
    fun claimsMustConnectAndRespectReservations() {
        val c = setup(0, 0)
        assertTrue(Service.claimError(c, Key(dim, 50, 0), "civic")!!.contains("connect"))
        Realm.data.reserves += Reserve(dim, 9, 1, "other", now() + 60_000)
        assertTrue(Service.claimError(c, Key(dim, 9, 1), "civic")!!.contains("reserved"))
        assertTrue(Service.claimError(c, Key(dim, 9, 0), "civic")!!.contains("Treasury"))
        c.treasury = 1
        assertNull(Service.claimError(c, Key(dim, 9, 0), "civic"))
    }

    @Test
    fun summaryReportsRunway() {
        val c = setup(10, 40)
        val sum = Upkeep.summary(c)
        assertEquals(10L, sum.upkeep)
        assertEquals("4 days", sum.runway(c.treasury))
        assertEquals("stable", Upkeep.Summary(1, 1, 0).runway(5))
    }

    @Test
    fun resetDropsOrphanClaims() {
        val c = setup(2, 0)
        val data = Realm.data
        data.countries.remove(c.id)
        Realm.reset(data)
        assertTrue(Realm.data.claims.isEmpty())
        assertTrue(Realm.index.isEmpty())
    }
}
