package kami.claims

import kami.claims.service.Service
import kami.claims.service.Upkeep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProvinceTest {
    private fun country(name: String, treasury: Long = 0): Country {
        val c = Country(name, treasury = treasury)
        Realm.data.countries[c.id] = c
        Realm.join(c, "${name}_p", Rank.PRESIDENT)
        return c
    }

    private fun setup(): Pair<Country, Country> {
        Realm.reset(Data())
        return country("parentland") to country("childland")
    }

    @Test
    fun percentTaxTransfersFromIncomeToParentTreasury() {
        val (parent, child) = setup()
        Service.finalizeProvince(child, parent, TaxMode.PERCENT, 0.5)
        child.treasury = 100
        child.pending = 40
        Upkeep.process(1)
        assertEquals(80L, child.treasury)
        assertEquals(20L, parent.treasury)
        assertEquals(0, child.provinceDebt)
    }

    @Test
    fun flatTaxTransfersFixedAmount() {
        val (parent, child) = setup()
        Service.finalizeProvince(child, parent, TaxMode.FLAT, 15.0)
        child.treasury = 50
        Upkeep.process(1)
        assertEquals(35L, child.treasury)
        assertEquals(15L, parent.treasury)
    }

    @Test
    fun missedProvinceTaxAccumulatesDebtWithoutAutoRelease() {
        val (parent, child) = setup()
        Service.finalizeProvince(child, parent, TaxMode.FLAT, 15.0)
        child.treasury = 0
        repeat(5) { Upkeep.process((it + 1).toLong()) }
        assertEquals(5, child.provinceDebt)
        assertEquals(parent.id, child.parent)
        assertEquals(0L, parent.treasury)
    }

    @Test
    fun formingAProvinceFlattensExistingSubProvinces() {
        Realm.reset(Data())
        val grand = country("grand")
        val middle = country("middle")
        val leaf = country("leaf")
        Service.finalizeProvince(leaf, middle, TaxMode.FLAT, 5.0)
        assertEquals(middle.id, leaf.parent)
        assertTrue(middle.provinces.contains(leaf.id))

        Service.finalizeProvince(middle, grand, TaxMode.PERCENT, 0.1)
        assertEquals(grand.id, middle.parent)
        assertEquals(grand.id, leaf.parent)
        assertTrue(middle.provinces.isEmpty())
        assertTrue(grand.provinces.containsAll(listOf(middle.id, leaf.id)))
    }

    @Test
    fun disbandingAParentReleasesItsProvinces() {
        val (parent, child) = setup()
        Service.finalizeProvince(child, parent, TaxMode.FLAT, 5.0)
        Realm.disband(parent)
        assertNull(child.parent)
        assertEquals(0, child.provinceDebt)
    }

    @Test
    fun disbandingAProvinceRemovesItFromParent() {
        val (parent, child) = setup()
        Service.finalizeProvince(child, parent, TaxMode.FLAT, 5.0)
        Realm.disband(child)
        assertTrue(child.id !in parent.provinces)
    }

    @Test
    fun formingAProvinceAutoAlliesMembersAcrossTheFamily() {
        val (parent, child) = setup()
        Service.finalizeProvince(child, parent, TaxMode.FLAT, 5.0)
        assertEquals(Rank.ALLIED, parent.outsiders["childland_p"])
        assertEquals(Rank.ALLIED, child.outsiders["parentland_p"])
        assertTrue("childland_p" in parent.autoAllies)
    }

    @Test
    fun releasingAProvinceRevertsAutoAlliesToNoRole() {
        val (parent, child) = setup()
        Service.finalizeProvince(child, parent, TaxMode.FLAT, 5.0)
        child.parent = null
        parent.provinces.remove(child.id)
        Realm.syncFamily(child.id)
        Realm.syncFamily(parent.id)
        assertNull(parent.outsiders["childland_p"])
        assertNull(child.outsiders["parentland_p"])
        assertTrue(parent.autoAllies.isEmpty())
        assertTrue(child.autoAllies.isEmpty())
    }

    @Test
    fun explicitBanIsNotOverriddenByAutoAlly() {
        val (parent, child) = setup()
        parent.outsiders["childland_p"] = Rank.BANISHED
        Service.finalizeProvince(child, parent, TaxMode.FLAT, 5.0)
        assertEquals(Rank.BANISHED, parent.outsiders["childland_p"])
        assertTrue("childland_p" !in parent.autoAllies)
    }
}
