package kami.libs.economy

import kami.libs.KamiLibs
import kami.libs.LibConfig
import kami.libs.LibSettings
import kami.libs.admin.Admin
import kami.libs.admin.AdminMetrics
import kami.libs.admin.AdminPerms
import kami.libs.admin.Page
import kami.libs.admin.Players
import kami.libs.admin.int
import kami.libs.admin.page
import kami.libs.admin.text
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.db.Db
import kami.libs.text.Phrase
import kami.libs.util.Time
import kami.libs.util.Time.DAY_MS
import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer
import java.util.UUID

enum class CoinAlert { OK, WARNING, CRITICAL }

@Serializable
class CoinDay(val day: Long, val minted: Long, val burned: Long, val terminalIn: Long, val terminalOut: Long)

@Serializable
class CoinSourceTotal(val source: CoinSource, val minted: Long, val burned: Long)

@Serializable
class CoinSummary(
    val supply: Long, val minted: Long, val burned: Long, val terminalIn: Long, val terminalOut: Long, val today: CoinDay,
    val days: List<CoinDay>, val sources: List<CoinSourceTotal>, val alert: CoinAlert, val reason: String
)

@Serializable
class CoinPlayer(val uuid: String, val name: String, val deposited: Long, val withdrawn: Long, val last: Long, val flagged: Boolean) {
    val net get() = deposited - withdrawn
}

object CoinWatch {
    private const val CHECK_MS = 60_000L
    private const val COOLDOWN_MS = Time.HOUR_MS
    private const val WEEK = 7
    private const val MAX_DAYS = 365
    private const val CACHE_MS = 30_000L
    private const val MAX_CACHED = 64
    private const val TERMINAL_LEGS = "flow IN ('COIN_DEPOSIT', 'COIN_WITHDRAW') AND party LIKE 'p:%' AND note LIKE 'terminal%'"
    private val sorts = mapOf("net" to "net DESC", "deposited" to "dep DESC", "withdrawn" to "wd DESC", "last" to "last DESC")
    private var checked = 0L
    private var last = CoinAlert.OK
    private val sent = HashMap<CoinAlert, Long>()
    private val cache = HashMap<Pair<String, Int>, Cached>()
    @Volatile
    private var failedAt = 0L

    @JvmStatic
    fun detectorFailed(now: Long = System.currentTimeMillis()) {
        failedAt = now
    }

    private fun failing(now: Long) = now - failedAt < Time.HOUR_MS

    fun register() {
        Admin.view("coins.summary") { _, a -> Db.json.encodeToJsonElement(CoinSummary.serializer(), summary(a.int(0) ?: WEEK)) }
        Admin.view("coins.players") { ctx, a ->
            Db.json.encodeToJsonElement(Page.serializer(CoinPlayer.serializer()), players(a.text(0), a.page(1)) { Players.profile(ctx.player.server, it).name })
        }
        Admin.view("money.unexplained") { ctx, a ->
            Db.json.encodeToJsonElement(Page.serializer(UnexplainedAccount.serializer()), MoneyGuard.accounts(a.page(0)) { Players.profile(ctx.player.server, it).name })
        }
        AdminMetrics.register("kami_libs.metric.coin_alert") { status().first.ordinal.toLong() }
    }

    fun status(now: Long = System.currentTimeMillis()) = assess(Coins.snapshot(), now / DAY_MS, LibConfig.s, MoneyGuard.recent(now), failing(now))

    internal fun assess(snap: Coins.Snapshot, today: Long, s: LibSettings, unexplained: Boolean = false, failing: Boolean = false): Pair<CoinAlert, String> {
        if (snap.supply < 0) return CoinAlert.CRITICAL to reason("negative_supply")
        if (unexplained) return CoinAlert.CRITICAL to reason("unexplained")
        if (failing) return CoinAlert.WARNING to reason("detector_failing")
        val week = snap.days.filterKeys { it.second == CoinSource.TERMINAL && it.first > today - WEEK }.values
        val deposits = week.sumOf { it[1] }
        val withdrawals = week.sumOf { it[0] }
        if (deposits + withdrawals >= s.coinAlertMinVolume && deposits > s.coinAlertRatio * withdrawals) return CoinAlert.WARNING to reason("deposit_ratio")
        return CoinAlert.OK to reason("ok")
    }

    private fun reason(key: String) = "kami_libs.coins.reason.$key"

    fun summary(range: Int, now: Long = System.currentTimeMillis()): CoinSummary {
        val snap = Coins.snapshot()
        val today = now / DAY_MS
        val from = today - range.coerceIn(1, MAX_DAYS) + 1
        fun day(d: Long): CoinDay {
            val all = CoinSource.entries.mapNotNull { snap.days[d to it] }
            val t = snap.days[d to CoinSource.TERMINAL]
            return CoinDay(d, all.sumOf { it[0] }, all.sumOf { it[1] }, t?.get(1) ?: 0L, t?.get(0) ?: 0L)
        }
        val terminal = snap.days.filterKeys { it.second == CoinSource.TERMINAL }.values
        val sources = CoinSource.entries.map { src ->
            val v = snap.days.filterKeys { it.second == src && it.first >= from }.values
            CoinSourceTotal(src, v.sumOf { it[0] }, v.sumOf { it[1] })
        }.filter { it.minted + it.burned > 0 }
        val (alert, why) = assess(snap, today, LibConfig.s, MoneyGuard.recent(now), failing(now))
        return CoinSummary(snap.supply, snap.minted, snap.burned, terminal.sumOf { it[1] }, terminal.sumOf { it[0] }, day(today), (from..today).map(::day), sources, alert, why)
    }

    fun players(sort: String?, page: Int, name: (UUID) -> String = UUID::toString): Page<CoinPlayer> {
        val order = sorts[sort] ?: sorts.getValue("net")
        val p = page.coerceAtLeast(0)
        val flag = LibConfig.s.coinFlagNet
        val count = "SELECT COUNT(DISTINCT party) AS n FROM libs_journal WHERE $TERMINAL_LEGS"
        val select = "SELECT party, SUM(CASE WHEN flow = 'COIN_DEPOSIT' THEN delta ELSE 0 END) AS dep, SUM(CASE WHEN flow = 'COIN_WITHDRAW' THEN -delta ELSE 0 END) AS wd, " +
            "SUM(delta) AS net, MAX(at) AS last FROM libs_journal WHERE $TERMINAL_LEGS GROUP BY party ORDER BY $order, party LIMIT ? OFFSET ?"
        val now = System.currentTimeMillis()
        val cached = synchronized(cache) { cache[order to p]?.takeIf { now - it.at < CACHE_MS } }
        val (total, rows) = cached?.let { it.total to it.rows } ?: Db.scan {
            val total = one(count) { it.long("n") } ?: 0L
            total to query(select, Page.SIZE, p.toLong() * Page.SIZE) { Totals(it.string("party").removePrefix("p:"), it.long("dep"), it.long("wd"), it.long("last")) }
        }.also { (n, list) -> synchronized(cache) { if (cache.size >= MAX_CACHED) cache.clear(); cache[order to p] = Cached(now, n, list) } }
        return Page(rows.map { t ->
            CoinPlayer(t.id, runCatching { UUID.fromString(t.id) }.getOrNull()?.let(name) ?: t.id, t.dep, t.wd, t.last, t.dep - t.wd > flag)
        }, total, p, Page.SIZE)
    }

    private class Totals(val id: String, val dep: Long, val wd: Long, val last: Long)

    private class Cached(val at: Long, val total: Long, val rows: List<Totals>)

    internal fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (now - checked < CHECK_MS) return
        checked = now
        val (alert, why) = status(now)
        val prev = last
        last = alert
        if (alert == CoinAlert.OK || alert == prev || now - (sent[alert] ?: 0L) < COOLDOWN_MS) return
        sent[alert] = now
        val supply = Coins.supply
        KamiLibs.LOG.warn("Coin alert {}: {} (coins in the world {})", alert, why.substringAfterLast('.'), supply)
        val msg = Chat.of("library").say(
            if (alert == CoinAlert.CRITICAL) Tone.BAD else Tone.WARN,
            Phrase.of("kami_libs.coins.alert.chat", Phrase.of("kami_libs.coins.alert.${alert.name.lowercase()}"), Phrase.of(why), Phrase.money(supply))
        )
        AdminPerms.notifyManagers(server, msg)
    }
}
