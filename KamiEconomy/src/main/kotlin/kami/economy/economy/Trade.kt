package kami.economy.economy

import kami.economy.Config
import kami.libs.claims.ClaimsApi
import kami.libs.claims.Relation
import java.util.UUID

class Terms(val taxPct: Int, val tariffPct: Int, val relation: Relation) {
    val blocked get() = relation == Relation.EMBARGO
    val rates get() = if (tariffPct > 0) listOf(taxPct, tariffPct) else listOf(taxPct)
    fun withoutTariff() = Terms(taxPct, 0, relation)
}

object Trade {
    fun country(player: String): String? = runCatching { UUID.fromString(player) }.getOrNull()?.let { ClaimsApi.countryOf(it) }

    fun terms(buyer: String, seller: String): Terms {
        val a = country(buyer)
        val b = country(seller)
        if (a == null || b == null) return Terms(Config.s.taxPct, 0, Relation.NEUTRAL)
        return resolve(ClaimsApi.relation(a, b), ClaimsApi.tariff(a, b))
    }

    fun resolve(relation: Relation, tariffPct: Int): Terms = when (relation) {
        Relation.EMBARGO -> Terms(Config.s.taxPct, 0, relation)
        Relation.ALLIED, Relation.FAMILY -> Terms(Config.s.allyTaxPct, tariffPct.coerceIn(0, 100), relation)
        Relation.NEUTRAL -> Terms(Config.s.taxPct, tariffPct.coerceIn(0, 100), relation)
    }

    fun canTrade(a: String, b: String): Boolean {
        val x = country(a) ?: return true
        val y = country(b) ?: return true
        return ClaimsApi.canTrade(x, y)
    }
}
