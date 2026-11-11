package kami.claims.press

import kami.libs.press.Cost

object PressRules {
    const val UNIT = '\u001F'
    const val PAPER = "minecraft:paper"
    const val INK = "minecraft:ink_sac"
    private const val MAX_CONTRIBUTORS = 32

    enum class Lock { FREE, MINE, OTHER }

    fun capFor(cfg: PressSettings, country: String) = cfg.perCountryCap[country] ?: cfg.dailyCap

    fun capReached(published: Int, cap: Int) = published >= cap

    fun slotsFull(drafts: Int, slots: Int) = drafts >= slots

    fun lockState(holder: String?, until: Long, player: String, now: Long) = when {
        holder == null || until <= now -> Lock.FREE
        holder == player -> Lock.MINE
        else -> Lock.OTHER
    }

    fun lockExpiry(now: Long, minutes: Int) = now + minutes * 60_000L

    fun savedSinceLock(updated: Long, lockSince: Long) = updated >= lockSince

    fun mayTakeOver(state: Lock, savedSinceLock: Boolean, force: Boolean) = state != Lock.OTHER || force && savedSinceLock

    fun autosaveAllowed(updated: Long, now: Long, minSeconds: Int, auto: Boolean) = !auto || now - updated >= minSeconds * 1000L

    fun contributors(current: List<String>, player: String, changed: Boolean) =
        if (changed && player !in current && current.size < MAX_CONTRIBUTORS) current + player else current

    fun authors(contributors: List<String>, publisher: String) = (contributors + publisher).distinct()

    fun hourlyFull(recent: Int, limit: Int) = recent >= limit

    fun warAllowed(perWar: Int, perDay: Int, cfg: PressSettings) = perWar < cfg.warUpdatesPerWar && perDay < cfg.warUpdatesPerDay

    fun warKey(a: String, b: String) = listOf(a, b).sorted().joinToString("+")

    fun need(cost: Cost): Map<String, Int> =
        buildMap { put(PAPER, cost.paper); put(INK, cost.ink); cost.dyes.forEach { (dye, n) -> merge(dye.item, n, Int::plus) } }.filterValues { it > 0 }

    fun missing(need: Map<String, Int>, have: Map<String, Int>): Map<String, Int> =
        need.mapValues { (item, n) -> n - (have[item] ?: 0) }.filterValues { it > 0 }
}
