package kami.claims

import kami.claims.service.Diplomacy
import kami.claims.service.Fail
import kami.claims.service.Provinces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiplomacyTest {
    private fun country(name: String): Country {
        val c = Country(name, level = 100)
        Realm.data.countries[c.id] = c
        Realm.join(c, "${name}_p", Rank.PRESIDENT)
        return c
    }

    @Test
    fun allianceGivesAndRemovesAllyRankWithoutTouchingManualRanks() {
        Realm.reset(Data())
        val a = country("aurelia")
        val b = country("verdania")
        Realm.join(b, "verdania_q", Rank.CITIZEN)
        a.outsiders["verdania_q"] = Rank.BANISHED
        Diplomacy.propose(a, b)
        Diplomacy.accept(b, a)
        assertEquals("allied", Diplomacy.relation(a, b))
        assertEquals(Rank.ALLIED, a.rank("verdania_p"))
        assertEquals(Rank.BANISHED, a.rank("verdania_q"))
        assertEquals(Rank.ALLIED, b.rank("aurelia_p"))
        Diplomacy.end(b, a)
        assertEquals(null, a.rank("verdania_p"))
        assertEquals(null, b.rank("aurelia_p"))
        assertEquals(Rank.BANISHED, a.rank("verdania_q"))
    }

    @Test
    fun provincesInheritEmbargoesAndFamilyIsExempt() {
        Realm.reset(Data())
        val top = country("aurelia")
        val child = country("vale")
        val other = country("nordmark")
        Provinces.finalize(child, top, TaxMode.FLAT, 0.0)
        Diplomacy.setEmbargo(top, other, true)
        Diplomacy.setTariff(top, other, 15)
        assertEquals("embargo", Diplomacy.relation(child, other))
        assertEquals("embargo", Diplomacy.relation(other, child))
        assertEquals("family", Diplomacy.relation(top, child))
        assertEquals(15, Diplomacy.tariff(top, other))
        assertEquals(0, Diplomacy.tariff(top, child))
        assertFailsWith<Fail> { Diplomacy.setTariff(top, child, 5) }
        assertFailsWith<Fail> { Diplomacy.setTariff(top, other, 7) }
        assertFailsWith<Fail> { Diplomacy.propose(top, other) }
    }

    @Test
    fun disbandClearsRelationsAndOldSavesLoad() {
        Realm.reset(Data())
        val a = country("aurelia")
        val b = country("verdania")
        Diplomacy.propose(a, b)
        Diplomacy.accept(b, a)
        Diplomacy.setTariff(a, b, 10)
        Realm.disband(b)
        assertTrue(a.alliances.isEmpty())
        assertTrue(a.tradePolicy.isEmpty())
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<Country>("""{"name":"Old"}""")
        assertTrue(old.alliances.isEmpty())
        assertFalse(old.tradePolicy.containsKey("x"))
    }
}
