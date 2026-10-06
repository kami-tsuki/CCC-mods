package kami.economy

import kami.economy.economy.Blacklist
import kami.economy.economy.Catalog
import kami.economy.economy.Ledger
import kami.economy.economy.Stocks
import kami.economy.economy.TradeStatus
import kami.economy.db.MarketTables
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
    val placedAt: Long = now(),
    val synthetic: Boolean = false,
    val lot: Int = 1
)

@Serializable
class Fill(val price: Int, val qty: Int, val at: Long)

@Serializable
class Book(
    val item: String,
    val sells: MutableList<Order> = mutableListOf(),
    val buys: MutableList<Order> = mutableListOf(),
    var lastFill: Int = 0,
    var midPrice: Double = 0.0,
    val recentFills: MutableList<Fill> = mutableListOf()
)

@Serializable
class Traded(var sold: Long = 0, var bought: Long = 0)

@Serializable
class Stock(var lots: Int, var base: Double, var observed: Double = 0.0)

@Serializable
class Vendor(
    val dim: String, val x: Int, val y: Int, val z: Int,
    var country: String = "", var owner: String = "", var item: String = "",
    var price: Int = 0, var count: Int = 1, var sell: Boolean = true, var stock: Int = -1, var seen: Long = now()
) {
    fun at(dim: String, x: Int, y: Int, z: Int) = this.dim == dim && this.x == x && this.y == y && this.z == z
}

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
    val sold: MutableMap<String, MutableMap<String, Int>> = mutableMapOf(),
    val traded: MutableMap<String, Traded> = mutableMapOf(),
    var day: Long = 0,
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
        TradeStatus.load()
        reindex()
        data.books.keys.removeAll { k -> data.books.getValue(k).let { it.sells.isEmpty() && it.buys.isEmpty() } && Stocks.good(k) == null && !Blacklist.sellable(k) }
        Ledger.recover()
        Catalog.relot()
    }

    fun unload() {
        attached = false
        Ledger.detach()
        TradeStatus.unload()
    }

    private fun resolve(tag: String): List<String> =
        BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag))).map { BuiltInRegistries.ITEM.getKey(it.value()).toString() }

    fun reindex() {
        Catalog.merge(::resolve)
        TradeStatus.reindex()
        Stocks.index({ ResourceLocation.tryParse(it)?.let(BuiltInRegistries.ITEM::containsKey) == true }, ::resolve)
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
