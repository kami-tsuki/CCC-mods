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
data class LevelsConfig(
    val curve: XpCurve = XpCurve(),
    val table: List<Long> = emptyList(),
    val maxLevel: Int = 1000,
    val xpFromLevel: Int = 10,
    val sources: Map<String, XpSource> = LevelDefaults.sources,
    val counted: List<String> = LevelDefaults.counted,
    val capacities: Map<Capacity, Int> = LevelDefaults.capacities,
    val rules: Map<Int, LevelRule> = LevelDefaults.rules,
    val rewards: Map<Int, List<Unlock>> = LevelDefaults.rewards,
    val announce: Boolean = true,
    val version: Int = 0
) {
    val top get() = minOf(Limits.MAX_LEVELS, if (table.isEmpty()) max(1, maxLevel) else max(1, minOf(maxLevel, table.size + 1)))

    private val cumulative by lazy { LongArray(Limits.MAX_LEVELS + 1).also { sums -> for (level in 1..Limits.MAX_LEVELS) sums[level] = sums[level - 1] + curve.cost(level) } }

    fun curveXp(level: Int): Long = when {
        level <= 1 -> 0
        table.isNotEmpty() -> table[level - 2]
        else -> cumulative[level.coerceAtMost(Limits.MAX_LEVELS)]
    }

    fun xpFor(level: Int): Long = rules[level]?.xp ?: curveXp(level)

    fun requirements(level: Int): List<Condition> = rules[level]?.requires.orEmpty()

    fun capacity(key: Capacity) = capacities[key] ?: LevelDefaults.capacities[key] ?: 0

    private val rewardCapacity: Map<Capacity, IntArray> by lazy {
        val adds = rewards.filterKeys { it in 0..Limits.MAX_LEVELS }.flatMap { (level, list) -> list.filterIsInstance<CapacityUnlock>().map { level to it } }
        adds.groupBy({ it.second.key }, { it.first to it.second.add }).mapValues { (_, list) ->
            IntArray(Limits.MAX_LEVELS + 1).also { sums ->
                list.forEach { (level, add) -> sums[level] += add }
                for (level in 1..Limits.MAX_LEVELS) sums[level] += sums[level - 1]
            }
        }
    }

    fun rewardedCapacity(level: Int, key: Capacity): Int = rewardCapacity[key]?.get(level.coerceIn(0, Limits.MAX_LEVELS)) ?: 0

    private val featureLevels: Map<String, Int> by lazy {
        rewards.entries.sortedByDescending { it.key }.flatMap { (level, list) -> list.filterIsInstance<FeatureUnlock>().map { it.id to level } }.toMap()
    }

    fun featureLevel(id: String): Int? = featureLevels[id]
}

object Levels {
    private const val MAX_SPEED = 90
    private val blocked = HashMap<String, Int>()

    fun reset() = blocked.clear()

    var day: () -> Long = ::today
    var announce: (Phrase) -> Unit = { text ->
        ServerLifecycleHooks.getCurrentServer()?.playerList?.broadcastSystemMessage(Mail.chat.info(text.component()), false)
    }

    fun capacity(country: Country, key: Capacity): Int = Research.defs.levels.capacity(key) +
        Research.unlocks<CapacityUnlock>(country).added(key) +
        Research.defs.levels.rewardedCapacity(Research.level(country), key)

    fun researchSpeed(country: Country, online: Int): Int {
        val level = Research.level(country)
        val tiers = Research.defs.levels.rewards.filterKeys { it <= level }.values.flatten().filterIsInstance<ResearchSpeedUnlock>() + Research.unlocks<ResearchSpeedUnlock>(country)
        return tiers.maxOfOrNull { minOf(it.cap, it.perCitizen * online) }?.coerceIn(0, MAX_SPEED) ?: 0
    }

    private fun List<CapacityUnlock>.added(key: Capacity) = filter { it.key == key }.sumOf { it.add }

    fun settleRewards(country: Country) {
        val level = Research.level(country)
        if (country.rewardedLevel >= level) return
        val rewards = Research.defs.levels.rewards
        val stuck = rewards.keys.sorted().filter { it in country.rewardedLevel + 1..level }.firstOrNull { !pay(country, it, rewards.getValue(it)) }
        if (stuck == null) blocked.remove(country.id)
        country.rewardedLevel = if (stuck == null) level else stuck - 1
        Realm.dirty = true
    }

    fun forget(country: String) = blocked.remove(country)

    private fun pay(country: Country, level: Int, rewards: List<Unlock>): Boolean {
        val money = rewards.filterIsInstance<MoneyReward>().sumOf { it.amount }
        if (money > Treasury.room(country)) {
            if (blocked.put(country.id, level) != level) Mail.broadcast(country, Phrase.of("kami_claims.level.mail.reward_blocked", Words.v(level), Words.money(money)), Tone.WARN)
            return false
        }
        rewards.filterIsInstance<TokenUnlock>().forEach { Tokens.grant(country, it.id, it.count) }
        if (money > 0) Treasury.move(country, LedgerKind.LEVEL_REWARD, money, note = "level $level")
        return true
    }

    fun advanceAll() = Realm.data.countries.values.filter { it.active }.forEach(::advance)

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
        Capacity.JOB_SLOTS -> country.members.values.maxOfOrNull { it.jobs.size } ?: 0
        Capacity.MARKET_SLOTS, Capacity.AUCTION_SLOTS -> 0
    }

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
        country.xp += xp
        Realm.dirty = true
        ResearchSync.refresh(country)
        advance(country)
    }

    fun advance(country: Country) {
        val config = Research.defs.levels
        val before = country.level
        while (country.level < config.top && config.xpFor(country.level + 1) <= country.xp && config.requirements(country.level + 1).all { it.met(country) }) country.level++
        settleRewards(country)
        if (country.level == before) return
        Realm.dirty = true
        leveledUp(country)
    }

    private fun leveledUp(country: Country) {
        val level = country.level
        Mail.broadcast(country, Phrase.of("kami_claims.level.mail.up", Words.v(level)), Tone.OK)
        if (Research.defs.levels.announce) announce(Phrase.of("kami_claims.level.broadcast", Words.v(country.name), Words.v(level)))
        Realm.changed()
        ResearchSync.touch(country)
    }
}
