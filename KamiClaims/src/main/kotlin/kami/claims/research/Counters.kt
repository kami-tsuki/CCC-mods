package kami.claims.research

import kami.claims.Country
import kami.claims.net.ResearchSync

object Counters {
    const val TREES_GROWN = "trees_grown"
    const val TAXES_COLLECTED = "taxes_collected"

    fun add(country: Country, key: String, amount: Long = 1) = event(country, key, "", amount)

    fun event(country: Country, kind: String, subject: String, amount: Long) {
        if (amount <= 0) return
        val keys = Research.defs.counterKeys
        country.counters.merge(kind, amount, Long::plus)
        val detail = if (subject.isBlank()) null else "$kind:$subject"
        if (detail != null) country.counters.merge(detail, amount, Long::plus)
        if (kind in keys || detail in keys) ResearchSync.refresh(country)
    }
}
