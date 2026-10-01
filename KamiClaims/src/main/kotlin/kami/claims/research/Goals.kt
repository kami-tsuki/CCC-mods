package kami.claims.research

import kami.claims.Country
import kami.claims.Realm
import kami.claims.service.Words
import kami.libs.claims.Goal
import kami.libs.text.Phrase

object Goals {
    private const val LIMIT = 6
    private val UNLISTED = setOf(Capacity.PLOTS, Capacity.FREE_CHUNKS, Capacity.MARKET_SLOTS, Capacity.AUCTION_SLOTS)

    class Raise(val level: Int, val max: Int)

    fun capacity(id: String): Capacity? = Capacity.entries.firstOrNull { it.id == id }

    fun label(key: Capacity): Phrase = Phrase.of("kami_claims.research.capacity.${key.name.lowercase()}").asValue()

    fun nextRaise(country: Country, key: Capacity): Raise? {
        val config = Research.defs.levels
        val level = Research.level(country)
        fun raises(list: List<Unlock>) = list.filterIsInstance<CapacityUnlock>().filter { it.key == key && it.add > 0 }
        val at = config.rewards.filter { (l, list) -> l > level && l <= config.top && raises(list).isNotEmpty() }.keys.minOrNull() ?: return null
        val gain = config.rewards.filterKeys { it in level + 1..at }.values.sumOf { list -> raises(list).sumOf { it.add } }
        return Raise(at, Levels.capacity(country, key) + gain)
    }

    fun of(country: Country): List<Goal> = (loans(country) + requirements(country) + experience(country) + research(country) + capacities(country)).take(LIMIT)

    private fun loans(country: Country): List<Goal> {
        val due = Loans.dueNext(country)
        if (due <= 0 || country.treasury >= due) return emptyList()
        return listOf(Goal(Phrase.of("kami_claims.goal.loan_due", Words.money(due)), country.treasury.coerceAtLeast(0), due, "loans"))
    }

    private fun requirements(country: Country): List<Goal> {
        val next = Research.level(country) + 1
        if (next > Research.defs.levels.top) return emptyList()
        return Research.defs.levels.requirements(next).filterNot { it.met(country) }.map { cond ->
            val (value, max) = progress(country, cond)
            Goal(Phrase.of("kami_claims.goal.requirement", Words.v(next), cond.describe()), value.coerceIn(0, max), max, page(cond))
        }
    }

    private fun experience(country: Country): List<Goal> {
        val config = Research.defs.levels
        val level = Research.level(country)
        if (level + 1 > config.top) return emptyList()
        val from = config.xpFor(level)
        val to = config.xpFor(level + 1)
        if (country.xp >= to) return emptyList()
        val head = Goal(Phrase.of("kami_claims.goal.xp", Words.num(to - country.xp), Words.v(level + 1)), (country.xp - from).coerceIn(0, to - from), to - from, "levels")
        if (level < config.xpFromLevel) return listOf(head)
        val today = if (country.xpDay == Levels.day()) country.xpToday else emptyMap()
        val sources = config.sources.entries.filter { it.value.dailyCap > 0 && (today[it.key] ?: 0L) < it.value.dailyCap }
            .sortedByDescending { it.value.dailyCap - (today[it.key] ?: 0L) }.take(2)
            .map { Goal(Phrase.of("kami_claims.goal.source", Phrase.or("kami_claims.goal.source.${it.key}", it.key).asValue()), today[it.key] ?: 0L, it.value.dailyCap, "levels") }
        return listOf(head) + sources
    }

    private fun research(country: Country): List<Goal> {
        if (!Research.defs.settings.enabled) return emptyList()
        val ready = country.research.queue.firstOrNull { it.state == NodeState.READY }?.let { Research.defs.node(it.node) }
        if (ready != null) return listOf(Goal(Phrase.of("kami_claims.goal.research_start", ready.label().asValue()), 0, 1, "research"))
        if (Queue.waiting(country) >= Levels.capacity(country, Capacity.QUEUE_SLOTS)) return emptyList()
        val node = Research.available(country).minWithOrNull(compareBy<Node>({ it.level }, { it.cost })) ?: return emptyList()
        return listOf(Goal(Phrase.of("kami_claims.goal.research_queue", node.label().asValue()), 0, 1, "research"))
    }

    private fun capacities(country: Country): List<Goal> = Capacity.entries.filterNot { it in UNLISTED }.mapNotNull { key ->
        val max = Levels.capacity(country, key)
        val used = Levels.used(country, key)
        if (max <= 0 || used * 10L < max * 9L) return@mapNotNull null
        val raise = nextRaise(country, key) ?: return@mapNotNull null
        Goal(Phrase.of("kami_claims.goal.capacity", label(key), Words.v(raise.level), Words.v(raise.max)), used.toLong(), max.toLong(), page(key))
    }

    private fun progress(country: Country, cond: Condition): Pair<Long, Long> = when (cond) {
        is MinChunks -> Realm.claims(country.id).count { cond.chunkType == null || it.type == cond.chunkType }.toLong() to cond.min.toLong()
        is MinCitizens -> country.members.size.toLong() to cond.min.toLong()
        is MinTreasury -> country.treasury.coerceAtLeast(0) to cond.min
        is MinCounter -> (country.counters[cond.key] ?: 0L) to cond.min
        is MinPlots -> Realm.claims(country.id).count { it.type == cond.chunkType && it.owner != null }.toLong() to cond.min.toLong()
        is MinProvinces -> country.provinces.size.toLong() to cond.min.toLong()
        is MinAllies -> country.alliances.size.toLong() to cond.min.toLong()
        else -> 0L to 1L
    }

    private fun page(cond: Condition) = when (cond) {
        is MinChunks -> "chunks"
        is MinCitizens -> "citizens"
        is MinTreasury -> "budget"
        is MinPlots -> "plots"
        is MinProvinces -> "provinces"
        is MinAllies -> "relations"
        is FlagSet -> "identity"
        else -> "levels"
    }

    private fun page(key: Capacity) = when (key) {
        Capacity.CHUNKS -> "chunks"
        Capacity.PROVINCES -> "provinces"
        Capacity.CITIZENS, Capacity.OFFICERS -> "citizens"
        Capacity.TREASURY -> "budget"
        Capacity.RESEARCH_SLOTS, Capacity.QUEUE_SLOTS -> "research"
        else -> "levels"
    }
}
