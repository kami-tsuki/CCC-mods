package kami.economy

import kami.economy.economy.Blacklist
import kami.economy.economy.Ledger
import kami.economy.economy.Stocks
import kami.libs.config.WorldStore
import kotlinx.serialization.Serializable
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.TagKey
import net.minecraft.world.level.storage.LevelResource

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
    var day: Long = 0,
    val vendors: MutableList<Vendor> = mutableListOf()
)

object Market {
    private val store = WorldStore(Data.serializer(), ::Data)
    var data: Data
        get() = store.data
        set(value) { store.data = value }
    var revision = 0L
        private set
    var dirty: Boolean
        get() = store.dirty
        set(value) { if (value) { revision++; store.changed() } else store.dirty = false }

    fun load(server: MinecraftServer) {
        val path = server.getWorldPath(LevelResource.ROOT).resolve("kami_economy.json")
        store.load(path) { LOG.error("Unreadable market data, kept as .bad", it) }
        Ledger.attach(path.resolveSibling("kami_economy.wal"))
        data.books.values.forEach { if (it.sells.removeAll { o -> o.synthetic }) dirty = true }
        Stocks.index { tag ->
            BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag))).map { BuiltInRegistries.ITEM.getKey(it.value()).toString() }
        }
        data.books.keys.removeAll { k -> data.books.getValue(k).let { it.sells.isEmpty() && it.buys.isEmpty() } && Stocks.good(k) == null && !Blacklist.sellable(k) }
        Ledger.recover()
    }

    fun save(force: Boolean = false) {
        val wasDirty = dirty || force
        store.save(force)
        if (wasDirty) runCatching { Ledger.compact() }.onFailure { LOG.error("Could not compact the ledger", it) }
    }

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
