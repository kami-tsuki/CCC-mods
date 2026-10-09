package kami.claims

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FreeChunksTest {
    @BeforeTest
    fun setup() {
        Realm.reset(Data())
        Config.s = Settings(freeChunks = 9, freeBonusMembers = 5, freeBonusCap = 2, inactiveDays = 10)
    }

    @AfterTest
    fun restore() {
        Config.s = Settings()
    }

    private fun country(members: Int, lastActive: Long = now()): Country {
        val c = Country("freeland", lastActive = lastActive)
        Realm.data.countries[c.id] = c
        repeat(members) { c.members["m$it"] = Member(Rank.PRESIDENT) }
        return c
    }

    @Test
    fun inactiveCountriesLoseAllFreeChunks() {
        val c = country(1, now() - 11 * Config.s.dayMillis)
        assertEquals(0, Realm.freeAllowed(c))
    }

    @Test
    fun memberBonusIsCappedAndAddsToTheBase() {
        val base = Realm.freeAllowed(country(1))
        assertEquals(base + 1, Realm.freeAllowed(country(5)))
        assertEquals(base + 2, Realm.freeAllowed(country(20)))
    }

    @Test
    fun zeroBonusMembersDoesNotDivideByZero() {
        Config.s = Settings(freeBonusMembers = 0, freeBonusCap = 3)
        val base = Realm.freeAllowed(country(0))
        assertEquals(base + 3, Realm.freeAllowed(country(10)))
    }

    @Test
    fun refreshFreeMarksCapitalFirstAndIsIdempotent() {
        Config.s = Settings(freeChunks = 2, freeBonusMembers = 5, freeBonusCap = 0)
        val c = country(1)
        val allowed = Realm.freeAllowed(c)
        (0..allowed + 1).forEach { Realm.add(Claim(c.id, "minecraft:overworld", it, 0, "civic", at = it.toLong(), since = 0, capital = it == allowed + 1)) }
        repeat(2) { Realm.refreshFree(c) }
        val claims = Realm.claims(c.id)
        assertEquals(allowed, claims.count { it.free })
        assertEquals(true, claims.single { it.capital }.free)
    }
}
