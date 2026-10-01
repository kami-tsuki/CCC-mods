package kami.claims

import kami.claims.economy.Treasury
import kami.claims.research.Capacity
import kami.claims.research.LevelsConfig
import kami.claims.research.Research
import kami.claims.research.ResearchDefs
import kami.claims.research.ResearchSettings
import kami.claims.service.Alerts
import kami.claims.service.Fail
import kami.claims.service.Provinces
import kami.claims.service.Upkeep
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgreementTest {
    private fun capacity(provinces: Int) {
        Research.defs = ResearchDefs(ResearchSettings(), LevelsConfig(capacities = mapOf(Capacity.PROVINCES to provinces)), emptyMap(), emptyMap())
    }

    @BeforeTest
    fun roomForProvinces() = capacity(5)

    @AfterTest
    fun restoreDefs() { Research.defs = ResearchDefs.EMPTY }

    @Test
    fun provinceCapacityBlocksGrowthButNotExistingProvinces() {
        val (parent, child) = pair()
        Provinces.finalize(child, parent, TaxMode.FLAT, 5.0)
        capacity(1)
        assertFailsWith<Fail> { Provinces.invite(parent, country("third"), TaxMode.FLAT, 5.0) }
        assertEquals(parent.id, child.parent)
        capacity(2)
        Provinces.invite(parent, country("third"), TaxMode.FLAT, 5.0)
    }

    private fun country(name: String, treasury: Long = 0): Country {
        val c = Country(name, treasury = treasury)
        Realm.data.countries[c.id] = c
        Realm.join(c, "${name}_p", Rank.PRESIDENT)
        return c
    }

    private fun pair(): Pair<Country, Country> {
        Realm.reset(Data())
        return country("overland") to country("underland")
    }

    @Test
    fun approvingARequestOnlySendsTermsUntilSigned() {
        val (parent, child) = pair()
        Provinces.request(child, parent)
        Provinces.approve(parent, child, TaxMode.PERCENT, 0.2)
        assertNull(child.parent)
        assertTrue(child.provinceInvites.getValue(parent.id).answered)
        Provinces.accept(child, parent)
        assertEquals(parent.id, child.parent)
        assertEquals(0.2, child.taxAmount)
    }

    @Test
    fun declinedIndependenceStartsCooldown() {
        val (parent, child) = pair()
        Provinces.finalize(child, parent, TaxMode.FLAT, 5.0)
        Provinces.askIndependence(child)
        Provinces.decline(parent, child)
        assertTrue(Provinces.cooldown(child) > 0)
        assertFailsWith<Fail> { Provinces.askIndependence(child) }
    }

    @Test
    fun onlyTheOverlordCanRelease() {
        val (parent, child) = pair()
        val stranger = country("strangeland")
        Provinces.finalize(child, parent, TaxMode.FLAT, 5.0)
        assertFailsWith<Fail> { Provinces.release(stranger, child) }
        Provinces.release(parent, child)
        assertNull(child.parent)
    }

    @Test
    fun givingAwayRejectsProvincesAsOverlords() {
        val (parent, child) = pair()
        val other = country("otherland")
        val sub = country("subland")
        Provinces.finalize(child, parent, TaxMode.FLAT, 5.0)
        Provinces.finalize(sub, other, TaxMode.FLAT, 5.0)
        assertFailsWith<Fail> { Provinces.give(parent, child, sub) }
        Provinces.give(parent, child, other)
        assertEquals(other.id, child.parent)
    }

    @Test
    fun ledgerRecordsMovementsAndDailyStats() {
        val (parent, child) = pair()
        Provinces.finalize(child, parent, TaxMode.FLAT, 7.0)
        Treasury.move(child, LedgerKind.DEPOSIT, 50, "someone")
        Upkeep.process(1)
        assertEquals(LedgerKind.TRIBUTE_OUT, child.ledger.last().kind)
        assertEquals(-7L, child.ledger.last().amount)
        assertEquals(LedgerKind.TRIBUTE_IN, parent.ledger.last().kind)
        assertEquals(43L, child.history.last().treasury)
        assertEquals(50L, child.history.last().deposits)
        assertEquals(7L, child.history.last().tributeOut)
    }

    @Test
    fun alertsAskForAnswersToIndependence() {
        val (parent, child) = pair()
        Provinces.finalize(child, parent, TaxMode.FLAT, 5.0)
        Provinces.askIndependence(child)
        assertTrue(child.independenceRequested)
    }
}
