package kami.claims.war

import kami.claims.Config
import kami.claims.Country
import kami.claims.Realm
import kami.claims.service.Fail
import kami.claims.service.Words.v
import kami.libs.text.Phrase

object WarLocks {
    private fun lock(what: String, vararg cs: Country) {
        if (cs.any(Wars::involved)) throw Fail("kami_claims.error.war.locked.$what")
    }

    fun claims(c: Country) = lock("claims", c)

    fun diplomacy(vararg cs: Country) = lock("diplomacy", *cs)

    fun disband(c: Country) {
        lock("disband", c)
        if (c.warDebts.isNotEmpty()) throw Fail("kami_claims.error.war.debt_disband")
    }

    fun rename(c: Country) = lock("rename", c)

    fun capital(c: Country) = lock("capital", c)

    fun independence(vararg cs: Country) = lock("independence", *cs)

    fun roster(c: Country, uuid: String) {
        if (Wars.all(c).any { Wars.combatant(it, c, uuid) }) throw Fail("kami_claims.error.war.locked.roster")
    }

    fun stakedProvince(c: Country, province: Country) {
        if ((Wars.all(c) + Wars.all(province)).any { province.id in it.stakes.provinces || rebel(it, c, province) || collateral(it, c) }) throw Fail("kami_claims.error.war.locked.province")
    }

    private fun collateral(war: War, c: Country) = war.attacker.main == c.id && (war.stakes.vassal || war.stakes.provinces.isNotEmpty())

    private fun rebel(war: War, parent: Country, province: Country) = war.side(province.id)?.let { it.main == province.id && parent.id in war.opponent(it).countries } == true

    fun provinceBlock(c: Country) = c.warOverlord?.let { Phrase.of("kami_claims.error.war.war_province", v(Realm.country(it)?.name ?: it)) }

    fun warProvince(c: Country) {
        provinceBlock(c)?.let { throw Fail(it) }
    }

    fun upkeepDebt(c: Country, debt: Int) = if (Wars.involved(c)) debt.coerceAtMost(Config.s.maxDebt) else debt

    fun upkeepRelease(c: Country) = !Wars.involved(c)
}
