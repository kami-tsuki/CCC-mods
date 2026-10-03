package kami.economy.economy

import kami.economy.LOG
import kami.economy.Config
import kami.economy.Market
import kami.economy.Order
import kami.economy.now
import kami.libs.chat.Tone
import kami.libs.claims.ClaimsApi
import kami.libs.economy.CleanStep
import kami.libs.economy.Money
import kami.libs.economy.Numismatics
import kami.libs.progress.KamiProgress
import kami.libs.text.Phrase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.core.HolderLookup
import net.minecraft.world.item.ItemStack
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

enum class IntentType { SELL, BUY, CANCEL, REPRICE, AUCTION_LIST, AUCTION_BID, AUCTION_SETTLE, AUCTION_CANCEL, BID, BID_CANCEL, SELL_NOW, REPRICE_BID }
enum class LedgerState { PENDING, DEBITED, BOOK_INSERTED, PAID, VOID }

interface Wallet {
    fun balance(id: UUID): Long
    fun deduct(id: UUID, amount: Int): Int
    fun deposit(id: UUID, amount: Int): Boolean
}

object NumismaticsWallet : Wallet {
    override fun balance(id: UUID) = Numismatics.balance(id)
    override fun deduct(id: UUID, amount: Int) = Numismatics.deduct(id, amount)
    override fun deposit(id: UUID, amount: Int) = Numismatics.deposit(id, amount)
}

@Serializable
data class LedgerFill(val orderId: Long, val owner: String, val qty: Int, val unitPrice: Int, val lot: Int = 1, val tax: Long = -1, val tariff: Long = 0, val pre: Long = -1)

@Serializable
data class Intent(
    val id: Long,
    val type: IntentType,
    val state: LedgerState,
    val actor: String = "",
    val item: String = "",
    val qty: Int = 0,
    val unitPrice: Int = 0,
    val taxSpurs: Long = 0,
    val netSpurs: Long = 0,
    val orderId: Long = 0,
    val fills: List<LedgerFill> = emptyList(),
    val auctionId: Long = 0,
    val stackData: String = "",
    val label: String = "",
    val seller: String = "",
    val buyNow: Int = -1,
    val prevBidder: String = "",
    val prevBid: Int = 0,
    val instant: Boolean = false,
    val paid: List<Long> = emptyList(),
    val lot: Int = 1,
    val capLots: Int = 0,
    val preBalance: Long = -1,
    val prevBalance: Long = -1,
    val sellerBalance: Long = -1,
    val tariff: Long = 0
)

class IntentOpenException : RuntimeException()

sealed class SellResult {
    data class Ok(val orderId: Long, val filled: Int = 0, val listed: Int = 0, val limited: Boolean = false) : SellResult()
    data class Sold(val filled: Int, val net: Long) : SellResult()
    object NoBuyers : SellResult()
    object Failed : SellResult()
    data class NotClean(val step: Int) : SellResult()
    object Full : SellResult()
    data class IntentOpen(val kept: Int) : SellResult()
    data class Limit(val max: Int) : SellResult()
}

sealed class BuyResult {
    data class Ok(val filled: Int, val spent: Long) : BuyResult()
    object OutOfRange : BuyResult()
    object InsufficientFunds : BuyResult()
    object NothingAvailable : BuyResult()
}

sealed class OrderResult {
    data class Ok(val orderId: Long, val qty: Int = 0) : OrderResult()
    object OutOfRange : OrderResult()
    data class NotClean(val step: Int) : OrderResult()
    object InsufficientFunds : OrderResult()
    object Crosses : OrderResult()
    object Exists : OrderResult()
    data class Limit(val max: Int) : OrderResult()
}

sealed class ListResult {
    data class Ok(val id: Long) : ListResult()
    data class Limit(val max: Int) : ListResult()
}

sealed class BidResult {
    object Ok : BidResult()
    object TooLow : BidResult()
    object InsufficientFunds : BidResult()
    object NotFound : BidResult()
    data class Embargoed(val country: String) : BidResult()
}

sealed class BuyNowResult {
    object Ok : BuyNowResult()
    object InsufficientFunds : BuyNowResult()
    object NotAvailable : BuyNowResult()
    data class Embargoed(val country: String) : BuyNowResult()
}

object Ledger {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var file: Path? = null
    private val open = LinkedHashMap<Long, Intent>()
    var wallet: Wallet = NumismaticsWallet

    fun attach(path: Path) {
        file = path
        open.clear()
        if (!Files.exists(path)) Files.createFile(path)
    }

    private fun FillLinePlan.toLedger() = LedgerFill(orderId, owner, qty, unitPrice, lot, tax, tariff)

    fun close(id: Long): Boolean {
        val intent = open[id] ?: return false
        LOG.warn("Admin voided open intent {} ({} {} actor={} item={} qty={}), no refund", id, intent.type, intent.state, intent.actor, intent.item, intent.qty)
        write(intent.copy(state = LedgerState.VOID))
        return true
    }

    private fun returnToOwner(base: Intent, mutate: () -> Unit, deliver: () -> Unit) {
        write(base)
        mutate()
        write(base.copy(state = LedgerState.BOOK_INSERTED))
        deliver()
        write(base.copy(state = LedgerState.PAID))
    }

    private fun write(intent: Intent) {
        if (intent.state == LedgerState.PAID || intent.state == LedgerState.VOID) open.remove(intent.id) else open[intent.id] = intent
        val path = file ?: return
        Files.writeString(path, json.encodeToString(intent) + "\n", StandardOpenOption.APPEND, StandardOpenOption.CREATE, StandardOpenOption.DSYNC)
    }

    fun compact() {
        val path = file ?: return
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, open.values.joinToString("") { json.encodeToString(it) + "\n" })
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun credit(owner: String, amount: Long): Boolean {
        if (amount == 0L) return true
        val spurs = Money.spurs(amount)
        if (spurs != null && wallet.deposit(UUID.fromString(owner), spurs)) return true
        LOG.error("Could not credit {} spurs to {}", amount, owner)
        return false
    }

    private fun debit(intent: Intent, payer: UUID, amount: Int): Boolean {
        val staged = intent.copy(preBalance = wallet.balance(payer))
        write(staged)
        val taken = wallet.deduct(payer, amount)
        if (taken >= amount) {
            write(staged.copy(state = LedgerState.DEBITED))
            return true
        }
        if (taken > 0) wallet.deposit(payer, taken)
        write(staged.copy(state = LedgerState.VOID))
        return false
    }

    private fun resumeDebited(intent: Intent, amount: Int): Intent? {
        if (amount <= 0 || intent.preBalance < 0) return null
        val payer = Trade.uuid(intent.actor) ?: return null
        val diff = intent.preBalance - wallet.balance(payer)
        return when {
            diff <= 0 -> null
            diff >= amount -> { if (diff > amount) wallet.deposit(payer, (diff - amount).toInt()); intent.copy(state = LedgerState.DEBITED) }
            else -> { wallet.deposit(payer, diff.toInt()); null }
        }
    }

    internal fun earnsXp(player: String, counterparty: String): Boolean {
        val country = Trade.country(player) ?: return false
        val other = Trade.country(counterparty) ?: return true
        return ClaimsApi.neutralForeign(country, other)
    }

    private fun traded(player: String, counterparty: String, spurs: Long, item: String, qty: Int) {
        if (spurs <= 0 || qty <= 0 || !earnsXp(player, counterparty)) return
        val country = Trade.country(player) ?: return
        KamiProgress.post(country, "trade", item, qty.toLong())
        KamiProgress.post(country, "trade_value", item, spurs)
    }

    private fun tradedFills(actor: String, item: String, fills: List<LedgerFill>, actorSpurs: (LedgerFill) -> Long) {
        fills.forEach { traded(actor, it.owner, actorSpurs(it), item, it.qty) }
        fills.forEach { if (it.owner.isNotEmpty()) traded(it.owner, actor, net(it), item, it.qty) }
    }

    private fun creditTariff(payer: String, tariff: Long) {
        if (tariff <= 0) return
        val country = Trade.country(payer) ?: return
        val unapplied = tariff - ClaimsApi.creditTariff(country, tariff)
        if (unapplied > 0) Trade.uuid(payer)?.let { wallet.deposit(it, unapplied.toInt()) }
    }

    private fun stockSale(player: String, item: String, qty: Int) {
        if (qty <= 0) return
        Trade.country(player)?.let { KamiProgress.post(it, "sale_stock", item, qty.toLong()) }
    }

    fun sell(seller: String, item: String, qty: Int, unitPrice: Int): SellResult {
        if (!Config.s.validAmount(qty) || !Config.s.validPrice(unitPrice)) return SellResult.Failed
        val price = Matching.listPrice(seller, item, unitPrice)
        val lot = Config.s.lotOf(item)
        val quote = Matching.sellQuote(seller, item, qty, price)
        if (quote.filled == 0 && quote.limited) return SellResult.Limit(Limits.marketSlots(seller))
        if (quote.filled == 0 && quote.listed == 0) return SellResult.NotClean(quote.step)
        if (Money.spurs(qty.toLong() / lot * price) == null) return SellResult.Failed
        val filled = try {
            quote.plan?.let { sellPlanned(seller, item, it) } ?: 0
        } catch (_: IntentOpenException) {
            return SellResult.IntentOpen(quote.filled)
        }
        if (quote.listed <= 0) {
            Market.save()
            return SellResult.Ok(0, filled, 0, quote.limited)
        }
        return listRest(seller, item, quote.listed, price, lot, filled)
    }

    private fun listRest(seller: String, item: String, rest: Int, price: Int, lot: Int, filled: Int): SellResult {
        val restGross = rest.toLong() / lot * price
        val tax = Matching.tax(restGross)
        val id = Market.nextId()
        val intent = Intent(id, IntentType.SELL, LedgerState.PENDING, actor = seller, item = item, qty = rest, unitPrice = price, taxSpurs = tax, netSpurs = restGross - tax, lot = lot)
        try {
            write(intent)
        } catch (e: Exception) {
            if (filled == 0) throw e
            LOG.error("sell of {} could not log its remainder after a partial fill", item, e)
            return SellResult.IntentOpen(filled)
        }
        try {
            Matching.insertSell(item, Order(id, seller, price, rest, lot = lot))
            write(intent.copy(state = LedgerState.BOOK_INSERTED))
            write(intent.copy(state = LedgerState.PAID))
            Market.save()
        } catch (e: Exception) {
            LOG.error("sell of {} left ledger intent {} open for recovery", item, id, e)
            return SellResult.IntentOpen(filled + rest)
        }
        return SellResult.Ok(id, filled, rest)
    }

    fun sellNow(seller: String, item: String, qty: Int): SellResult {
        if (!Config.s.validAmount(qty)) return SellResult.Failed
        val lot = Config.s.lotOf(item)
        val plan = Matching.sellPlan(seller, item, qty - qty % lot)
        if (plan.filled <= 0 || plan.net <= 0) return if (Stocks.stock(item) != null && Stocks.room(item) <= 0) SellResult.Full else SellResult.NoBuyers
        if (Money.spurs(plan.gross) == null) return SellResult.Failed
        val filled = try {
            sellPlanned(seller, item, plan)
        } catch (_: IntentOpenException) {
            return SellResult.IntentOpen(plan.filled)
        }
        Market.save()
        return SellResult.Sold(filled, plan.net)
    }

    private fun sellPlanned(seller: String, item: String, plan: SellPlan): Int {
        val fills = plan.fills.map { it.toLedger() }
        val intent = Intent(Market.nextId(), IntentType.SELL_NOW, LedgerState.PENDING, actor = seller, item = item, qty = plan.filled, taxSpurs = plan.tax, netSpurs = plan.net, fills = fills, lot = Config.s.lotOf(item), capLots = plan.capLots)
        write(intent)
        try {
            soldNow(intent)
        } catch (e: Exception) {
            LOG.error("sell-now {} left a ledger intent open for recovery", intent.id, e)
            throw IntentOpenException()
        }
        fills.forEach { Notify.tell(it.owner, Tone.OK, "order_filled") { arrayOf(Notify.name(seller), Notify.stack(item, it.qty), Phrase.money(gross(it))) } }
        return plan.filled
    }

    private fun soldNow(intent: Intent) {
        var current = intent
        if (current.state == LedgerState.PENDING) {
            Matching.applySale(current.item, current.actor, current.fills.map { FillLinePlan(it.orderId, it.owner, it.qty, it.unitPrice, it.lot) }, current.capLots)
            current.fills.forEach { f -> creditTariff(f.owner, f.tariff) }
            current = current.copy(state = LedgerState.BOOK_INSERTED)
            val actor = Trade.uuid(current.actor)
            if (actor != null) current = current.copy(preBalance = wallet.balance(actor))
            write(current)
        }
        if (creditOrFreeze(current, current.actor, current.netSpurs, current.preBalance)) write(current.copy(state = LedgerState.PAID))
        tradedFills(current.actor, current.item, current.fills) { net(it) - it.tariff }
        stockSale(current.actor, current.item, current.fills.filter { it.owner.isEmpty() }.sumOf { it.qty })
    }

    fun bid(buyer: String, item: String, qty: Int, price: Int): OrderResult {
        if (!Config.s.validAmount(qty) || !Config.s.validPrice(price)) return OrderResult.OutOfRange
        val lot = Config.s.lotOf(item)
        val step = Matching.step(price) * lot
        val amount = qty - qty % step
        if (amount <= 0) return OrderResult.NotClean(step)
        if (Matching.bookFor(item).buys.any { it.owner == buyer }) return OrderResult.Exists
        if (Limits.marketFull(buyer)) return OrderResult.Limit(Limits.marketSlots(buyer))
        if (Matching.effectiveSellPrice(item)?.let { it <= price } == true) return OrderResult.Crosses
        val escrow = amount.toLong() / lot * price
        val total = Money.spurs(escrow) ?: return OrderResult.OutOfRange
        val buyerId = UUID.fromString(buyer)
        if (wallet.balance(buyerId) < total) return OrderResult.InsufficientFunds
        val intent = Intent(Market.nextId(), IntentType.BID, LedgerState.PENDING, actor = buyer, item = item, qty = amount, unitPrice = price, netSpurs = escrow, lot = lot)
        if (!debit(intent, buyerId, total)) return OrderResult.InsufficientFunds
        placeBid(intent)
        Market.save()
        return OrderResult.Ok(intent.id, amount)
    }

    private fun placeBid(intent: Intent) {
        Matching.insertBid(intent.item, Order(intent.id, intent.actor, intent.unitPrice, intent.qty, lot = intent.lot))
        write(intent.copy(state = LedgerState.BOOK_INSERTED))
        write(intent.copy(state = LedgerState.PAID))
    }

    fun cancelBid(owner: String, item: String): Long? {
        val bid = Matching.bookFor(item).buys.firstOrNull { it.owner == owner } ?: return null
        val intent = Intent(Market.nextId(), IntentType.BID_CANCEL, LedgerState.PENDING, actor = owner, item = item, qty = bid.amount, orderId = bid.id, unitPrice = bid.price, netSpurs = Matching.escrow(bid), lot = bid.lot)
        write(intent)
        refundBid(intent)
        Market.save()
        return intent.netSpurs
    }

    private fun refundBid(intent: Intent) {
        var current = intent
        if (current.state == LedgerState.PENDING) {
            Matching.removeBid(current.item, current.orderId)
            current = current.copy(state = LedgerState.BOOK_INSERTED, preBalance = balanceOf(current.actor))
            write(current)
        }
        if (creditOrFreeze(current, current.actor, current.netSpurs, current.preBalance)) write(current.copy(state = LedgerState.PAID))
    }

    internal fun sellToStock(seller: String, item: String, qty: Int): SellResult {
        if (!Config.s.validAmount(qty)) return SellResult.Failed
        val lot = Stocks.good(item)?.lot ?: return SellResult.Failed
        if (qty % lot != 0) return SellResult.NotClean(lot)
        val sale = Stocks.sale(seller, item, qty / lot) ?: return SellResult.Failed
        if (sale.full) return SellResult.Full
        if (sale.net <= 0 || Money.spurs(sale.gross) == null) return SellResult.Failed
        val id = Market.nextId()
        val intent = Intent(id, IntentType.SELL, LedgerState.PENDING, actor = seller, item = item, qty = qty, unitPrice = (sale.gross / sale.lots).toInt(), taxSpurs = sale.tax, netSpurs = sale.net, instant = true, lot = lot, capLots = sale.guaranteed)
        write(intent)
        val current = stockSold(intent)
        if (creditOrFreeze(current, current.actor, current.netSpurs, current.preBalance)) write(current.copy(state = LedgerState.PAID))
        traded(current.actor, "", current.netSpurs, current.item, current.qty)
        stockSale(current.actor, current.item, current.qty)
        Market.save()
        return SellResult.Ok(id)
    }

    private fun stockSold(intent: Intent): Intent {
        Stocks.sold(intent.actor, intent.item, intent.qty / intent.lot.coerceAtLeast(1), intent.capLots, intent.unitPrice)
        val actor = Trade.uuid(intent.actor)
        val current = intent.copy(state = LedgerState.BOOK_INSERTED, preBalance = actor?.let { wallet.balance(it) } ?: intent.preBalance)
        write(current)
        return current
    }

    fun buy(buyer: String, item: String, qty: Int): BuyResult {
        if (!Config.s.validAmount(qty)) return BuyResult.OutOfRange
        val lot = Config.s.lotOf(item)
        val plan = Matching.plan(item, qty - qty % lot, buyer)
        if (plan.filled <= 0) return BuyResult.NothingAvailable
        val total = Money.spurs(plan.totalSpurs) ?: return BuyResult.OutOfRange
        val buyerId = UUID.fromString(buyer)
        if (wallet.balance(buyerId) < total) return BuyResult.InsufficientFunds
        val fills = plan.fills.map { it.toLedger() }
        val intent = Intent(Market.nextId(), IntentType.BUY, LedgerState.PENDING, actor = buyer, item = item, qty = plan.filled, netSpurs = plan.totalSpurs, fills = fills)
        if (!debit(intent, buyerId, total)) return BuyResult.InsufficientFunds
        deliverBuy(intent.copy(state = LedgerState.DEBITED))
        fills.forEach { Notify.tell(it.owner, Tone.OK, "sold") { arrayOf(Notify.name(buyer), Notify.stack(item, it.qty), Phrase.money(net(it))) } }
        Market.save()
        return BuyResult.Ok(plan.filled, plan.totalSpurs)
    }

    private fun deliverBuy(intent: Intent) {
        Matching.applyFills(intent.item, intent.fills.map { FillLinePlan(it.orderId, it.owner, it.qty, it.unitPrice, it.lot) }, intent.actor)
        Market.queueDelivery(intent.actor, intent.item, intent.qty, "${intent.id}:d")
        payFills(intent.copy(state = LedgerState.BOOK_INSERTED).also(::write))
        tradedFills(intent.actor, intent.item, intent.fills) { gross(it) + it.tariff }
    }

    private fun gross(f: LedgerFill): Long = f.qty.toLong() / f.lot.coerceAtLeast(1) * f.unitPrice

    private fun net(f: LedgerFill): Long = gross(f) - if (f.tax >= 0) f.tax else Matching.tax(gross(f))

    fun cancel(owner: String, item: String, orderId: Long): Order? {
        val existing = Matching.bookFor(item).sells.firstOrNull { it.id == orderId && it.owner == owner } ?: return null
        val amount = existing.amount
        val id = Market.nextId()
        val base = Intent(id, IntentType.CANCEL, LedgerState.PENDING, actor = owner, item = item, qty = amount, orderId = orderId, unitPrice = existing.price)
        returnToOwner(base, { Matching.cancelOrder(item, orderId, owner) }, { Market.queueDelivery(owner, item, amount, "$id:d") })
        Market.save()
        return existing
    }

    fun repriceStep(owner: String, item: String, newPrice: Int): Int? =
        Matching.bookFor(item).sells.firstOrNull { it.owner == owner && !Matching.isClean(it.amount / it.lot.coerceAtLeast(1), newPrice) }
            ?.let { Matching.step(newPrice) * it.lot.coerceAtLeast(1) }

    fun repriceAll(owner: String, item: String, newPrice: Int): Boolean {
        if (newPrice <= 0 || repriceStep(owner, item, newPrice) != null) return false
        val orders = Matching.bookFor(item).sells.filter { it.owner == owner }
        if (orders.isEmpty()) return false
        orders.forEach { reprice(owner, item, it.id, newPrice) }
        return true
    }

    private fun reprice(owner: String, item: String, orderId: Long, newPrice: Int) {
        val order = Matching.bookFor(item).sells.firstOrNull { it.id == orderId && it.owner == owner } ?: return
        if (order.price == newPrice) return
        val id = Market.nextId()
        write(Intent(id, IntentType.REPRICE, LedgerState.PENDING, actor = owner, item = item, orderId = orderId, unitPrice = newPrice))
        order.price = newPrice
        Market.dirty = true
        write(Intent(id, IntentType.REPRICE, LedgerState.PAID, actor = owner, item = item, orderId = orderId, unitPrice = newPrice))
        Market.save()
    }

    fun repriceBid(owner: String, item: String, newPrice: Int): OrderResult {
        if (!Config.s.validPrice(newPrice)) return OrderResult.OutOfRange
        val bid = Matching.bookFor(item).buys.firstOrNull { it.owner == owner } ?: return OrderResult.OutOfRange
        val lot = bid.lot.coerceAtLeast(1)
        if (!Matching.isClean(bid.amount / lot, newPrice)) return OrderResult.NotClean(Matching.step(newPrice) * lot)
        if (Matching.effectiveSellPrice(item)?.let { it <= newPrice } == true) return OrderResult.Crosses
        if (newPrice == bid.price) return OrderResult.Ok(bid.id)
        val delta = bid.amount.toLong() / lot * newPrice - Matching.escrow(bid)
        val id = Market.nextId()
        val intent = Intent(id, IntentType.REPRICE_BID, LedgerState.PENDING, actor = owner, item = item, orderId = bid.id, unitPrice = newPrice, netSpurs = delta, lot = lot)
        if (delta > 0) {
            val total = Money.spurs(delta) ?: return OrderResult.OutOfRange
            val buyerId = UUID.fromString(owner)
            if (wallet.balance(buyerId) < total) return OrderResult.InsufficientFunds
            if (!debit(intent, buyerId, total)) return OrderResult.InsufficientFunds
            repriceBidApply(intent.copy(state = LedgerState.DEBITED))
        } else {
            write(intent)
            repriceBidApply(intent)
        }
        Market.save()
        return OrderResult.Ok(bid.id)
    }

    private fun alreadyCredited(who: String, pre: Long, amount: Long): Boolean {
        if (pre < 0) return false
        val actor = Trade.uuid(who) ?: return false
        return wallet.balance(actor) - pre >= amount
    }

    private fun creditOrFreeze(intent: Intent, who: String, amount: Long, pre: Long): Boolean {
        if (alreadyCredited(who, pre, amount)) return true
        if (credit(who, amount)) return true
        Market.freeze(intent.id)
        LOG.error("Freezing ledger intent {}, credit of {} to {} failed permanently", intent.id, amount, who)
        return false
    }

    private fun balanceOf(who: String): Long =
        Trade.uuid(who)?.let { wallet.balance(it) } ?: -1

    private fun withBalances(intent: Intent): Intent = intent.copy(
        prevBalance = if (intent.prevBidder.isNotEmpty()) balanceOf(intent.prevBidder) else -1,
        sellerBalance = if (intent.seller.isNotEmpty()) balanceOf(intent.seller) else -1
    )

    private fun repriceBidApply(intent: Intent) {
        var current = intent
        if (current.state == LedgerState.PENDING || current.state == LedgerState.DEBITED) {
            Matching.bookFor(current.item).buys.firstOrNull { it.id == current.orderId }?.price = current.unitPrice
            Market.dirty = true
            current = current.copy(state = LedgerState.BOOK_INSERTED)
            if (current.netSpurs < 0) {
                val actor = Trade.uuid(current.actor)
                if (actor != null) current = current.copy(preBalance = wallet.balance(actor))
            }
            write(current)
        }
        val ok = current.netSpurs >= 0 || creditOrFreeze(current, current.actor, -current.netSpurs, current.preBalance)
        if (ok) write(current.copy(state = LedgerState.PAID))
    }

    fun cancelAll(owner: String, item: String): Int {
        val ids = Matching.bookFor(item).sells.filter { it.owner == owner }.map { it.id }
        return ids.sumOf { cancel(owner, item, it)?.amount ?: 0 }
    }

    fun auctionList(seller: String, stack: ItemStack, startPrice: Int, buyNowPrice: Int?, registries: HolderLookup.Provider): ListResult {
        if (Limits.auctionFull(seller)) return ListResult.Limit(Limits.auctionSlots(seller))
        val id = Market.nextId()
        val data = StackCodec.encode(stack, registries)
        val label = stack.hoverName.string
        write(Intent(id, IntentType.AUCTION_LIST, LedgerState.PENDING, actor = seller, auctionId = id, stackData = data, label = label, unitPrice = startPrice, buyNow = buyNowPrice ?: -1))
        Auctions.insert(id, seller, data, label, startPrice, buyNowPrice)
        write(Intent(id, IntentType.AUCTION_LIST, LedgerState.PAID, actor = seller, auctionId = id, stackData = data, label = label, unitPrice = startPrice, buyNow = buyNowPrice ?: -1))
        Market.save()
        return ListResult.Ok(id)
    }

    fun auctionBid(bidder: String, auctionId: Long, amount: Int): BidResult {
        val a = Auctions.find(auctionId)?.takeIf { it.state == AuctionState.OPEN && now() < it.expiresAt && it.seller != bidder } ?: return BidResult.NotFound
        if (!Trade.canTrade(bidder, a.seller)) return BidResult.Embargoed(Trade.country(a.seller).orEmpty())
        if (amount <= 0 || amount < Auctions.minBid(a)) return BidResult.TooLow
        val bidderId = UUID.fromString(bidder)
        if (wallet.balance(bidderId) < amount) return BidResult.InsufficientFunds
        val intent = withBalances(Intent(Market.nextId(), IntentType.AUCTION_BID, LedgerState.PENDING, actor = bidder, auctionId = auctionId, unitPrice = amount, prevBidder = a.currentBidder, prevBid = a.currentBid))
        if (!debit(intent, bidderId, amount)) return BidResult.InsufficientFunds
        applyBid(intent)
        if (intent.prevBidder != bidder) Notify.tell(intent.prevBidder, Tone.WARN, "outbid") { arrayOf(Notify.name(bidder), Notify.label(a.label), Phrase.money(amount.toLong())) }
        Notify.tell(a.seller, Tone.INFO, "bid") { arrayOf(Notify.name(bidder), Notify.label(a.label), Phrase.money(amount.toLong())) }
        Market.save()
        return BidResult.Ok
    }

    fun auctionBuyNow(buyer: String, auctionId: Long): BuyNowResult {
        val a = Auctions.find(auctionId)?.takeIf { it.state == AuctionState.OPEN && now() < it.expiresAt && it.seller != buyer } ?: return BuyNowResult.NotAvailable
        val price = a.buyNowPrice ?: return BuyNowResult.NotAvailable
        if (!Trade.canTrade(buyer, a.seller)) return BuyNowResult.Embargoed(Trade.country(a.seller).orEmpty())
        val buyerId = UUID.fromString(buyer)
        if (wallet.balance(buyerId) < price) return BuyNowResult.InsufficientFunds
        val intent = withBalances(settleIntent(a, buyer, price).copy(prevBidder = a.currentBidder, prevBid = a.currentBid, instant = true))
        if (!debit(intent, buyerId, price)) return BuyNowResult.InsufficientFunds
        settle(intent.copy(state = LedgerState.DEBITED))
        Notify.tell(intent.prevBidder, Tone.WARN, "bought_out") { arrayOf(Notify.label(a.label), Notify.name(buyer)) }
        Notify.tell(a.seller, Tone.OK, "auction_bought") { arrayOf(Notify.name(buyer), Notify.label(a.label), Phrase.money(price.toLong())) }
        Market.save()
        return BuyNowResult.Ok
    }

    fun auctionCancel(seller: String, auctionId: Long): Boolean {
        val a = Auctions.find(auctionId)?.takeIf { it.state == AuctionState.OPEN && it.seller == seller && it.currentBidder.isEmpty() } ?: return false
        val id = Market.nextId()
        val base = Intent(id, IntentType.AUCTION_CANCEL, LedgerState.PENDING, actor = seller, auctionId = a.id, stackData = a.stackData)
        returnToOwner(base, { Auctions.cancelled(a.id) }, { Market.queueStackDelivery(seller, a.stackData, "$id:d") })
        Market.save()
        return true
    }

    fun auctionSweepSettle(auctionId: Long) {
        val a = Auctions.find(auctionId) ?: return
        if (a.state != AuctionState.OPEN || a.currentBidder.isEmpty()) return
        settle(withBalances(settleIntent(a, a.currentBidder, a.currentBid)).also(::write))
        Notify.tell(a.seller, Tone.OK, "auction_sold") { arrayOf(Notify.label(a.label), Notify.name(a.currentBidder), Phrase.money(a.currentBid.toLong())) }
        Notify.tell(a.currentBidder, Tone.OK, "auction_won") { arrayOf(Notify.label(a.label), Phrase.money(a.currentBid.toLong())) }
        Market.save()
    }

    fun auctionSweepExpireUnsold(auctionId: Long) {
        val a = Auctions.find(auctionId) ?: return
        if (a.state != AuctionState.OPEN) return
        val id = Market.nextId()
        val base = Intent(id, IntentType.AUCTION_CANCEL, LedgerState.PENDING, actor = a.seller, auctionId = a.id, stackData = a.stackData)
        returnToOwner(base, { Auctions.expire(a.id) }, { Market.queueStackDelivery(a.seller, a.stackData, "$id:d") })
        Notify.tell(a.seller, Tone.INFO, "auction_expired") { arrayOf(Notify.label(a.label)) }
        Market.save()
    }

    private fun settleIntent(a: Auction, buyer: String, price: Int): Intent {
        val tariff = CleanStep.charge(price.toLong(), Trade.terms(buyer, a.seller).tariffPct)
        val net = (price - (price * Config.s.auctionFeePct).toLong() - tariff).coerceAtLeast(0)
        return Intent(Market.nextId(), IntentType.AUCTION_SETTLE, LedgerState.PENDING, actor = buyer, seller = a.seller, auctionId = a.id, unitPrice = price, netSpurs = net, tariff = tariff, stackData = a.stackData)
    }

    private fun settle(intent: Intent) {
        var current = intent
        if (current.state != LedgerState.BOOK_INSERTED) {
            if (current.prevBidder.isNotEmpty() && !creditOrFreeze(current, current.prevBidder, current.prevBid.toLong(), current.prevBalance)) return
            Auctions.settle(current.auctionId, current.actor, current.unitPrice)
            creditTariff(current.actor, current.tariff)
            current = current.copy(state = LedgerState.BOOK_INSERTED)
            write(current)
        }
        Market.queueStackDelivery(current.actor, current.stackData, "${current.id}:d")
        if (creditOrFreeze(current, current.seller, current.netSpurs, current.sellerBalance)) write(current.copy(state = LedgerState.PAID))
        val item = StackCodec.itemId(current.stackData)
        val count = StackCodec.count(current.stackData)
        traded(current.actor, current.seller, current.unitPrice.toLong(), item, count)
        traded(current.seller, current.actor, current.netSpurs, item, count)
        if (earnsXp(current.seller, current.actor)) Trade.country(current.seller)?.let { KamiProgress.post(it, "auction_sold", item, count.toLong()) }
    }

    private fun applyBid(intent: Intent) {
        var current = intent
        if (current.state != LedgerState.BOOK_INSERTED) {
            Auctions.applyBid(current.auctionId, current.actor, current.unitPrice)
            current = current.copy(state = LedgerState.BOOK_INSERTED)
            write(current)
        }
        val refunded = current.prevBidder.isEmpty() || creditOrFreeze(current, current.prevBidder, current.prevBid.toLong(), current.prevBalance)
        if (refunded) write(current.copy(state = LedgerState.PAID))
    }

    private fun payFills(start: Intent) {
        var intent = start
        for (idx in intent.fills.indices) {
            val f = intent.fills[idx]
            if (f.owner.isEmpty() || f.orderId in intent.paid) continue
            val pre = if (f.pre >= 0) f.pre else balanceOf(f.owner)
            if (f.pre < 0) {
                intent = intent.copy(fills = intent.fills.mapIndexed { i, x -> if (i == idx) x.copy(pre = pre) else x })
                write(intent)
            }
            if (!creditOrFreeze(intent, f.owner, net(f), pre)) return
            intent = intent.copy(paid = intent.paid + f.orderId).also(::write)
            creditTariff(intent.actor, f.tariff)
        }
        write(intent.copy(state = LedgerState.PAID))
    }

    private fun isPendingDebit(intent: Intent) = intent.state == LedgerState.PENDING && when (intent.type) {
        IntentType.BUY, IntentType.BID, IntentType.AUCTION_BID -> true
        IntentType.AUCTION_SETTLE -> intent.instant
        IntentType.REPRICE_BID -> intent.netSpurs > 0
        else -> false
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
        val pending = latest.values.filter { it.state != LedgerState.PAID && it.state != LedgerState.VOID }
        pending.forEach { open[it.id] = it }
        val (debits, rest) = pending.partition(::isPendingDebit)
        debits.groupBy { it.actor }.filterValues { it.size > 1 }.forEach { (actor, intents) ->
            LOG.warn("Recovering multiple pending debits for {} in the same pass: {}", actor, intents.map { it.id })
        }
        debits.forEach(::resume)
        rest.forEach(::resume)
    }

    private fun resume(intent: Intent) {
        runCatching {
            when (intent.type) {
                IntentType.SELL -> {
                    if (!intent.instant) {
                        Matching.insertSell(intent.item, Order(intent.id, intent.actor, intent.unitPrice, intent.qty, lot = intent.lot))
                        write(intent.copy(state = LedgerState.PAID))
                    } else {
                        val current = if (intent.state == LedgerState.PENDING) stockSold(intent) else intent
                        if (creditOrFreeze(current, current.actor, current.netSpurs, current.preBalance)) {
                            write(current.copy(state = LedgerState.PAID))
                            traded(current.actor, "", current.netSpurs, current.item, current.qty)
                            stockSale(current.actor, current.item, current.qty)
                        }
                    }
                }
                IntentType.BUY -> when (intent.state) {
                    LedgerState.PENDING -> resumeDebited(intent, Money.spurs(intent.netSpurs) ?: 0)?.let(::deliverBuy) ?: void(intent)
                    LedgerState.DEBITED -> deliverBuy(intent)
                    else -> payFills(intent)
                }
                IntentType.CANCEL -> {
                    Matching.cancelOrder(intent.item, intent.orderId, intent.actor)
                    Market.queueDelivery(intent.actor, intent.item, intent.qty, "${intent.id}:d")
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
                IntentType.AUCTION_BID -> if (intent.state == LedgerState.PENDING) resumeDebited(intent, intent.unitPrice)?.let(::applyBid) ?: void(intent) else applyBid(intent)
                IntentType.AUCTION_SETTLE -> if (intent.state == LedgerState.PENDING && intent.instant) resumeDebited(intent, intent.unitPrice)?.let(::settle) ?: void(intent) else settle(intent)
                IntentType.BID -> if (intent.state == LedgerState.PENDING) resumeDebited(intent, Money.spurs(intent.netSpurs) ?: 0)?.let(::placeBid) ?: void(intent) else placeBid(intent)
                IntentType.REPRICE_BID -> if (intent.state == LedgerState.PENDING && intent.netSpurs > 0)
                    resumeDebited(intent, Money.spurs(intent.netSpurs) ?: 0)?.let(::repriceBidApply) ?: void(intent)
                else repriceBidApply(intent)
                IntentType.BID_CANCEL -> refundBid(intent)
                IntentType.SELL_NOW -> soldNow(intent)
                IntentType.AUCTION_CANCEL -> {
                    Market.queueStackDelivery(intent.actor, intent.stackData, "${intent.id}:d")
                    write(intent.copy(state = LedgerState.PAID))
                }
            }
        }.onFailure {
            LOG.error("Failed to recover ledger intent ${intent.id}, freezing for manual resolution", it)
            Market.freeze(intent.id)
        }
    }

    private fun void(intent: Intent) {
        LOG.warn("Ledger intent {} of {} stopped before the debit was confirmed, check the balance by hand", intent.id, intent.actor)
        write(intent.copy(state = LedgerState.VOID))
    }
}
