package kami.claims

import kami.claims.admin.AdminCountries
import kami.claims.admin.CountryAdmin
import kami.claims.service.Fail
import kami.claims.economy.Treasury
import kami.libs.economy.ServerBank
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminCountriesTest : WithDb() {
    private fun country(name: String, treasury: Long = 1_000): Country {
        val c = Country(name, treasury = treasury)
        Realm.data.countries[c.id] = c
        Realm.join(c, "${name}_p", Rank.PRESIDENT)
        return c
    }

    @BeforeTest
    fun setup() = Realm.reset(Data())

    @Test
    fun moneyConserves() {
        val c = country("alpha")
        val total = c.treasury + ServerBank.balance
        assertEquals(500, CountryAdmin.money(c, true, 500, null))
        assertEquals(1_500, c.treasury)
        assertEquals(1_500, CountryAdmin.money(c, false, 9_999, null))
        assertEquals(0, c.treasury)
        assertEquals(total, c.treasury + ServerBank.balance)
    }

    @Test
    fun bankRejectionAppliesNothing() {
        val a = country("alpha", 0)
        val b = country("beta", 0)
        assertEquals(Long.MAX_VALUE, Treasury.bank(a, LedgerKind.ADJUST, Long.MAX_VALUE, capped = false))
        val bank = ServerBank.balance
        assertEquals(0, Treasury.bank(b, LedgerKind.ADJUST, 2, capped = false))
        assertEquals(0, b.treasury)
        assertEquals(0, b.ledger.size)
        assertEquals(bank, ServerBank.balance)
    }

    @Test
    fun renameValidates() {
        val c = country("alpha")
        country("beta")
        assertFailsWith<Fail> { CountryAdmin.rename(c, "beta") }
        assertFailsWith<Fail> { CountryAdmin.rename(c, "bad name!") }
        CountryAdmin.rename(c, "gamma")
        assertEquals("gamma", c.name)
        assertEquals(1_000, c.treasury)
    }

    @Test
    fun staleRevisionRejected() {
        val c = country("alpha")
        val rev = AdminCountries.revision(c)
        AdminCountries.fresh(c, rev)
        CountryAdmin.rename(c, "delta")
        assertFailsWith<Fail> { AdminCountries.fresh(c, rev) }
    }
}
