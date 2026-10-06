package kami.libs.economy

import kami.libs.db.Db
import kami.libs.db.Rows
import kami.libs.db.SavePoint
import kami.libs.db.Session
import kami.libs.log.Log
import kami.libs.util.Time.DAY_MS
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList

object ServerBank {
    private const val KEEP = 90 * DAY_MS
    private val log = Log.of("library")
    private val keeps = CopyOnWriteArrayList<(String) -> Boolean>()
    private val bankRow = Rows<Int>("libs_bank", listOf("id"), listOf("balance", "revision"))
    private val dayRows = Rows<Pair<Long, Flow>>("libs_bank_day", listOf("day", "flow"), listOf("inflow", "outflow"))
    private val onceRows = Rows<String>("libs_bank_once", listOf("ref"), listOf("at"))
    private val totals = HashMap<Pair<Long, Flow>, LongArray>()
    private val refs = HashMap<String, Long>()

    @Serializable
    class Day(val day: Long, val flow: Flow, val inflow: Long, val outflow: Long)

    @Serializable
    class Summary(val balance: Long, val revision: Long, val days: List<Day>)

    @Volatile
    var balance = 0L
        private set
    private var revision = 0L

    @Synchronized
    fun load() {
        val row = Db.read { one("SELECT balance, revision FROM libs_bank WHERE id = 1") { it.long("balance") to it.long("revision") } }
        balance = row?.first ?: 0L
        revision = row?.second ?: 0L
        totals.clear()
        refs.clear()
        Db.read {
            query("SELECT day, flow, inflow, outflow FROM libs_bank_day") { totals[it.long("day") to Flow.valueOf(it.string("flow"))] = longArrayOf(it.long("inflow"), it.long("outflow")) }
            query("SELECT ref, at FROM libs_bank_once") { refs[it.string("ref")] = it.long("at") }
        }
        bankRow.prime(bankRows())
        dayRows.prime(dayRows())
        onceRows.prime(onceRows())
        SavePoint.register("libs.bank", ::changes)
    }

    fun keep(predicate: (String) -> Boolean) {
        keeps += predicate
    }

    fun pay(to: Party, amount: Long, flow: Flow, note: String = "", ref: String = "", once: Boolean = false) = post(to, amount, false, flow, note, ref, once)

    fun collect(from: Party, amount: Long, flow: Flow, note: String = "", ref: String = "", once: Boolean = false) = post(from, amount, true, flow, note, ref, once)

    @Synchronized
    fun days(since: Long): List<Day> = totals.filterKeys { it.first >= since }
        .map { (k, v) -> Day(k.first, k.second, v[0], v[1]) }
        .sortedWith(compareBy({ it.day }, { it.flow.name }))

    fun summary(days: Int = 30) = Summary(balance, revision, days(System.currentTimeMillis() / DAY_MS - days + 1))

    @Synchronized
    private fun post(other: Party, amount: Long, incoming: Boolean, flow: Flow, note: String, ref: String, once: Boolean): Long {
        if (amount <= 0) return 0
        val delta = if (incoming) amount else -amount
        val next = try {
            Math.addExact(balance, delta)
        } catch (e: ArithmeticException) {
            log.error("Server bank {} of {} with {} rejected: balance {} would overflow", flow, delta, other, balance)
            return 0
        }
        val now = System.currentTimeMillis()
        if (once && ref.isNotEmpty()) {
            if (ref in refs) return 0
            refs[ref] = now
        }
        balance = next
        revision++
        val day = totals.getOrPut(now / DAY_MS to flow) { LongArray(2) }
        if (delta > 0) day[0] = sat(day[0], delta) else day[1] = sat(day[1], -delta)
        Db.write { journalLeg(now, ServerParty, delta, flow, other, note, ref) }
        return amount
    }

    @Synchronized
    private fun changes(): (Session.() -> Unit)? {
        val cut = System.currentTimeMillis() - KEEP
        refs.entries.removeIf { (ref, at) -> at < cut && keeps.none { it(ref) } }
        return SavePoint.join(listOf(bankRow.diff(bankRows()), dayRows.diff(dayRows()), onceRows.diff(onceRows())))
    }

    private fun sat(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private fun bankRows() = mapOf(1 to listOf<Any?>(1, balance, revision))

    private fun dayRows() = totals.mapValues { (k, v) -> listOf<Any?>(k.first, k.second.name, v[0], v[1]) }

    private fun onceRows() = refs.mapValues { (ref, at) -> listOf<Any?>(ref, at) }
}
