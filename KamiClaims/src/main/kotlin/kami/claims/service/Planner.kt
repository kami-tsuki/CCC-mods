package kami.claims.service

import kami.claims.Claim
import kami.claims.Config
import kami.claims.Country
import kami.claims.Key
import kami.claims.Realm
import kami.claims.now
import kami.claims.research.Capacity
import kami.claims.research.Features
import kami.claims.research.Levels
import kami.libs.text.Phrase
import kotlin.math.max
import kotlin.math.min

enum class Outcome { CLAIM, FREE, RETYPE, UNCLAIM, BLOCKED, SKIP }

class PlannedCell(val key: Key, val outcome: Outcome, val reason: Phrase? = null, val price: Int = 0, val period: Int = 1)

class Plan(val cells: List<PlannedCell>, val costNow: Long, val upkeepPerDay: Double) {
    val ready get() = cells.filter { it.outcome != Outcome.BLOCKED && it.outcome != Outcome.SKIP }
    val blocked get() = cells.filter { it.outcome == Outcome.BLOCKED }
}

object Planner {
    private val s get() = Config.s

    private fun neighbours(k: Key) = listOf(Key(k.dim, k.x + 1, k.z), Key(k.dim, k.x - 1, k.z), Key(k.dim, k.x, k.z + 1), Key(k.dim, k.x, k.z - 1))

    fun blockReason(c: Country, k: Key, type: String, owned: Set<Key>, treasury: Long, count: Int): Phrase? {
        if (k.dim !in s.dimensionSet) return Phrase.of("kami_claims.block.dimension")
        val def = s.types[type] ?: return Phrase.of("kami_claims.block.unknown_type")
        Realm.index[k]?.let { cl -> return if (cl.country == c.id) Phrase.of("kami_claims.block.yours") else Phrase.of("kami_claims.block.owned", Realm.data.countries[cl.country]?.name ?: cl.country) }
        Realm.reservedFor(k.dim, k.x, k.z)?.let { if (it != c.id) return Phrase.of("kami_claims.block.reserved", Realm.data.countries[it]?.name ?: it) }
        Features.lockReason(c, Features.claimType(type))?.let { return it }
        if (owned.isNotEmpty() && neighbours(k).none { it in owned }) return Phrase.of("kami_claims.block.not_connected")
        Features.limit(c, Capacity.CHUNKS, count)?.let { return it }
        if (count >= Realm.freeAllowed(c) && treasury < def.price) return Phrase.of("kami_claims.block.treasury", Phrase.of("kami_libs.unit.money", def.price.toString()))
        return null
    }

    fun claim(c: Country, type: String, keys: List<Key>): Plan {
        val def = s.types[type]
        val owned = Realm.claims(c.id).map { it.key }.toHashSet()
        var treasury = c.treasury
        var count = owned.size
        val decided = LinkedHashMap<Key, PlannedCell>()
        val pending = keys.distinct().toMutableList()
        for (pass in 0..min(pending.size, 64)) {
            var progress = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val k = iterator.next()
                if (blockReason(c, k, type, owned, treasury, count) != null) continue
                val free = count < Realm.freeAllowed(c)
                val price = def?.price ?: 0
                if (!free) treasury -= price
                decided[k] = PlannedCell(k, if (free) Outcome.FREE else Outcome.CLAIM, null, if (free) 0 else price, max(1, def?.period ?: 1))
                owned += k
                count++
                iterator.remove()
                progress = true
            }
            if (!progress) break
        }
        pending.forEach { k -> decided[k] = PlannedCell(k, if (Realm.index[k]?.country == c.id) Outcome.SKIP else Outcome.BLOCKED, blockReason(c, k, type, owned, treasury, count)) }
        val ordered = keys.distinct().mapNotNull { decided[it] }
        return Plan(ordered, c.treasury - treasury, ordered.filter { it.outcome == Outcome.CLAIM || it.outcome == Outcome.FREE }.sumOf { if (it.outcome == Outcome.FREE) 0.0 else it.price.toDouble() / it.period })
    }

    fun unclaimLock(cl: Claim): Phrase? {
        val age = now() - cl.at
        if (age < s.dayMillis) return Phrase.of("kami_claims.block.new", Phrase.of("kami_libs.unit.hour.short", ((s.dayMillis - age) / 3_600_000 + 1).toString()))
        if (cl.upkeepCycles < 1) return Phrase.of("kami_claims.block.upkeep_day")
        return null
    }

    fun unclaim(c: Country, keys: List<Key>): Plan {
        val set = keys.toHashSet()
        val remaining = Realm.claims(c.id).associateBy { it.key }.toMutableMap()
        val decided = LinkedHashMap<Key, PlannedCell>()
        val candidates = remaining.values.filter { it.key in set }.sortedByDescending { it.at }
        candidates.forEach { cl ->
            when {
                cl.capital -> decided[cl.key] = PlannedCell(cl.key, Outcome.BLOCKED, Phrase.of("kami_claims.block.capital"))
                unclaimLock(cl) != null -> decided[cl.key] = PlannedCell(cl.key, Outcome.BLOCKED, unclaimLock(cl))
            }
        }
        val undecided = candidates.filter { it.key !in decided }
        if (undecided.isNotEmpty() && undecided.groupBy { it.dim }.all { (dim, cells) -> Realm.removableBatch(c.id, dim, cells.map { it.key }.toSet()) }) {
            undecided.forEach { cl ->
                remaining.remove(cl.key)
                decided[cl.key] = PlannedCell(cl.key, Outcome.UNCLAIM, null, Realm.price(cl), Realm.period(cl))
            }
        } else for (pass in 0 until 6) {
            var progress = false
            candidates.filter { it.key !in decided }.forEach { cl ->
                if (connectedWithout(remaining, cl)) {
                    remaining.remove(cl.key)
                    decided[cl.key] = PlannedCell(cl.key, Outcome.UNCLAIM, null, Realm.price(cl), Realm.period(cl))
                    progress = true
                }
            }
            if (!progress) break
        }
        candidates.filter { it.key !in decided }.forEach { decided[it.key] = PlannedCell(it.key, Outcome.BLOCKED, Phrase.of("kami_claims.block.split")) }
        keys.filter { it !in decided }.forEach { k -> decided[k] = PlannedCell(k, Outcome.SKIP, Phrase.of("kami_claims.block.not_yours")) }
        val ordered = keys.distinct().mapNotNull { decided[it] }
        val saved = ordered.filter { it.outcome == Outcome.UNCLAIM }.sumOf { k -> Realm.index[k.key]?.takeIf { !it.free }?.let { it.def?.price?.toDouble()?.div(Realm.period(it)) } ?: 0.0 }
        return Plan(ordered, 0, -saved)
    }

    fun retype(c: Country, type: String, keys: List<Key>): Plan {
        val def = s.types[type]
        val lock = Features.lockReason(c, Features.claimType(type))
        val cells = keys.distinct().map { k ->
            val cl = Realm.index[k]
            when {
                cl == null || cl.country != c.id -> PlannedCell(k, Outcome.SKIP, Phrase.of("kami_claims.block.not_yours"))
                cl.type == type -> PlannedCell(k, Outcome.SKIP, Phrase.of("kami_claims.block.same_type"))
                lock != null -> PlannedCell(k, Outcome.BLOCKED, lock)
                else -> PlannedCell(k, Outcome.RETYPE, if (cl.owner != null && type != "residential") Phrase.of("kami_claims.block.tenant_loses") else null, def?.price ?: 0, max(1, def?.period ?: 1))
            }
        }
        val delta = cells.filter { it.outcome == Outcome.RETYPE }.sumOf { cell ->
            val cl = Realm.index[cell.key]!!
            if (cl.free) 0.0 else cell.price.toDouble() / cell.period - Realm.price(cl).toDouble() / Realm.period(cl)
        }
        return Plan(cells, 0, delta)
    }

    private fun connectedWithout(remaining: Map<Key, Claim>, target: Claim): Boolean {
        val rest = remaining.filterKeys { it != target.key && it.dim == target.dim }
        val start = rest.keys.firstOrNull() ?: return true
        val seen = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) neighbours(queue.removeFirst()).forEach { k -> if (k in rest && seen.add(k)) queue.addLast(k) }
        return seen.size == rest.size
    }
}
