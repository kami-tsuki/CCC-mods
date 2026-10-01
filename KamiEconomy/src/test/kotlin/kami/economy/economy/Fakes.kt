package kami.economy.economy

import kami.libs.claims.Citizenship
import kami.libs.claims.ClaimInfo
import kami.libs.claims.ClaimsProvider
import kami.libs.claims.Relation
import kami.libs.text.Phrase
import java.util.UUID

object Fakes {
    fun claims(): Claims = object : Claims() {}

    abstract class Claims : ClaimsProvider {
        val home = mutableMapOf<UUID, String>()
        val relations = mutableMapOf<Pair<String, String>, Relation>()
        val tariffs = mutableMapOf<Pair<String, String>, Int>()
        val credits = mutableListOf<Pair<String, Long>>()
        val capacities = mutableMapOf<String, Int>()
        var homeless = UUID(0, 0)
        var creditRoom = Long.MAX_VALUE
        var locked = false
        override fun at(dim: String, x: Int, z: Int): ClaimInfo? = null
        override fun isBanished(player: UUID, country: String) = false
        override fun countryOf(player: UUID) = home[player] ?: "aurelia"
        override fun citizenship(player: UUID): Citizenship? = null
        override fun relation(a: String, b: String) = relations[a to b] ?: relations[b to a] ?: Relation.NEUTRAL
        override fun tariff(buyerCountry: String, sellerCountry: String) = tariffs[buyerCountry to sellerCountry] ?: 0
        override fun capacity(player: UUID, key: String) = capacities[key]
        override fun lock(player: UUID, feature: String) = if (locked) Phrase.of("test.locked", feature) else null
        override fun creditTariff(country: String, amount: Long): Long = minOf(amount, creditRoom).also { credits += country to it }
    }
}
