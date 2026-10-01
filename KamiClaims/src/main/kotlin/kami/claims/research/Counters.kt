package kami.claims.research

import kami.claims.Country
import kami.claims.Realm
import kami.claims.net.ResearchSync

object Counters {
    const val TREES_GROWN = "trees_grown"
    const val TAXES_COLLECTED = "taxes_collected"

    fun add(country: Country, key: String, amount: Long = 1) {
        if (amount <= 0) return
        country.counters.merge(key, amount, Long::plus)
        Realm.dirty = true
        ResearchSync.refresh(country)
    }

    fun event(country: Country, kind: String, subject: String, amount: Long) {
        add(country, kind, amount)
        if (subject.isNotBlank()) add(country, "$kind:$subject", amount)
    }
}
