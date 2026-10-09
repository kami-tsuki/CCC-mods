package kami.libs.economy

import kami.libs.admin.Admin
import kami.libs.admin.AdminMetrics
import kami.libs.admin.Players
import kami.libs.db.Db
import kami.libs.db.Migration
import kami.libs.db.Rows
import kami.libs.db.SavePoint
import kami.libs.db.Session
import kami.libs.util.Time.DAY_MS
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

@Serializable
class WealthDay(
    val day: Long,
    val players: Long,
    val countries: Long,
    val countriesNegative: Long,
    val escrow: Long,
    val bank: Long,
    val playerCount: Int,
    val giniPlayers: Double,
    val giniCountries: Double,
    val top10Share: Double,
    val median: Long,
    val brackets: List<Wealth.Bracket>,
    val top: List<Wealth.Holder>,
    val minted: Map<Flow, Long>,
    val voided: Map<Flow, Long>,
    val coins: Long = 0
)

object Wealth {
    @Serializable
    class Bracket(val min: Long, val max: Long, val count: Int)

    @Serializable
    class Holder(val party: String, val name: String = "", val amount: Long)

    private class Snap(val value: WealthDay, val data: String)

    private const val KEEP = 365
    private const val LIVE_MS = 10_000L
    private const val TOP = 10
    private val sources = ConcurrentHashMap<String, () -> Long>()
    private val distributions = ConcurrentHashMap<String, () -> List<Holder>>()
    private val rows = Rows<Long>("libs_wealth_day", listOf("day"), listOf("data"))
    private val stored = TreeMap<Long, Snap>()
    private var checked = -1L
    private var cached: WealthDay? = null
    private var cachedAt = 0L

    internal val migration = Migration("libs", 4, "wealth", ddl = listOf("CREATE TABLE libs_wealth_day (day INTEGER PRIMARY KEY, data TEXT NOT NULL)"))

    fun source(name: String, value: () -> Long) {
        sources[name] = value
    }

    fun distribution(name: String, values: () -> List<Holder>) {
        distributions[name] = values
    }

    fun snapshot(day: Long = System.currentTimeMillis() / DAY_MS): WealthDay {
        val players = holders("players")
        val countries = holders("countries")
        val p = players.map { it.amount }
        val c = countries.map { it.amount }
        val values = sources.toMap().mapValues { runCatching { it.value() }.getOrDefault(0L) }
        val flows = ServerBank.days(day).filter { it.day == day }
        return WealthDay(
            day, total(p), total(c.filter { it > 0 }), total(c.filter { it < 0 }),
            total(values.filterKeys { it.startsWith("escrow.") }.values), values["bank"] ?: 0L,
            players.size, gini(p), gini(c), topShare(p), median(p), brackets(p), top(players + countries),
            flows.filter { it.outflow > 0 }.associate { it.flow to it.outflow }, flows.filter { it.inflow > 0 }.associate { it.flow to it.inflow },
            values["coins"] ?: 0L
        )
    }

    @Synchronized
    fun live(): WealthDay {
        val now = System.currentTimeMillis()
        cached?.takeIf { now - cachedAt < LIVE_MS }?.let { return it }
        return snapshot(now / DAY_MS).also { cached = it; cachedAt = now }
    }

    @Synchronized
    fun history(days: Int): List<WealthDay> = stored.tailMap(System.currentTimeMillis() / DAY_MS - days.coerceIn(1, KEEP)).values.map { it.value }

    fun record(day: Long) {
        val value = snapshot(day)
        val snap = Snap(value, Db.json.encodeToString(WealthDay.serializer(), value))
        synchronized(this) {
            stored[day] = snap
            stored.headMap(day - KEEP).clear()
        }
    }

    internal fun builtins() {
        distribution("players") { Numismatics.balances().map { Holder(PlayerParty(it.first).key, amount = it.second) } }
        source("bank") { ServerBank.balance }
        source("coins") { Coins.supply }
        AdminMetrics.register("kami_libs.metric.coins") { Coins.supply }
        Admin.view("wealth.live") { _, _ -> Db.encode(WealthDay.serializer(), live()) }
        Admin.view("wealth.history") { _, a ->
            Db.encode(ListSerializer(WealthDay.serializer()), history(a.firstOrNull()?.trim()?.toIntOrNull() ?: 30))
        }
    }

    @Synchronized
    internal fun load() {
        checked = -1L
        cached = null
        stored.clear()
        Db.read { query("SELECT day, data FROM libs_wealth_day") { it.long("day") to it.string("data") } }
            .forEach { (day, data) -> stored[day] = Snap(Db.json.decodeFromString(WealthDay.serializer(), data), data) }
        rows.prime(rowsOf())
        SavePoint.register("libs.wealth", ::changes)
    }

    @Synchronized
    private fun changes(): (Session.() -> Unit)? = rows.diff(rowsOf())

    private fun rowsOf() = stored.mapValues { (day, s) -> listOf<Any?>(day, s.data) }

    internal fun tick(now: Long = System.currentTimeMillis()) {
        val day = now / DAY_MS
        if (day == checked) return
        checked = day
        if (synchronized(this) { day - 1 !in stored }) record(day - 1)
    }

    internal fun total(values: Iterable<Long>) = values.fold(0L) { a, b ->
        val r = a + b
        if (((a xor r) and (b xor r)) < 0) (if (b > 0) Long.MAX_VALUE else Long.MIN_VALUE) else r
    }

    internal fun gini(values: List<Long>): Double {
        val v = values.map { maxOf(it, 0L).toDouble() }.sorted()
        val total = v.sum()
        if (total <= 0.0) return 0.0
        val weighted = v.withIndex().sumOf { (it.index + 1) * it.value }
        return 2 * weighted / (v.size * total) - (v.size + 1.0) / v.size
    }

    internal fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val v = values.sorted()
        val m = v.size / 2
        return if (v.size % 2 == 1) v[m] else v[m - 1] + (v[m] - v[m - 1]) / 2
    }

    internal fun brackets(values: List<Long>): List<Bracket> {
        val counts = IntArray(20)
        values.forEach { counts[if (it <= 0) 0 else it.toString().length]++ }
        return (0..counts.indexOfLast { it > 0 }).map { i ->
            if (i == 0) Bracket(0, 0, counts[0]) else Bracket(ten(i - 1), if (i >= 19) Long.MAX_VALUE else ten(i) - 1, counts[i])
        }
    }

    private fun ten(k: Int) = (1..k).fold(1L) { a, _ -> a * 10 }

    private fun topShare(values: List<Long>): Double {
        val v = values.filter { it > 0 }
        val sum = total(v)
        return if (sum <= 0) 0.0 else total(v.sortedDescending().take(TOP)).toDouble() / sum
    }

    private fun holders(name: String) = runCatching { distributions[name]?.invoke() }.getOrNull().orEmpty()

    private fun top(all: List<Holder>) = all.sortedByDescending { it.amount }.take(TOP).map { h ->
        if (h.name.isNotEmpty()) h else Holder(h.party, name(h.party), h.amount)
    }

    private fun name(party: String): String = (Party.parse(party) as? PlayerParty)?.let { p ->
        ServerLifecycleHooks.getCurrentServer()?.let { Players.name(it, p.uuid) }
    } ?: party
}
