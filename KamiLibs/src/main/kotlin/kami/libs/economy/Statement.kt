package kami.libs.economy

import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.util.Time.DAY_MS
import kotlinx.serialization.Serializable
import java.util.UUID

enum class Flow {
    MARKET_BUY, MARKET_SELL, AUCTION_BUY, AUCTION_SELL, REFUND, FEE, RENT, WAGES, TREASURY, OTHER,
    SERVER_SALE, SERVER_PURCHASE, TAX, UPKEEP, CLAIM, RESEARCH, LOAN, REWARD, WAR, DISBAND, ADMIN, ROUNDING, RECOVERY,
    COIN_DEPOSIT, COIN_WITHDRAW, VENDOR, UNEXPLAINED
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

    fun record(player: UUID, amount: Long, flow: Flow, counterparty: String = "", item: String = "", qty: Int = 0) {
        if (amount == 0L) return
        val now = System.currentTimeMillis()
        val today = now / DAY_MS
        val id = player.toString()
        val other = Party.parse(counterparty)
        val note = listOfNotNull(counterparty.takeIf { other == null && it.isNotEmpty() }, item.takeIf { it.isNotEmpty() }?.let { if (qty > 0) "$qty x $it" else it }).joinToString(" | ")
        Db.write {
            journalLeg(now, PlayerParty(player), amount, flow, other, note, "")
            exec("INSERT INTO libs_statement (player, at, amount, flow, counterparty, item, qty) VALUES (?, ?, ?, ?, ?, ?, ?)", id, now, amount, flow.name, counterparty, item, qty)
            exec("DELETE FROM libs_statement WHERE player = ? AND at < ?", id, now - KEEP_MOVEMENTS * DAY_MS)
            exec("DELETE FROM libs_statement WHERE player = ? AND id NOT IN (SELECT id FROM libs_statement WHERE player = ? ORDER BY id DESC LIMIT ?)", id, id, MAX_MOVEMENTS)
            exec("DELETE FROM libs_statement_day WHERE player = ? AND day <= ?", id, today - KEEP_DAYS)
            exec(
                "INSERT INTO libs_statement_day (player, day, income, expense) VALUES (?, ?, ?, ?) ON CONFLICT(player, day) DO UPDATE SET income = income + excluded.income, expense = expense + excluded.expense",
                id, today, maxOf(amount, 0L), maxOf(-amount, 0L)
            )
        }
    }

    fun movements(player: UUID): List<Movement> = Db.read { movements(player.toString()) }

    fun days(player: UUID): List<DayTotal> = Db.read { days(player.toString()) }

    fun totals(player: UUID, days: Int): List<FlowTotal> = totals(movements(player), System.currentTimeMillis(), days)

    class Book(val movements: List<Movement>, val days: List<DayTotal>, val week: List<FlowTotal>, val month: List<FlowTotal>)

    fun book(player: UUID): Book = Db.read(fresh = false) {
        val id = player.toString()
        val list = movements(id)
        val now = System.currentTimeMillis()
        Book(list, days(id), totals(list, now, 7), totals(list, now, 30))
    }

    private fun totals(list: List<Movement>, now: Long, days: Int): List<FlowTotal> {
        val since = now - days * DAY_MS
        return list.filter { it.at >= since }.groupBy { it.flow }.map { (flow, l) ->
            FlowTotal(flow, l.filter { it.amount > 0 }.sumOf { it.amount }, -l.filter { it.amount < 0 }.sumOf { it.amount })
        }
    }

    internal fun Session.movements(player: String): List<Movement> =
        query("SELECT at, amount, flow, counterparty, item, qty FROM libs_statement WHERE player = ? ORDER BY id DESC", player) {
            Movement(it.long("at"), it.long("amount"), Flow.valueOf(it.string("flow")), it.string("counterparty"), it.string("item"), it.int("qty"))
        }

    internal fun Session.days(player: String): List<DayTotal> =
        query("SELECT day, income, expense FROM libs_statement_day WHERE player = ? ORDER BY day", player) {
            DayTotal(it.long("day"), it.long("income"), it.long("expense"))
        }
}
