package kami.claims.research

import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.economy.Treasury
import kami.claims.net.ResearchSync
import kami.claims.service.Fail
import kami.claims.service.Words

object Tokens {
    const val RENAME = "rename"
    const val CAPITAL_MOVE = "capital_move"

    private fun count(country: Country, id: String) = country.tokens[id] ?: 0

    fun grant(country: Country, id: String, count: Int) {
        country.tokens.merge(id, count, Int::plus)
        ResearchSync.refresh(country)
    }

    fun spend(country: Country, id: String, cost: Long, actor: String? = null) {
        val held = count(country, id)
        if (held > 0) {
            if (held == 1) country.tokens.remove(id) else country.tokens[id] = held - 1
            ResearchSync.refresh(country)
            return
        }
        if (country.treasury < cost) throw Fail("kami_claims.error.token_cost", Words.money(cost))
        Treasury.move(country, LedgerKind.FEE, -cost, actor, id)
    }
}
