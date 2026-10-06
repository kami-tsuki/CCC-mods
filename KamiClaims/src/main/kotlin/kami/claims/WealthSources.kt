package kami.claims

import kami.libs.economy.CountryParty
import kami.libs.economy.Wealth

internal object WealthSources {
    fun register() {
        Wealth.distribution("countries") { Realm.data.countries.values.filter { it.active }.map { Wealth.Holder(CountryParty(it.id).key, it.name, it.treasury) } }
        Wealth.source("escrow.war") { Realm.data.wars.sumOf { it.escrow } }
        Wealth.source("escrow.rent") { Realm.data.claims.sumOf { it.rentCredit } }
    }
}
