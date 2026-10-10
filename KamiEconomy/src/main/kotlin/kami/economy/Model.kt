package kami.economy

import kami.economy.economy.Blacklist
import kami.economy.economy.Catalog
import kami.economy.economy.Ledger
import kami.economy.economy.Stocks
import kami.economy.economy.VariantScan
import kami.economy.economy.Variants
import kami.economy.economy.TradeStatus
import kami.economy.economy.Watchlist
import kami.economy.db.MarketTables
import kami.economy.quest.Quests
import kami.libs.db.Db
import kami.libs.db.Session
import kotlinx.serialization.Serializable
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.tags.TagKey

fun now() = System.currentTimeMillis()

@Serializable
class Order(
    val id: Long,
    val owner: String,
    var price: Int,
    var amount: Int,
    var placedAt: Long = now(),
    val synthetic: Boolean = false,
    val lot: Int = 1,
    var peg: Int? = null,
    var pegLimit: Int? = null
)

@Serializable
class Fill(val price: Int, val qty: Int, val at: Long)

@Serializable
class Book(
    val item: String,
    val sells: MutableList<Order> = mutableListOf(),
    val buys: MutableList<Order> = mutableListOf(),
    var lastFill: Int = 0,
    var course: Double = 0.0,
    val recentFills: MutableList<Fill> = mutableListOf()
)

@Serializable
class Traded(var sold: Long = 0, var bought: Long = 0, var last: Long = 0)

@Serializable
class Stock(var supply: Int, var demand: Int)

@Serializable
class Sale(val at: Long, val lots: Int)

@Serializable
class Vendor(
    val dim: String, val x: Int, val y: Int, val z: Int,
    var country: String = "", var owner: String = "", var item: String = "",
    var price: Int = 0, var count: Int = 1, var sell: Boolean = true, var stock: Int = -1, var seen: Long = now()
)

@Serializable
class Delivery(val item: String = "", val qty: Int = 0, val stackData: String = "", val id: String = "", val parked: Boolean = false)

@Serializable
class Data(
    val books: MutableMap<String, Book> = mutableMapOf(),
    var nextOrderId: Long = 1,
    val pendingDelivery: MutableMap<String, MutableList<Delivery>> = mutableMapOf(),
    val auctions: MutableList<kami.economy.economy.Auction> = mutableListOf(),
    val frozen: MutableList<Long> = mutableListOf(),
    val stocks: MutableMap<String, Stock> = mutableMapOf(),
    val sold: MutableMap<String, MutableMap<String, MutableList<Sale>>> = mutableMapOf(),
    val traded: MutableMap<String, Traded> = mutableMapOf(),
    val vendors: MutableList<Vendor> = mutableListOf(),
    val muted: MutableMap<String, MutableSet<String>> = mutableMapOf(),
    var version: Int = 0
)

object Market {
    var data = Data()
    var revision = 0L
        private set
    var dirty = false
        set(value) { if (value) revision++; field = value }
    private var attached = false

    fun load() {
        data = Db.read { MarketTables.read(this) }
        MarketTables.all.forEach { it.prime(data) }
        dirty = false
        attached = true
        Ledger.attach()
        data.books.values.forEach { if (it.sells.removeAll { o -> o.synthetic }) dirty = true }
        runCatching { Quests.load() }.onFailure { LOG.error("Quests could not be loaded", it) }
        TradeStatus.load()
        Watchlist.load()
        reindex()
        data.books.keys.removeAll { k -> data.books.getValue(k).let { it.sells.isEmpty() && it.buys.isEmpty() } && Stocks.good(k) == null && !Blacklist.sellable(k) }
        Ledger.recover()
        foldVariants()
    }

    fun unload() {
        attached = false
        Ledger.detach()
        Quests.unload()
        TradeStatus.unload()
        Watchlist.unload()
    }

    private fun resolve(tag: String): List<String> =
        BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag))).map { BuiltInRegistries.ITEM.getKey(it.value()).toString() }

    fun starterIds(): Set<String> = Config.s.starterGoods.flatMapTo(HashSet()) { if (it.item.startsWith("#")) resolve(it.item.drop(1)) else listOf(it.item) }

    fun variantsChanged() = reindex()

    private fun foldVariants() {
        if (!attached) return
        Variants.map.forEach { (id, v) ->
            data.books[id]?.let { b ->
                b.sells.filter { !it.synthetic && it.owner.isNotEmpty() }.forEach { Ledger.cancelAsServer("sell", id, it.id, "variant") }
                b.buys.filter { !it.synthetic && it.owner.isNotEmpty() }.forEach { Ledger.cancelAsServer("buy", id, it.id, "variant") }
                data.books.remove(id)
                dirty = true
            }
            data.traded.remove(id)?.let { t ->
                val core = data.traded.getOrPut(v.core) { Traded() }
                core.sold += t.sold * v.mul / v.div
                core.bought += t.bought * v.mul / v.div
                core.last = maxOf(core.last, t.last)
                dirty = true
            }
            data.stocks.remove(id)?.let { s ->
                dirty = true
                val core = Stocks.stock(v.core) ?: return@let
                val g = Stocks.configured(id) ?: return@let
                val coreLot = Config.s.lotOf(v.core).coerceAtLeast(1)
                fun fold(cur: Int, live: Int, base: Int): Int {
                    if (cur < 0 || live < 0 || base < 0) return cur
                    val lots = (live.toLong() - base) * g.lot * v.mul / v.div / coreLot
                    return (cur + lots).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                }
                core.supply = fold(core.supply, s.supply, g.supply)
                core.demand = fold(core.demand, s.demand, g.demand)
            }
        }
        Watchlist.canonicalize()
    }

    fun reindex() {
        Catalog.merge(::resolve)
        VariantScan.apply()
        TradeStatus.reindex()
        Stocks.index({ ResourceLocation.tryParse(it)?.let(BuiltInRegistries.ITEM::containsKey) == true }, ::resolve)
        foldVariants()
    }

    internal fun changes(): List<(Session.() -> Unit)?> = if (attached) MarketTables.all.map { it.sync(data) } else emptyList()

    fun book(item: String): Book = data.books.getOrPut(item) { Book(item) }

    fun peek(item: String): Book? = data.books[item]

    fun nextId(): Long = data.nextOrderId++

    fun deliver(uuid: String, includeParked: Boolean = false): List<Delivery> {
        val all = data.pendingDelivery[uuid] ?: return emptyList()
        val (take, keep) = all.partition { includeParked || !it.parked }
        if (take.isEmpty()) return emptyList()
        if (keep.isEmpty()) data.pendingDelivery.remove(uuid) else data.pendingDelivery[uuid] = keep.toMutableList()
        dirty = true
        return take
    }

    fun queued(uuid: String, id: String): Boolean = data.pendingDelivery[uuid]?.any { it.id == id } == true

    fun queueDelivery(uuid: String, item: String, qty: Int, id: String = "", parked: Boolean = false) {
        val list = data.pendingDelivery.getOrPut(uuid) { mutableListOf() }
        if (id.isNotEmpty() && list.any { it.id == id }) return
        list += Delivery(item = item, qty = qty, id = id, parked = parked)
        dirty = true
    }

    fun queueStackDelivery(uuid: String, stackData: String, id: String = "", parked: Boolean = false) {
        val list = data.pendingDelivery.getOrPut(uuid) { mutableListOf() }
        if (id.isNotEmpty() && list.any { it.id == id }) return
        list += Delivery(stackData = stackData, id = id, parked = parked)
        dirty = true
    }

    fun freeze(id: Long) {
        if (id !in data.frozen) {
            data.frozen += id
            dirty = true
        }
    }
}
