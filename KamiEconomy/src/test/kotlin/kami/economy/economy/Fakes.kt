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
        override fun creditTariff(country: String, amount: Long, payer: String, note: String): Long = minOf(amount, creditRoom).also { credits += country to it }
    }
}

class Purse(vararg start: Pair<UUID, Long>, private val initial: Long = 0) : Wallet {
    val funds = mutableMapOf(*start)
    val deposits = mutableListOf<Pair<UUID, Int>>()
    var failBalance: UUID? = null
    private fun held(id: UUID) = funds.getOrDefault(id, initial)
    override fun balance(id: UUID): Long {
        if (id == failBalance) { failBalance = null; error("injected") }
        return held(id)
    }
    override fun deduct(id: UUID, amount: Int): Int = minOf(amount.toLong(), held(id)).toInt().also { funds[id] = held(id) - it }
    override fun deposit(id: UUID, amount: Int): Boolean { deposits += id to amount; funds[id] = held(id) + amount; return true }
}

class Till(var funds: Long = 1_000_000, private val credit: Boolean = false) : Wallet {
    val deposits = mutableListOf<Pair<UUID, Int>>()
    var onDeduct: () -> Unit = {}
    fun received(id: UUID) = deposits.filter { it.first == id }.sumOf { it.second.toLong() }
    override fun balance(id: UUID) = funds
    override fun deduct(id: UUID, amount: Int): Int {
        onDeduct()
        return minOf(amount.toLong(), funds).toInt().also { funds -= it }
    }
    override fun deposit(id: UUID, amount: Int): Boolean {
        deposits += id to amount
        if (credit) funds += amount
        return true
    }
}
