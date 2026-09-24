package kami.economy.economy

import kami.economy.Config
import kami.economy.KamiEconomy
import kami.economy.Market
import kami.economy.Order
import kami.libs.economy.Numismatics
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.core.HolderLookup
import net.minecraft.world.item.ItemStack
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

enum class IntentType { SELL, BUY, CANCEL, REPRICE, AUCTION_LIST, AUCTION_BID, AUCTION_SETTLE, AUCTION_CANCEL }
enum class LedgerState { PENDING, BOOK_INSERTED, PAID }

@Serializable
data class LedgerFill(val orderId: Long, val owner: String, val qty: Int, val unitPrice: Int)

@Serializable
data class Intent(
    val id: Long,
    val type: IntentType,
    val state: LedgerState,
    val actor: String = "",
    val item: String = "",
    val qty: Int = 0,
    val unitPrice: Int = 0,
    val taxSpurs: Int = 0,
    val netSpurs: Int = 0,
    val orderId: Long = 0,
    val fills: List<LedgerFill> = emptyList(),
    val auctionId: Long = 0,
    val stackData: String = "",
    val label: String = "",
    val seller: String = "",
    val buyNow: Int = -1,
    val prevBidder: String = "",
    val prevBid: Int = 0,
    val instant: Boolean = false
)

sealed class SellResult {
    data class Ok(val orderId: Long) : SellResult()
    object Failed : SellResult()
}

sealed class BuyResult {
    data class Ok(val filled: Int, val spent: Int) : BuyResult()
    object InsufficientFunds : BuyResult()
    object NothingAvailable : BuyResult()
}

sealed class BidResult {
    object Ok : BidResult()
    object TooLow : BidResult()
    object InsufficientFunds : BidResult()
    object NotFound : BidResult()
}

sealed class BuyNowResult {
    object Ok : BuyNowResult()
    object InsufficientFunds : BuyNowResult()
    object NotAvailable : BuyNowResult()
}

object Ledger {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var file: Path? = null

    fun attach(path: Path) {
        file = path
        if (!Files.exists(path)) Files.createFile(path)
    }

    private fun write(intent: Intent) {
        val path = file ?: return
        Files.writeString(path, json.encodeToString(intent) + "\n", StandardOpenOption.APPEND, StandardOpenOption.CREATE)
    }

    fun sell(seller: String, item: String, qty: Int, unitPrice: Int): SellResult {
        if (qty <= 0 || unitPrice <= 0) return SellResult.Failed
        val endless = Config.s.endlessByItem[item]?.takeIf { Config.s.sellInfiniteEnabled }
        val instant = endless != null
        val existingPrice = Matching.bookFor(item).sells.firstOrNull { it.owner == seller && !it.synthetic }?.price
        val price = when {
            endless != null -> (Matching.bestPrice(item) ?: endless.floorPrice).coerceAtLeast(endless.floorPrice)
            existingPrice != null -> existingPrice
            else -> unitPrice
        }
        val gross = qty * price
        val tax = (gross * Config.s.sellTaxPct).toInt()
        val net = gross - tax
        val id = Market.nextId()
        write(Intent(id, IntentType.SELL, LedgerState.PENDING, actor = seller, item = item, qty = qty, unitPrice = price, taxSpurs = tax, netSpurs = net, instant = instant))
        if (!instant) Matching.insertSell(item, Order(id, seller, price, qty))
        write(Intent(id, IntentType.SELL, LedgerState.BOOK_INSERTED, actor = seller, item = item, qty = qty, unitPrice = price, taxSpurs = tax, netSpurs = net, instant = instant))
        if (instant) Numismatics.deposit(UUID.fromString(seller), net)
        write(Intent(id, IntentType.SELL, LedgerState.PAID, actor = seller, item = item, qty = qty, unitPrice = price, taxSpurs = tax, netSpurs = net, instant = instant))
        Market.save()
        return SellResult.Ok(id)
    }

    fun buy(buyer: String, item: String, qty: Int): BuyResult {
        if (qty <= 0) return BuyResult.NothingAvailable
        val plan = Matching.plan(item, qty)
        if (plan.filled <= 0) return BuyResult.NothingAvailable
        val buyerId = UUID.fromString(buyer)
        if (Numismatics.balance(buyerId) < plan.totalSpurs) return BuyResult.InsufficientFunds
        val deducted = Numismatics.deduct(buyerId, plan.totalSpurs)
        if (deducted < plan.totalSpurs) return BuyResult.InsufficientFunds

        val id = Market.nextId()
        val fills = plan.fills.map { LedgerFill(it.orderId, it.owner, it.qty, it.unitPrice) }
        write(Intent(id, IntentType.BUY, LedgerState.PENDING, actor = buyer, item = item, qty = plan.filled, netSpurs = plan.totalSpurs, fills = fills))
        Matching.applyFills(item, plan.fills)
        Market.queueDelivery(buyer, item, plan.filled)
        write(Intent(id, IntentType.BUY, LedgerState.BOOK_INSERTED, actor = buyer, item = item, qty = plan.filled, netSpurs = plan.totalSpurs, fills = fills))
        payFills(fills)
        write(Intent(id, IntentType.BUY, LedgerState.PAID, actor = buyer, item = item, qty = plan.filled, netSpurs = plan.totalSpurs, fills = fills))
        Market.save()
        return BuyResult.Ok(plan.filled, plan.totalSpurs)
    }

    private fun netPerUnit(price: Int) = price - (price * Config.s.sellTaxPct).toInt()

    fun cancel(owner: String, item: String, orderId: Long): Order? {
        val existing = Matching.bookFor(item).sells.firstOrNull { it.id == orderId && it.owner == owner && !it.synthetic } ?: return null
        val amount = existing.amount
        val id = Market.nextId()
        write(Intent(id, IntentType.CANCEL, LedgerState.PENDING, actor = owner, item = item, qty = amount, orderId = orderId, unitPrice = existing.price))
        Matching.cancelOrder(item, orderId, owner)
        write(Intent(id, IntentType.CANCEL, LedgerState.BOOK_INSERTED, actor = owner, item = item, qty = amount, orderId = orderId, unitPrice = existing.price))
        Market.queueDelivery(owner, item, amount)
        write(Intent(id, IntentType.CANCEL, LedgerState.PAID, actor = owner, item = item, qty = amount, orderId = orderId, unitPrice = existing.price))
        Market.save()
        return existing
    }

    fun repriceAll(owner: String, item: String, newPrice: Int): Boolean {
        if (newPrice <= 0) return false
        val orders = Matching.bookFor(item).sells.filter { it.owner == owner && !it.synthetic }
        if (orders.isEmpty()) return false
        orders.forEach { reprice(owner, item, it.id, newPrice) }
        return true
    }

    private fun reprice(owner: String, item: String, orderId: Long, newPrice: Int) {
        val order = Matching.bookFor(item).sells.firstOrNull { it.id == orderId && it.owner == owner && !it.synthetic } ?: return
        if (order.price == newPrice) return
        val id = Market.nextId()
        write(Intent(id, IntentType.REPRICE, LedgerState.PENDING, actor = owner, item = item, orderId = orderId, unitPrice = newPrice))
        order.price = newPrice
        Market.dirty = true
        write(Intent(id, IntentType.REPRICE, LedgerState.PAID, actor = owner, item = item, orderId = orderId, unitPrice = newPrice))
        Market.save()
    }

    fun cancelAll(owner: String, item: String): Int {
        val ids = Matching.bookFor(item).sells.filter { it.owner == owner && !it.synthetic }.map { it.id }
        return ids.sumOf { cancel(owner, item, it)?.amount ?: 0 }
    }

    fun auctionList(seller: String, stack: ItemStack, startPrice: Int, buyNowPrice: Int?, registries: HolderLookup.Provider): Long {
        val id = Market.nextId()
        val data = StackCodec.encode(stack, registries)
        val label = stack.hoverName.string
        write(Intent(id, IntentType.AUCTION_LIST, LedgerState.PENDING, actor = seller, auctionId = id, stackData = data, label = label, unitPrice = startPrice, buyNow = buyNowPrice ?: -1))
        Auctions.insert(id, seller, data, label, startPrice, buyNowPrice)
        write(Intent(id, IntentType.AUCTION_LIST, LedgerState.PAID, actor = seller, auctionId = id, stackData = data, label = label, unitPrice = startPrice, buyNow = buyNowPrice ?: -1))
        Market.save()
        return id
    }

    fun auctionBid(bidder: String, auctionId: Long, amount: Int): BidResult {
        val a = Auctions.find(auctionId)?.takeIf { it.state == AuctionState.OPEN } ?: return BidResult.NotFound
        if (amount < Auctions.minBid(a)) return BidResult.TooLow
        val bidderId = UUID.fromString(bidder)
        if (Numismatics.balance(bidderId) < amount) return BidResult.InsufficientFunds
        if (Numismatics.deduct(bidderId, amount) < amount) return BidResult.InsufficientFunds

        val prevBidder = a.currentBidder
        val prevBid = a.currentBid
        val id = Market.nextId()
        write(Intent(id, IntentType.AUCTION_BID, LedgerState.PENDING, actor = bidder, auctionId = auctionId, unitPrice = amount, prevBidder = prevBidder, prevBid = prevBid))
        Auctions.applyBid(auctionId, bidder, amount)
        write(Intent(id, IntentType.AUCTION_BID, LedgerState.BOOK_INSERTED, actor = bidder, auctionId = auctionId, unitPrice = amount, prevBidder = prevBidder, prevBid = prevBid))
        if (prevBidder.isNotEmpty()) Numismatics.deposit(UUID.fromString(prevBidder), prevBid)
        write(Intent(id, IntentType.AUCTION_BID, LedgerState.PAID, actor = bidder, auctionId = auctionId, unitPrice = amount, prevBidder = prevBidder, prevBid = prevBid))
        Market.save()
        return BidResult.Ok
    }

    fun auctionBuyNow(buyer: String, auctionId: Long): BuyNowResult {
        val a = Auctions.find(auctionId)?.takeIf { it.state == AuctionState.OPEN } ?: return BuyNowResult.NotAvailable
        val price = a.buyNowPrice ?: return BuyNowResult.NotAvailable
        val buyerId = UUID.fromString(buyer)
        if (Numismatics.balance(buyerId) < price) return BuyNowResult.InsufficientFunds
        if (Numismatics.deduct(buyerId, price) < price) return BuyNowResult.InsufficientFunds
        if (a.currentBidder.isNotEmpty()) Numismatics.deposit(UUID.fromString(a.currentBidder), a.currentBid)
        settle(a, buyer, price)
        return BuyNowResult.Ok
    }

    fun auctionCancel(seller: String, auctionId: Long): Boolean {
        val a = Auctions.find(auctionId)?.takeIf { it.state == AuctionState.OPEN && it.seller == seller && it.currentBidder.isEmpty() } ?: return false
        val id = Market.nextId()
        write(Intent(id, IntentType.AUCTION_CANCEL, LedgerState.PENDING, actor = seller, auctionId = a.id, stackData = a.stackData))
        Auctions.cancelled(a.id)
        write(Intent(id, IntentType.AUCTION_CANCEL, LedgerState.BOOK_INSERTED, actor = seller, auctionId = a.id, stackData = a.stackData))
        Market.queueStackDelivery(seller, a.stackData)
        write(Intent(id, IntentType.AUCTION_CANCEL, LedgerState.PAID, actor = seller, auctionId = a.id, stackData = a.stackData))
        Market.save()
        return true
    }

    fun auctionSweepSettle(auctionId: Long) {
        val a = Auctions.find(auctionId) ?: return
        if (a.state != AuctionState.OPEN || a.currentBidder.isEmpty()) return
        settle(a, a.currentBidder, a.currentBid)
    }

    fun auctionSweepExpireUnsold(auctionId: Long) {
        val a = Auctions.find(auctionId) ?: return
        if (a.state != AuctionState.OPEN) return
        val id = Market.nextId()
        write(Intent(id, IntentType.AUCTION_CANCEL, LedgerState.PENDING, actor = a.seller, auctionId = a.id, stackData = a.stackData))
        Auctions.expire(a.id)
        write(Intent(id, IntentType.AUCTION_CANCEL, LedgerState.BOOK_INSERTED, actor = a.seller, auctionId = a.id, stackData = a.stackData))
        Market.queueStackDelivery(a.seller, a.stackData)
        write(Intent(id, IntentType.AUCTION_CANCEL, LedgerState.PAID, actor = a.seller, auctionId = a.id, stackData = a.stackData))
        Market.save()
    }

    private fun settle(a: Auction, buyer: String, price: Int) {
        val fee = (price * Config.s.auctionFeePct).toInt()
        val net = price - fee
        val id = Market.nextId()
        write(Intent(id, IntentType.AUCTION_SETTLE, LedgerState.PENDING, actor = buyer, seller = a.seller, auctionId = a.id, unitPrice = price, netSpurs = net, stackData = a.stackData))
        Auctions.settle(a.id, buyer, price)
        write(Intent(id, IntentType.AUCTION_SETTLE, LedgerState.BOOK_INSERTED, actor = buyer, seller = a.seller, auctionId = a.id, unitPrice = price, netSpurs = net, stackData = a.stackData))
        Market.queueStackDelivery(buyer, a.stackData)
        Numismatics.deposit(UUID.fromString(a.seller), net)
        write(Intent(id, IntentType.AUCTION_SETTLE, LedgerState.PAID, actor = buyer, seller = a.seller, auctionId = a.id, unitPrice = price, netSpurs = net, stackData = a.stackData))
        Market.save()
    }

    private fun payFills(fills: List<LedgerFill>) {
        fills.forEach { f -> if (f.owner.isNotEmpty()) Numismatics.deposit(UUID.fromString(f.owner), f.qty * netPerUnit(f.unitPrice)) }
    }

    fun recover() {
        val path = file ?: return
        if (!Files.exists(path)) return
        val lines = runCatching { Files.readAllLines(path) }.getOrElse { emptyList() }
        val latest = LinkedHashMap<Long, Intent>()
        for (line in lines) {
            if (line.isBlank()) continue
            val intent = runCatching { json.decodeFromString<Intent>(line) }.getOrNull() ?: continue
            latest[intent.id] = intent
        }
        latest.values.filter { it.state != LedgerState.PAID }.forEach(::resume)
    }

    private fun resume(intent: Intent) {
        runCatching {
            when (intent.type) {
                IntentType.SELL -> {
                    if (!intent.instant) Matching.insertSell(intent.item, Order(intent.id, intent.actor, intent.unitPrice, intent.qty))
                    else Numismatics.deposit(UUID.fromString(intent.actor), intent.netSpurs)
                    write(intent.copy(state = LedgerState.PAID))
                }
                IntentType.BUY -> {
                    if (intent.state == LedgerState.PENDING) {
                        val plan = intent.fills.map { FillLinePlan(it.orderId, it.owner, it.qty, it.unitPrice) }
                        Matching.applyFills(intent.item, plan)
                        Market.queueDelivery(intent.actor, intent.item, intent.qty)
                    }
                    payFills(intent.fills)
                    write(intent.copy(state = LedgerState.PAID))
                }
                IntentType.CANCEL -> {
                    Matching.cancelOrder(intent.item, intent.orderId, intent.actor)
                    Market.queueDelivery(intent.actor, intent.item, intent.qty)
                    write(intent.copy(state = LedgerState.PAID))
                }
                IntentType.REPRICE -> {
                    Matching.bookFor(intent.item).sells.firstOrNull { it.id == intent.orderId }?.price = intent.unitPrice
                    write(intent.copy(state = LedgerState.PAID))
                }
                IntentType.AUCTION_LIST -> {
                    Auctions.insert(intent.auctionId, intent.actor, intent.stackData, intent.label, intent.unitPrice, intent.buyNow.takeIf { it >= 0 })
                    write(intent.copy(state = LedgerState.PAID))
                }
                IntentType.AUCTION_BID -> {
                    Auctions.applyBid(intent.auctionId, intent.actor, intent.unitPrice)
                    if (intent.prevBidder.isNotEmpty()) Numismatics.deposit(UUID.fromString(intent.prevBidder), intent.prevBid)
                    write(intent.copy(state = LedgerState.PAID))
                }
                IntentType.AUCTION_SETTLE -> {
                    Auctions.settle(intent.auctionId, intent.actor, intent.unitPrice)
                    Market.queueStackDelivery(intent.actor, intent.stackData)
                    Numismatics.deposit(UUID.fromString(intent.seller), intent.netSpurs)
                    write(intent.copy(state = LedgerState.PAID))
                }
                IntentType.AUCTION_CANCEL -> {
                    Market.queueStackDelivery(intent.actor, intent.stackData)
                    write(intent.copy(state = LedgerState.PAID))
                }
            }
        }.onFailure {
            KamiEconomy.LOG.error("Failed to recover ledger intent ${intent.id}, freezing for manual resolution", it)
            Market.data.frozen += intent.id
        }
    }
}
