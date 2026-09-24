package kami.economy

import kami.economy.economy.Ledger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
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
    val synthetic: Boolean = false
)

@Serializable
class Fill(val price: Int, val qty: Int, val at: Long)

@Serializable
class Book(
    val item: String,
    val sells: MutableList<Order> = mutableListOf(),
    var lastFill: Int = 0,
    var midPrice: Double = 0.0,
    val recentFills: MutableList<Fill> = mutableListOf()
)

@Serializable
class Delivery(val item: String = "", val qty: Int = 0, val stackData: String = "")

@Serializable
class Data(
    val books: MutableMap<String, Book> = mutableMapOf(),
    var nextOrderId: Long = 1,
    val pendingDelivery: MutableMap<String, MutableList<Delivery>> = mutableMapOf(),
    val auctions: MutableList<kami.economy.economy.Auction> = mutableListOf(),
    val frozen: MutableList<Long> = mutableListOf()
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
        endlessSupply()
    }

    fun save(force: Boolean = false) {
        val path = file ?: return
        if (!dirty && !force) return
        val tmp = path.resolveSibling("kami_economy.json.tmp")
        Files.writeString(tmp, json.encodeToString(data))
        if (Files.exists(path)) Files.copy(path, path.resolveSibling("kami_economy.json.bak"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        dirty = false
    }

    fun book(item: String): Book = data.books.getOrPut(item) { Book(item) }

    fun nextId(): Long = data.nextOrderId++

    fun endlessSupply() {
        if (!Config.s.buyInfiniteEnabled) {
            data.books.values.forEach { b -> if (b.sells.removeAll { it.synthetic }) dirty = true }
            return
        }
        Config.s.endlessSupply.forEach { e ->
            val b = book(e.item)
            if (b.sells.none { it.synthetic }) {
                b.sells += Order(nextId(), "", e.floorPrice, Int.MAX_VALUE, synthetic = true)
                dirty = true
            }
        }
    }

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
