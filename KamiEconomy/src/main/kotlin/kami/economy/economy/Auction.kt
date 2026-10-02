package kami.economy.economy

import kami.economy.Config
import kami.economy.Market
import kami.economy.now
import kotlinx.serialization.Serializable
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.item.ItemStack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit

enum class AuctionState { OPEN, SOLD, EXPIRED, CANCELLED }

@Serializable
class Auction(
    val id: Long, val seller: String, val stackData: String, val label: String,
    val startPrice: Int, val buyNowPrice: Int?, var currentBid: Int, var currentBidder: String,
    val expiresAt: Long, var state: AuctionState
)

object StackCodec {
    fun encode(stack: ItemStack, registries: HolderLookup.Provider): String {
        val tag = stack.save(registries) as CompoundTag
        val out = ByteArrayOutputStream()
        NbtIo.writeCompressed(tag, out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    fun decode(data: String, registries: HolderLookup.Provider): ItemStack {
        if (data.isEmpty()) return ItemStack.EMPTY
        val bytes = Base64.getDecoder().decode(data)
        val tag = NbtIo.readCompressed(ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap())
        return ItemStack.parse(registries, tag).orElse(ItemStack.EMPTY)
    }

    fun count(data: String): Int = runCatching {
        NbtIo.readCompressed(ByteArrayInputStream(Base64.getDecoder().decode(data)), NbtAccounter.unlimitedHeap()).getInt("count")
    }.getOrDefault(1).coerceAtLeast(1)

    fun itemId(data: String): String = runCatching {
        NbtIo.readCompressed(ByteArrayInputStream(Base64.getDecoder().decode(data)), NbtAccounter.unlimitedHeap()).getString("id")
    }.getOrDefault("")
}

object Auctions {
    private val RETENTION_MILLIS = TimeUnit.DAYS.toMillis(7)

    fun minBid(a: Auction) = maxOf(a.startPrice, a.currentBid + 1)

    fun open(): List<Auction> = Market.data.auctions.filter { it.state == AuctionState.OPEN }

    fun find(id: Long) = Market.data.auctions.firstOrNull { it.id == id }

    fun insert(id: Long, seller: String, stackData: String, label: String, startPrice: Int, buyNowPrice: Int?) {
        if (Market.data.auctions.any { it.id == id }) return
        Market.data.auctions += Auction(id, seller, stackData, label, startPrice, buyNowPrice, 0, "", now() + Config.s.auctionDurationMillis, AuctionState.OPEN)
        Market.dirty = true
    }

    fun applyBid(id: Long, bidder: String, amount: Int) {
        val a = find(id) ?: return
        a.currentBid = amount
        a.currentBidder = bidder
        Market.dirty = true
    }

    fun settle(id: Long, buyer: String, price: Int) {
        val a = find(id) ?: return
        a.state = AuctionState.SOLD
        a.currentBid = price
        a.currentBidder = buyer
        Market.dirty = true
    }

    fun expire(id: Long) {
        find(id)?.state = AuctionState.EXPIRED
        Market.dirty = true
    }

    fun cancelled(id: Long) {
        find(id)?.state = AuctionState.CANCELLED
        Market.dirty = true
    }

    fun sweep() {
        val t = now()
        open().filter { it.expiresAt <= t }.forEach { a ->
            if (a.currentBidder.isNotEmpty()) Ledger.auctionSweepSettle(a.id) else Ledger.auctionSweepExpireUnsold(a.id)
        }
        if (Market.data.auctions.removeAll { it.state != AuctionState.OPEN && it.expiresAt < t - RETENTION_MILLIS }) Market.dirty = true
    }
}
