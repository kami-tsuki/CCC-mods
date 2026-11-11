package kami.claims.press

import kami.claims.Config
import kami.claims.Realm
import kami.claims.now
import kami.claims.war.Power
import kami.libs.economy.MarketApi
import kami.libs.util.Time

object NewsReports {
    private const val ROWS = 10
    private const val MANUAL_GAP_MS = 60_000L
    private val markup = Regex("[*_{}^#>\\[\\]\\n]")

    fun tick() {
        val cfg = Config.s.press
        val t = now()
        NewsDigest.flush()
        if (t / Time.DAY_MS > (NewsStore.state("pruned")?.toLongOrNull() ?: 0)) {
            News.prune()
            NewsStore.putState("pruned", (t / Time.DAY_MS).toString())
        }
        report("market", cfg.marketReport, t, ::market)
        report("ranking", cfg.rankingReport, t, ::ranking)
    }

    val NAMES = listOf("market", "ranking")

    fun runNow(name: String): Boolean {
        val t = now()
        val manual = "lastManual.$name"
        if (t - (NewsStore.state(manual)?.toLongOrNull() ?: 0) < MANUAL_GAP_MS) return false
        val (schedule, build) = when (name) {
            "market" -> Config.s.press.marketReport to ::market
            "ranking" -> Config.s.press.rankingReport to ::ranking
            else -> return false
        }
        val built = build()
        if (built != null) {
            NewsStore.putState(manual, t.toString())
            val key = "lastRun.$name"
            if (PressSchedule.due(schedule.times, NewsStore.state(key)?.toLongOrNull() ?: t, t)) NewsStore.putState(key, t.toString())
        }
        return built?.let { (template, body) -> News.system(template, emptyList(), emptyList(), body = body) } != null
    }

    private fun report(name: String, schedule: ReportSchedule, t: Long, build: () -> Pair<String, String>?) {
        val key = "lastRun.$name"
        val last = NewsStore.state(key)?.toLongOrNull()
        if (last == null) return NewsStore.putState(key, t.toString())
        if (!PressSchedule.due(schedule.times, last, t)) return
        NewsStore.putState(key, t.toString())
        if (!schedule.enabled) return
        build()?.let { (template, body) -> News.system(template, emptyList(), emptyList(), body = body) }
    }

    private fun market(): Pair<String, String>? {
        val rows = MarketApi.ticker(ROWS)
        if (rows.isEmpty()) return null
        return "marketReport" to rows.joinToString("\n") { "- **${it.name.replace(markup, "")}**: ${it.price} (${"%+.1f".format(it.changePermille / 10.0)}%)" }
    }

    private fun ranking(): Pair<String, String>? {
        val rows = Realm.data.countries.values.filter { it.active }.sortedByDescending(Power::score).take(ROWS)
        if (rows.isEmpty()) return null
        return "rankingReport" to rows.joinToString("\n") { "- **${it.name}**: level ${it.level}, ${it.members.size} members" }
    }
}
