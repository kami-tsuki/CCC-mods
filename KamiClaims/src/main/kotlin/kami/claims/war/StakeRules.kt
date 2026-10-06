package kami.claims.war

import kami.claims.Config
import kami.claims.Country
import kami.claims.Key
import kami.claims.Realm
import kami.claims.now
import kami.claims.service.Fail
import kami.claims.service.Words.money
import kotlin.math.pow
import kotlin.math.roundToLong

object StakeRules {
    private val s get() = Config.s.war

    fun check(attacker: Country, defender: Country, kind: WarKind, stakes: Stakes, offer: Boolean = false) {
        if (stakes.money < 0) throw Fail("kami_claims.error.war.stake.money")
        val land = stakes.chunks.isNotEmpty() || stakes.provinces.isNotEmpty()
        if (kind == WarKind.REBELLION && (land || stakes.vassal && !offer)) throw Fail("kami_claims.error.war.stake.rebellion")
        if (!offer && kind == WarKind.REGULAR && !land && !stakes.vassal && stakes.money == 0L) throw Fail("kami_claims.error.war.stake.empty")
        if (stakes.vassal && defender.parent == attacker.id) throw Fail("kami_claims.error.war.stake.vassal")
        if (stakes.provinces.toSet().size != stakes.provinces.size || stakes.provinces.any { it !in defender.provinces }) throw Fail("kami_claims.error.war.stake.province")
        if (stakes.provinces.any { id -> Realm.country(id)?.let(Wars::rebel) == true }) throw Fail("kami_claims.error.war.stake.rebel")
        chunks(attacker, defender, stakes.chunks)
    }

    fun validate(attacker: Country, defender: Country, kind: WarKind, stakes: Stakes): Long {
        check(attacker, defender, kind, stakes)
        val fee = fee(attacker, stakes, defender)
        val cost = if (stakes.money > Long.MAX_VALUE - fee) Long.MAX_VALUE else stakes.money + fee
        if (attacker.treasury < cost) throw Fail("kami_claims.error.war.stake.funds", money(cost))
        return fee
    }

    private fun chunks(attacker: Country, defender: Country, chunks: List<ChunkRef>) {
        if (chunks.isEmpty()) return
        val set = chunks.mapTo(LinkedHashSet()) { it.key }
        if (set.size != chunks.size) throw Fail("kami_claims.error.war.stake.duplicate")
        if (set.distinctBy { it.dim }.size > 1) throw Fail("kami_claims.error.war.stake.dimension")
        if (set.any { k -> Realm.index[k].let { it == null || it.country != defender.id || it.capital } }) throw Fail("kami_claims.error.war.stake.owner")
        if (!connected(set)) throw Fail("kami_claims.error.war.stake.connected")
        if (set.none { k -> Realm.neighbors(k).any { Realm.index[it]?.country == attacker.id } }) throw Fail("kami_claims.error.war.stake.border")
    }

    fun connected(set: Set<Key>): Boolean {
        val seen = mutableSetOf(set.first())
        val queue = ArrayDeque(seen)
        while (queue.isNotEmpty()) Realm.neighbors(queue.removeFirst()).filter { it in set && seen.add(it) }.forEach(queue::addLast)
        return seen.size == set.size
    }

    fun value(stakes: Stakes, defender: Country): Long {
        val power = (if (stakes.vassal) Power.score(defender) else 0.0) + Power.staked(stakes)
        val land = stakes.chunks.sumOf { c -> Realm.at(c.dim, c.x, c.z)?.let { Realm.price(it).toDouble() } ?: 0.0 }
        return (stakes.money.toDouble() + land + power * s.stakePowerValue).roundToLong()
    }

    private fun fee(attacker: Country, stakes: Stakes, defender: Country): Long {
        val since = now() - s.feeDoublingDays * Config.s.dayMillis
        val n = attacker.warDeclared.count { it > since }.coerceAtMost(40)
        val fee = maxOf(s.feeMin.toDouble(), value(stakes, defender) * s.feePct) * Reputation.factor(attacker) * 2.0.pow(n)
        return if (fee.isNaN()) s.feeMin else fee.coerceIn(0.0, Long.MAX_VALUE.toDouble()).roundToLong()
    }
}
