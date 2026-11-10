package kami.libs.economy

import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.util.Time.DAY_MS
import kotlinx.serialization.Serializable
import java.util.UUID

enum class Flow {
    MARKET_BUY, MARKET_SELL, AUCTION_BUY, AUCTION_SELL, REFUND, FEE, RENT, WAGES, TREASURY, OTHER,
    SERVER_SALE, SERVER_PURCHASE, TAX, UPKEEP, CLAIM, RESEARCH, LOAN, REWARD, WAR, DISBAND, ADMIN, ROUNDING, RECOVERY,
    COIN_DEPOSIT, COIN_WITHDRAW, VENDOR, UNEXPLAINED, TRANSFER, QUEST, VILLAGER;

    companion object {
        fun named(name: String) = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

@Serializable
class Movement(val at: Long, val amount: Long, val flow: Flow, val counterparty: String = "", val item: String = "", val qty: Int = 0)

@Serializable
class DayTotal(val day: Long, val income: Long, val expense: Long)

@Serializable
class FlowTotal(val flow: Flow, val income: Long, val expense: Long)

@Serializable
class Account(
    val balance: Long,
    val playtime: Long,
    val kills: Int,
    val deaths: Int,
    val country: String? = null,
    val rank: String? = null,
    val movements: List<Movement> = emptyList(),
    val days: List<DayTotal> = emptyList(),
    val week: List<FlowTotal> = emptyList(),
    val month: List<FlowTotal> = emptyList()
)

@Serializable
internal class StatementBook(val entries: MutableList<Movement> = ArrayList(), val days: MutableList<DayTotal> = ArrayList())

@Serializable
internal class StatementData(val books: MutableMap<String, StatementBook> = HashMap())

object Statement {
    private const val KEEP_MOVEMENTS = 30
    private const val MAX_MOVEMENTS = 500
    private const val KEEP_DAYS = 90
    private const val TRIM_EVERY = 25
    private val recorded = java.util.concurrent.ConcurrentHashMap<String, Int>()


    const val NOTE = "note:"

    fun note(key: String, vararg args: Any) = if (key.isEmpty()) "" else NOTE + (listOf(key) + args.map { it.toString().replace("|", "/") }).joinToString("|")

    fun record(player: UUID, amount: Long, flow: Flow, counterparty: String = "", item: String = "", qty: Int = 0) {
        if (amount == 0L) return
        val now = System.currentTimeMillis()
        val today = now / DAY_MS
        val id = player.toString()
        val other = Party.parse(counterparty)
        val note = if (item.startsWith(NOTE)) item else listOfNotNull(counterparty.takeIf { other == null && it.isNotEmpty() }, item.takeIf { it.isNotEmpty() }?.let { if (qty > 0) "$qty x $it" else it }).joinToString(" | ")
        val trim = recorded.compute(id) { _, a -> if (a == null || a + 1 >= TRIM_EVERY) 0 else a + 1 } == 0
        countMoney(PlayerParty(player), amount, flow)
        Db.write {
            journalLeg(now, PlayerParty(player), amount, flow, other, note, "")
            exec("INSERT INTO libs_statement (player, at, amount, flow, counterparty, item, qty) VALUES (?, ?, ?, ?, ?, ?, ?)", id, now, amount, flow.name, counterparty, item, qty)
            if (trim) {
                exec("DELETE FROM libs_statement WHERE player = ? AND at < ?", id, now - KEEP_MOVEMENTS * DAY_MS)
                exec("DELETE FROM libs_statement WHERE player = ? AND id NOT IN (SELECT id FROM libs_statement WHERE player = ? ORDER BY id DESC LIMIT ?)", id, id, MAX_MOVEMENTS)
                exec("DELETE FROM libs_statement_day WHERE player = ? AND day <= ?", id, today - KEEP_DAYS)
            }
            exec(
                "INSERT INTO libs_statement_day (player, day, income, expense) VALUES (?, ?, ?, ?) ON CONFLICT(player, day) DO UPDATE SET income = income + excluded.income, expense = expense + excluded.expense",
                id, today, maxOf(amount, 0L), maxOf(-amount, 0L)
            )
        }
    }

    fun movements(player: UUID): List<Movement> = Db.read { movements(player.toString()) }

    fun days(player: UUID): List<DayTotal> = Db.read { days(player.toString()) }

    fun net(player: UUID, vararg days: Int): List<Long> = Db.read {
        val id = player.toString()
        val today = System.currentTimeMillis() / DAY_MS
        days.map { d -> one("SELECT TOTAL(income - expense) v FROM libs_statement_day WHERE player = ? AND day > ?", id, today - d) { it.long("v") } ?: 0L }
    }

    fun totals(player: UUID, days: Int): List<FlowTotal> = Db.read { totals(PlayerParty(player).key, System.currentTimeMillis(), days) }

    class Book(val movements: List<Movement>, val days: List<DayTotal>, val week: List<FlowTotal>, val month: List<FlowTotal>)

    fun book(player: UUID): Book = Db.read {
        val id = player.toString()
        val key = PlayerParty(player).key
        val now = System.currentTimeMillis()
        Book(movements(id), days(id), totals(key, now, 7), totals(key, now, 30))
    }

    private fun Session.totals(party: String, now: Long, days: Int): List<FlowTotal> =
        query(
            "SELECT flow, TOTAL(CASE WHEN delta > 0 THEN delta ELSE 0 END) AS i, TOTAL(CASE WHEN delta < 0 THEN -delta ELSE 0 END) AS e FROM libs_journal WHERE party = ? AND at >= ? AND flow NOT IN ('UNEXPLAINED', 'ROUNDING', 'RECOVERY') GROUP BY flow ORDER BY MAX(id) DESC",
            party, now - days * DAY_MS
        ) { FlowTotal(Flow.named(it.string("flow")), it.long("i"), it.long("e")) }

    internal fun Session.movements(player: String): List<Movement> =
        query("SELECT at, amount, flow, counterparty, item, qty FROM libs_statement WHERE player = ? ORDER BY id DESC LIMIT ?", player, MAX_MOVEMENTS) {
            Movement(it.long("at"), it.long("amount"), Flow.named(it.string("flow")), it.string("counterparty"), it.string("item"), it.int("qty"))
        }

    internal fun Session.days(player: String): List<DayTotal> =
        query("SELECT day, income, expense FROM libs_statement_day WHERE player = ? ORDER BY day", player) {
            DayTotal(it.long("day"), it.long("income"), it.long("expense"))
        }
}
