package kami.economy

import kami.economy.economy.Ledger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.TagKey
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
class Delivery(val item: String = "", val qty: Int = 0, val stackData: String = "")

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
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private var file: Path? = null
    var data = Data()
    var dirty = false

    fun load(server: MinecraftServer) {
        val path = server.getWorldPath(LevelResource.ROOT).resolve("kami_economy.json")
        file = path
        data = if (Files.exists(path)) runCatching { json.decodeFromString<Data>(Files.readString(path)) }.getOrElse {
            KamiEconomy.LOG.error("Unreadable market data, kept as .bad", it)
            Files.move(path, path.resolveSibling("kami_economy.json.bad"), StandardCopyOption.REPLACE_EXISTING)
            Data()
        } else Data()
        Ledger.attach(path.resolveSibling("kami_economy.wal"))
        Ledger.recover()
        data.books.values.forEach { if (it.sells.removeAll { o -> o.synthetic }) dirty = true }
        kami.economy.economy.Stocks.index { tag ->
            BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, ResourceLocation.parse(tag))).map { BuiltInRegistries.ITEM.getKey(it.value()).toString() }
        }
    }

    fun save(force: Boolean = false) {
        val path = file ?: return
        if (!dirty && !force) return
        val tmp = path.resolveSibling("kami_economy.json.tmp")
        Files.writeString(tmp, json.encodeToString(data))
        if (Files.exists(path)) Files.copy(path, path.resolveSibling("kami_economy.json.bak"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        dirty = false
        runCatching { Ledger.compact() }.onFailure { KamiEconomy.LOG.error("Could not compact the ledger", it) }
    }

    fun book(item: String): Book = data.books.getOrPut(item) { Book(item) }

    fun nextId(): Long = data.nextOrderId++

    fun deliver(uuid: String): List<Delivery> {
        val list = data.pendingDelivery.remove(uuid) ?: return emptyList()
        dirty = true
        return list
    }

    fun queueDelivery(uuid: String, item: String, qty: Int) {
        data.pendingDelivery.getOrPut(uuid) { mutableListOf() } += Delivery(item = item, qty = qty)
        dirty = true
    }

    fun queueStackDelivery(uuid: String, stackData: String) {
        data.pendingDelivery.getOrPut(uuid) { mutableListOf() } += Delivery(stackData = stackData)
        dirty = true
    }
}
