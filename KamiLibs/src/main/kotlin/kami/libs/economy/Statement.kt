package kami.libs.economy

import kami.libs.KamiLibs
import kami.libs.config.WorldStore
import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.util.UUID

enum class Flow { MARKET_BUY, MARKET_SELL, AUCTION_BUY, AUCTION_SELL, REFUND, FEE, RENT, WAGES, TREASURY, OTHER }

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
private class Book(val entries: MutableList<Movement> = ArrayList(), val days: MutableList<DayTotal> = ArrayList())

@Serializable
private class Data(val books: MutableMap<String, Book> = HashMap())

object Statement {
    private const val DAY = 86_400_000L
    private const val KEEP_MOVEMENTS = 30
    private const val MAX_MOVEMENTS = 500
    private const val KEEP_DAYS = 90

    private val store = WorldStore(Data.serializer(), ::Data)

    @Synchronized
    fun load(server: MinecraftServer) {
        val path = server.getWorldPath(LevelResource.ROOT).resolve("kami_libs_statement.json")
        store.load(path) { KamiLibs.LOG.error("Unreadable statement data, kept as .bad", it) }
    }

    @Synchronized
    fun save() {
        store.save()
    }

    @Synchronized
    fun record(player: UUID, amount: Long, flow: Flow, counterparty: String = "", item: String = "", qty: Int = 0) {
        if (amount == 0L) return
        val now = System.currentTimeMillis()
        val today = now / DAY
        val book = store.data.books.getOrPut(player.toString(), ::Book)
        book.entries.add(0, Movement(now, amount, flow, counterparty, item, qty))
        book.entries.removeAll { it.at < now - KEEP_MOVEMENTS * DAY }
        while (book.entries.size > MAX_MOVEMENTS) book.entries.removeAt(book.entries.lastIndex)
        book.days.removeAll { it.day <= today - KEEP_DAYS }
        val last = book.days.lastOrNull()
        val income = maxOf(amount, 0L)
        val expense = maxOf(-amount, 0L)
        if (last?.day == today) book.days[book.days.lastIndex] = DayTotal(today, last.income + income, last.expense + expense)
        else book.days.add(DayTotal(today, income, expense))
        store.changed()
    }

    @Synchronized
    fun movements(player: UUID): List<Movement> = store.data.books[player.toString()]?.entries?.toList().orEmpty()

    @Synchronized
    fun days(player: UUID): List<DayTotal> = store.data.books[player.toString()]?.days?.toList().orEmpty()

    @Synchronized
    fun totals(player: UUID, days: Int): List<FlowTotal> {
        val since = System.currentTimeMillis() - days * DAY
        return movements(player).filter { it.at >= since }.groupBy { it.flow }.map { (flow, list) ->
            FlowTotal(flow, list.filter { it.amount > 0 }.sumOf { it.amount }, -list.filter { it.amount < 0 }.sumOf { it.amount })
        }
    }
}
