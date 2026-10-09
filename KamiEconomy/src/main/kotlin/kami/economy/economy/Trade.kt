package kami.economy.economy

import kami.economy.Config
import kami.economy.quest.Quests
import kami.libs.claims.ClaimsApi
import kami.libs.claims.Relation
import kami.libs.util.toUuidOrNull
import kotlin.math.roundToInt

class Terms(val taxPct: Int, val tariffPct: Int, val relation: Relation) {
    val blocked get() = relation == Relation.EMBARGO
    val rates get() = if (tariffPct > 0) listOf(taxPct, tariffPct) else listOf(taxPct)
}

object Trade {
    fun country(player: String): String? = player.toUuidOrNull()?.let { ClaimsApi.countryOf(it) }

    fun sameCountry(a: String, b: String): Boolean {
        val x = country(a) ?: return false
        return x == country(b)
    }

    fun terms(buyer: String, seller: String): Terms {
        if (Quests.owns(buyer) || Quests.owns(seller)) return Terms(0, 0, Relation.NEUTRAL)
        val a = country(buyer)
        val b = country(seller)
        if (a == null || b == null) return Terms(Config.s.taxPct, 0, Relation.NEUTRAL)
        val t = resolve(ClaimsApi.relation(a, b), ClaimsApi.tariff(a, b))
        if (a == b) return t
        return Terms((t.taxPct * ClaimsApi.tradeFactor(b)).roundToInt().coerceAtLeast(0), t.tariffPct, t.relation)
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
