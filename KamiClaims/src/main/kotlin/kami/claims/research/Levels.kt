package kami.claims.research

import kami.claims.Country
import kami.claims.Rank
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.economy.Treasury
import kami.claims.net.ResearchSync
import kami.claims.service.Words
import kami.claims.social.Mail
import kami.claims.today
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import kotlinx.serialization.Serializable
import net.neoforged.neoforge.server.ServerLifecycleHooks
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

@Serializable
class XpSource(val rate: Double, val dailyCap: Long = 0)

@Serializable
class LevelRule(val xp: Long? = null, val requires: List<Condition> = emptyList())

@Serializable
class XpCurve(val from: Int = 11, val peak: Int = 50, val base: Double = 100.0, val rise: Double = 0.32, val tail: Double = 1.5) {
    fun cost(level: Int): Long = when {
        level < from -> 0
        level <= peak -> (base + rise * (level - from) * (2 * peak - from - level)).roundToLong()
        else -> (cost(peak) + tail * (level - peak).toDouble().pow(2)).roundToLong()
    }
}

@Serializable
class LevelsConfig(
    val curve: XpCurve = XpCurve(),
    val table: List<Long> = emptyList(),
    val maxLevel: Int = 1000,
    val xpFromLevel: Int = 10,
    val sources: Map<String, XpSource> = LevelDefaults.sources,
    val counted: List<String> = LevelDefaults.counted,
    val capacities: Map<Capacity, Int> = LevelDefaults.capacities,
    val rules: Map<Int, LevelRule> = LevelDefaults.rules,
    val rewards: Map<Int, List<Unlock>> = LevelDefaults.rewards,
    val announce: Boolean = true
) {
    val top get() = minOf(Limits.MAX_LEVELS, if (table.isEmpty()) max(1, maxLevel) else max(1, minOf(maxLevel, table.size + 1)))

    private val cumulative by lazy { LongArray(Limits.MAX_LEVELS + 1).also { sums -> for (level in 1..Limits.MAX_LEVELS) sums[level] = sums[level - 1] + curve.cost(level) } }

    fun curveXp(level: Int): Long = when {
        level <= 1 -> 0
        table.isNotEmpty() -> table[level - 2]
        else -> cumulative[level.coerceAtMost(Limits.MAX_LEVELS)]
    }

    fun xpFor(level: Int): Long = rules[level]?.xp ?: curveXp(level)

    fun levelOf(xp: Long): Int = (top downTo 1).first { xpFor(it) <= xp }

    fun requirements(level: Int): List<Condition> = rules[level]?.requires.orEmpty()

    fun capacity(key: Capacity) = capacities[key] ?: LevelDefaults.capacities[key] ?: 0

    fun rewardsUpTo(level: Int): List<Unlock> = rewards.filterKeys { it <= level }.toSortedMap().values.flatten()

    fun featureLevel(id: String): Int? = rewards.filterValues { list -> list.any { it is FeatureUnlock && it.id == id } }.keys.minOrNull()
}

object Levels {
    var day: () -> Long = ::today
    var announce: (Phrase) -> Unit = { text ->
        ServerLifecycleHooks.getCurrentServer()?.playerList?.broadcastSystemMessage(Mail.chat.info(text.component()), false)
    }

    fun capacity(country: Country, key: Capacity): Int = Research.defs.levels.capacity(key) +
        country.research.done.keys.sumOf { done -> Research.defs.node(done)?.unlocks.orEmpty().added(key) } +
        Research.defs.levels.rewardsUpTo(Research.level(country)).added(key)

    private fun List<Unlock>.added(key: Capacity) = filterIsInstance<CapacityUnlock>().filter { it.key == key }.sumOf { it.add }

    fun settleRewards(country: Country) {
        val level = Research.level(country)
        if (country.rewardedLevel >= level) return
        val fresh = Research.defs.levels.rewards.filterKeys { it in country.rewardedLevel + 1..level }.values.flatten()
        val money = fresh.filterIsInstance<MoneyReward>().sumOf { it.amount }
        fresh.filterIsInstance<TokenUnlock>().forEach { Tokens.grant(country, it.id, it.count) }
        country.rewardedLevel = level
        if (money > 0) Treasury.move(country, LedgerKind.LEVEL_REWARD, money, note = "level $level")
        Realm.dirty = true
        ResearchSync.touch(country)
    }

    fun settleAllRewards() = Realm.data.countries.values.forEach { advance(it); settleRewards(it) }

    fun used(country: Country, key: Capacity) = when (key) {
        Capacity.CHUNKS -> Realm.claims(country.id).size
        Capacity.PROVINCES -> country.provinces.size
        Capacity.CITIZENS -> country.members.size
        Capacity.RESEARCH_SLOTS -> Queue.researching(country)
        Capacity.QUEUE_SLOTS -> Queue.waiting(country)
        Capacity.TREASURY -> country.treasury.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        Capacity.OFFICERS -> country.members.values.count { it.rank == Rank.OFFICER }
        Capacity.FREE_CHUNKS -> Realm.claims(country.id).count { it.free }
        Capacity.PLOTS -> Realm.claims(country.id).filter { it.owner != null }.groupingBy { it.owner }.eachCount().values.maxOrNull() ?: 0
        Capacity.MARKET_SLOTS, Capacity.AUCTION_SLOTS -> 0
    }

    fun full(country: Country, key: Capacity, used: Int) = used >= capacity(country, key)

    fun add(country: Country, source: String, units: Double) {
        val rate = Research.defs.levels.sources[source]?.rate ?: return
        val total = units * rate + (country.xpFrac[source] ?: 0.0)
        val whole = floor(total).toLong()
        country.xpFrac[source] = total - whole
        Realm.dirty = true
        if (whole > 0) grant(country, source, whole)
    }

    fun grant(country: Country, source: String, xp: Long) {
        if (Research.level(country) < Research.defs.levels.xpFromLevel) return
        val config = Research.defs.levels.sources[source] ?: return
        if (country.xpDay != day()) {
            country.xpToday.clear()
            country.xpDay = day()
        }
        val used = country.xpToday[source] ?: 0
        val gained = if (config.dailyCap > 0) minOf(xp, config.dailyCap - used) else xp
        if (gained <= 0) return
        country.xpToday[source] = used + gained
        award(country, gained)
    }

    fun award(country: Country, xp: Long) {
        migrate(country)
        country.xp += xp
        Realm.dirty = true
        ResearchSync.touch(country)
        advance(country)
    }

    fun migrate(country: Country) {
        if (country.level > 0) return
        country.level = 1
        Realm.dirty = true
    }

    fun advanceAll() = Realm.data.countries.values.forEach { advance(it) }

    fun advance(country: Country) {
        migrate(country)
        val config = Research.defs.levels
        val before = country.level
        while (country.level < config.top && config.xpFor(country.level + 1) <= country.xp && config.requirements(country.level + 1).all { it.met(country) }) country.level++
        if (country.level == before) return
        Realm.dirty = true
        leveledUp(country, country.level)
    }

    private fun leveledUp(country: Country, level: Int) {
        settleRewards(country)
        Mail.broadcast(country, Phrase.of("kami_claims.level.mail.up", Words.v(level)), Tone.OK)
        if (Research.defs.levels.announce) announce(Phrase.of("kami_claims.level.broadcast", Words.v(country.name), Words.v(level)))
        Realm.changed()
        ResearchSync.touch(country)
    }
}
