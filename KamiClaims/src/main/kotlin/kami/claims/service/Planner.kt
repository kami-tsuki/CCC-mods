package kami.claims.service

import kami.claims.Claim
import kami.claims.Config
import kami.claims.Country
import kami.claims.Key
import kami.claims.Realm
import kami.claims.now
import kotlin.math.max
import kotlin.math.min

enum class Outcome { CLAIM, FREE, RETYPE, UNCLAIM, BLOCKED, SKIP }

class PlannedCell(val key: Key, val outcome: Outcome, val reason: String? = null, val price: Int = 0, val period: Int = 1)

class Plan(val cells: List<PlannedCell>, val costNow: Long, val upkeepPerDay: Double) {
    val ready get() = cells.filter { it.outcome != Outcome.BLOCKED && it.outcome != Outcome.SKIP }
    val blocked get() = cells.filter { it.outcome == Outcome.BLOCKED }
}

object Planner {
    private val s get() = Config.s

    private fun neighbours(k: Key) = listOf(Key(k.dim, k.x + 1, k.z), Key(k.dim, k.x - 1, k.z), Key(k.dim, k.x, k.z + 1), Key(k.dim, k.x, k.z - 1))

    fun blockReason(c: Country, k: Key, type: String, owned: Set<Key>, treasury: Long, count: Int): String? {
        if (k.dim !in s.dimensionSet) return "Claims are off in this dimension"
        val def = s.types[type] ?: return "Unknown chunk type"
        Realm.index[k]?.let { cl -> return if (cl.country == c.id) "Already yours" else "Owned by ${Realm.data.countries[cl.country]?.name ?: cl.country}" }
        Realm.reservedFor(k.dim, k.x, k.z)?.let { if (it != c.id) return "This land is reserved for ${Realm.data.countries[it]?.name ?: it} after they lost it" }
        if (owned.isNotEmpty() && neighbours(k).none { it in owned }) return "Not connected to your land"
        if (count >= Realm.freeAllowed(c) && treasury < def.price) return "The treasury can't pay the first day (${def.price} spur)"
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

    fun unclaimLock(cl: Claim): String? {
        val age = now() - cl.at
        if (age < s.dayMillis) return "Newly claimed, can be released in ${(s.dayMillis - age) / 3_600_000 + 1}h"
        if (cl.upkeepCycles < 1) return "Must go through one upkeep day first"
        return null
    }

    fun unclaim(c: Country, keys: List<Key>): Plan {
        val set = keys.toHashSet()
        val remaining = Realm.claims(c.id).associateBy { it.key }.toMutableMap()
        val decided = LinkedHashMap<Key, PlannedCell>()
        val candidates = remaining.values.filter { it.key in set }.sortedByDescending { it.at }
        candidates.forEach { cl ->
            when {
                cl.capital -> decided[cl.key] = PlannedCell(cl.key, Outcome.BLOCKED, "The capital can't be released, move it first")
                unclaimLock(cl) != null -> decided[cl.key] = PlannedCell(cl.key, Outcome.BLOCKED, unclaimLock(cl))
            }
        }
        for (pass in 0 until 6) {
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
        candidates.filter { it.key !in decided }.forEach { decided[it.key] = PlannedCell(it.key, Outcome.BLOCKED, "Would split your land in two") }
        keys.filter { it !in decided }.forEach { k -> decided[k] = PlannedCell(k, Outcome.SKIP, "Not yours") }
        val ordered = keys.distinct().mapNotNull { decided[it] }
        val saved = ordered.filter { it.outcome == Outcome.UNCLAIM }.sumOf { k -> Realm.index[k.key]?.takeIf { !it.free }?.let { it.def?.price?.toDouble()?.div(Realm.period(it)) } ?: 0.0 }
        return Plan(ordered, 0, -saved)
    }

    fun retype(c: Country, type: String, keys: List<Key>): Plan {
        val def = s.types[type]
        val cells = keys.distinct().map { k ->
            val cl = Realm.index[k]
            when {
                cl == null || cl.country != c.id -> PlannedCell(k, Outcome.SKIP, "Not yours")
                cl.type == type -> PlannedCell(k, Outcome.SKIP, "Already $type")
                else -> PlannedCell(k, Outcome.RETYPE, if (cl.owner != null && type != "residential") "The plot owner loses this plot" else null, def?.price ?: 0, max(1, def?.period ?: 1))
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
