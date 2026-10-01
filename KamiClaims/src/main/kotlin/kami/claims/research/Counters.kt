package kami.claims.research

import kami.claims.Country
import kami.claims.Realm
import kami.claims.net.ResearchSync

object Counters {
    const val TREES_GROWN = "trees_grown"
    const val TAXES_COLLECTED = "taxes_collected"

    fun add(country: Country, key: String, amount: Long = 1) = event(country, key, "", amount)

    fun event(country: Country, kind: String, subject: String, amount: Long) {
        if (amount <= 0) return
        country.counters.merge(kind, amount, Long::plus)
        if (subject.isNotBlank()) country.counters.merge("$kind:$subject", amount, Long::plus)
        Realm.dirty = true
        ResearchSync.refresh(country)
    }
}
