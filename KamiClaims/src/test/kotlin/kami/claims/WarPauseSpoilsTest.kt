package kami.claims

import kami.claims.war.Spoils
import kami.claims.war.War
import kami.claims.war.WarOffer
import kami.claims.war.WarPhase
import kami.claims.war.WarResult
import kami.claims.war.Wars
import kami.claims.war.Stakes
import kami.libs.economy.ServerBank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarPauseSpoilsTest : WithDb() {
    private val day get() = Config.s.dayMillis
    private val gap = 2 * 3_600_000L

    private fun country(name: String): Country {
        val c = Country(name, created = now() - 10 * day, level = 100, treasury = 100_000)
        Realm.data.countries[c.id] = c
        c.members["${name}_p"] = Member(Rank.PRESIDENT, since = now() - 1000)
        return c
    }

    private fun declared(): Triple<Country, Country, War> {
        Realm.reset(Data())
        val a = country("aurelia")
        val b = country("verdania")
        Wars.declare(a, b, stakes = Stakes(money = 100))
        return Triple(a, b, Wars.of(a)!!)
    }

    private fun near(expected: Long, actual: Long) = assertTrue(actual - expected in gap - 5_000..gap + 5_000, "shift ${actual - expected}")

    @Test
    fun downtimeShiftsCeasefireOfferMembersAndFutureCooldowns() {
        val (a, b, war) = declared()
        val t = now()
        war.phase = WarPhase.CEASEFIRE
        war.phaseEnds = t + day
        war.ceasefireStart = t - 1000
        war.warSince = 77
        war.remindedAt = t
        war.offer = WarOffer(a.id, at = t - 500)
        war.offeredAt["x"] = t - 400
        a.lastActive = t - 3000
        a.members.getValue("aurelia_p").seen = t - 2000
        a.warPairs["future"] = t + day
        a.warPairs["past"] = 5
        b.warCooldownUntil = t + day
        Realm.data.warClock = t - gap
        Wars.tick()
        near(t + day, war.phaseEnds)
        near(t - 1000, war.ceasefireStart)
        near(t, war.remindedAt)
        near(t - 500, war.offer!!.at)
        near(t - 400, war.offeredAt.getValue("x"))
        near(t - 3000, a.lastActive)
        near(t - 2000, a.members.getValue("aurelia_p").seen)
        near(t + day, a.warPairs.getValue("future"))
        near(t + day, b.warCooldownUntil)
        assertEquals(77L, war.warSince)
        assertEquals(5L, a.warPairs.getValue("past"))
    }


    @Test
    fun smallGapShiftsNothing() {
        val (_, _, war) = declared()
        val before = war.phaseEnds
        Realm.data.warClock = now() - 1000
        Wars.tick()
        assertEquals(before, war.phaseEnds)
    }

    private fun settle(war: War, result: WarResult) {
        war.result = result
        war.silent = true
        Spoils.apply(war)
    }

    @Test
    fun escrowReturnsToAttackerWhenNotDefeated() {
        val (a, b, war) = declared()
        assertEquals(100L, war.escrow)
        val ta = a.treasury
        val tb = b.treasury
        settle(war, WarResult.WHITE)
        assertEquals(ta + 100, a.treasury)
        assertEquals(tb, b.treasury)
        assertEquals(0L, war.escrow)
    }

    @Test
    fun escrowGoesToDefenderOnDefeat() {
        val (a, b, war) = declared()
        val ta = a.treasury
        val tb = b.treasury
        settle(war, WarResult.DEFENDER)
        assertEquals(tb + 100, b.treasury)
        assertEquals(ta, a.treasury)
    }

    @Test
    fun escrowFallsBackToAttackerWhenDefeatedDefenderIsGone() {
        val (a, b, war) = declared()
        val ta = a.treasury
        b.state = CountryState.DISBANDED
        settle(war, WarResult.DEFENDER)
        assertEquals(ta + 100, a.treasury)
    }

    @Test
    fun escrowGoesToServerBankWhenNoRecipientIsLeft() {
        val (a, b, war) = declared()
        val bank = ServerBank.balance
        val ta = a.treasury
        a.state = CountryState.DISBANDED
        b.state = CountryState.DISBANDED
        settle(war, WarResult.DEFENDER)
        assertEquals(bank + 100, ServerBank.balance)
        assertEquals(ta, a.treasury)
        assertEquals(0L, war.escrow)
    }
}
