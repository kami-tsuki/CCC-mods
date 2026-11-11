package kami.economy.economy

import kami.libs.util.toUuidOrNull
import kami.economy.LOG
import kami.economy.Config
import kami.economy.Market
import kami.economy.Order
import kami.economy.now
import kami.economy.quest.Quests
import kami.economy.db.intentTable
import kami.economy.db.intents
import kami.libs.chat.Tone
import kami.libs.db.Db
import kami.libs.db.Session
import kami.libs.stats.Stats
import kami.libs.claims.ClaimsApi
import kami.libs.skills.SkillClass
import kami.libs.skills.Skills
import kami.libs.economy.CleanStep
import kami.libs.economy.Flow
import kami.libs.economy.Statement
import kami.libs.economy.Money
import kami.libs.economy.Numismatics
import kami.libs.economy.Party
import kami.libs.economy.ServerBank
import kami.libs.economy.ServerParty
import kami.libs.progress.KamiProgress
import kami.libs.text.Phrase
import kotlinx.serialization.Serializable
import net.minecraft.core.HolderLookup
import net.minecraft.world.item.ItemStack
import java.util.UUID
import kotlin.math.log10

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
data class LedgerFill(val orderId: Long, val owner: String, val qty: Int, val unitPrice: Int, val lot: Int = 1, val tax: Long = -1, val tariff: Long = 0, val pre: Long = -1, val gross: Long = -1)

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
    val peg: Int? = null,
    val pegLimit: Int? = null,
    val preBalance: Long = -1,
    val prevBalance: Long = -1,
    val sellerBalance: Long = -1,
    val tariff: Long = 0,
    val done: List<String> = emptyList(),
    val variant: String = "",
    val variantMul: Int = 1,
    val variantDiv: Int = 1,
    val ver: Int = 0,
    val applied: Boolean = false
)

class IntentOpenException : RuntimeException()

sealed class SellResult {
    data class Ok(val orderId: Long, val filled: Int = 0, val listed: Int = 0, val limited: Boolean = false) : SellResult()
    data class Sold(val filled: Int, val net: Long) : SellResult()
    object NoBuyers : SellResult()
    object Failed : SellResult()
    object OwnCross : SellResult()
    data class NotClean(val step: Int) : SellResult()
    object Full : SellResult()
    data class IntentOpen(val kept: Int) : SellResult()
    data class Frozen(val kept: Int) : SellResult()
    data class Limit(val max: Int) : SellResult()
}

sealed class BuyResult {
    data class Ok(val filled: Int, val spent: Long) : BuyResult()
    object OutOfRange : BuyResult()
    object InsufficientFunds : BuyResult()
    object NothingAvailable : BuyResult()
    object IntentOpen : BuyResult()
    object Frozen : BuyResult()
}

sealed class OrderResult {
    data class Ok(val orderId: Long, val qty: Int = 0, val filled: Int = 0) : OrderResult()
    object OutOfRange : OrderResult()
    data class NotClean(val step: Int) : OrderResult()
    object InsufficientFunds : OrderResult()
    object Crosses : OrderResult()
    object OwnCross : OrderResult()
    object Exists : OrderResult()
    data class Limit(val max: Int) : OrderResult()
}

sealed class ListResult {
    data class Ok(val id: Long) : ListResult()
    data class Limit(val max: Int) : ListResult()
    object TooLarge : ListResult()
    object BadPrice : ListResult()
}

sealed class BidResult {
    object Ok : BidResult()
    object BoughtOut : BidResult()
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

private class Memo(val flow: Flow, val counterparty: String = "", val item: String = "", val qty: Int = 0, val fee: Long = 0) {
    fun record(player: UUID, amount: Long) {
        if (amount == 0L) return
        if (fee > 0 && amount > 0) {
            Statement.record(player, amount + fee, flow, counterparty, item, qty)
            Statement.record(player, -fee, Flow.FEE, counterparty, item, qty)
        } else Statement.record(player, amount, flow, counterparty, item, qty)
    }
}

object Ledger {
    private const val MAX_STACK_DATA = 16_384
    private var attached = false
    private val open = LinkedHashMap<Long, Intent>()
    var wallet: Wallet = NumismaticsWallet

    fun attach() {
        val all = Db.read { intents() }
        intentTable.prime(all)
        open.clear()
        all.filter { it.state != LedgerState.PAID && it.state != LedgerState.VOID }.forEach { open[it.id] = it }
        attached = true
    }

    fun detach() {
        attached = false
        open.clear()
    }

    internal fun changes(): (Session.() -> Unit)? = if (attached) intentTable.sync(open.values) else null

    fun holds(ref: String): Boolean = ref.startsWith("eco:") && ref.split(':').getOrNull(1)?.toLongOrNull()?.let(open::containsKey) == true

    private fun FillLinePlan.toLedger() = LedgerFill(orderId, owner, qty, unitPrice, lot, tax, tariff, gross = gross)

    private fun LedgerFill.toPlan() = FillLinePlan(orderId, owner, qty, unitPrice, lot, gross(this), taxOf(this), tariff)

    fun close(id: Long): Boolean {
        val intent = open[id] ?: return false
        LOG.warn("Admin voided open intent {} ({} {} actor={} item={} qty={}), no refund", id, intent.type, intent.state, intent.actor, intent.item, intent.qty)
        bank(intent.id, "void", intent.actor, held(intent), Flow.RECOVERY)
        write(intent.copy(state = LedgerState.VOID))
        return true
    }

    fun resolve(id: Long): Boolean {
        val unfrozen = Market.data.frozen.remove(id)
        val closed = close(id)
        if (unfrozen || closed) Market.dirty = true
        return unfrozen || closed
    }

    fun frozen(): List<Pair<Long, Intent?>> = Market.data.frozen.map { it to open[it] }

    fun owed(i: Intent): List<Pair<String, Long>> = when (i.state) {
        LedgerState.DEBITED -> listOf(Triple("", i.actor, held(i)))
        LedgerState.BOOK_INSERTED -> when (i.type) {
            IntentType.BUY -> i.fills.mapIndexedNotNull { idx, f -> Triple("pay:$idx", f.owner, net(f)).takeIf { f.owner.isNotEmpty() && f.orderId !in i.paid } }
            IntentType.SELL -> if (i.instant) listOf(Triple("pay", i.actor, i.netSpurs)) else emptyList()
            IntentType.SELL_NOW, IntentType.BID_CANCEL -> listOf(Triple("pay", i.actor, i.netSpurs))
            IntentType.REPRICE_BID -> listOf(Triple("pay", i.actor, -i.netSpurs))
            IntentType.AUCTION_BID, IntentType.AUCTION_CANCEL -> listOf(Triple("refund", i.prevBidder, i.prevBid.toLong()))
            IntentType.AUCTION_SETTLE -> listOf(Triple("pay", i.seller, i.netSpurs))
            else -> emptyList()
        }
        else -> emptyList()
    }.filter { it.first !in i.done && it.third > 0 && it.second.toUuidOrNull() != null }.map { it.second to it.third }

    fun release(id: Long): List<Pair<String, Long>>? {
        val due = open[id]?.let(::owed) ?: return null
        if (!resolve(id)) return null
        due.forEachIndexed { i, (who, amount) ->
            if (credit(who, amount, Memo(Flow.RECOVERY))) bank(id, if (i == 0) "release" else "release:$i", who, -amount, Flow.RECOVERY)
        }
        return due
    }

    fun cancelAsServer(side: String, item: String, id: Long, reason: String): String? {
        val (owner, what) = when (side) {
            "sell" -> Matching.bookFor(item).sells.firstOrNull { it.id == id && !it.synthetic && it.owner.isNotEmpty() }?.owner?.also { cancel(it, item, id) } to item
            "buy" -> Matching.bookFor(item).buys.firstOrNull { it.id == id && !it.synthetic && it.owner.isNotEmpty() }?.owner?.also { cancelBid(it, item, id) } to item
            "auction" -> Auctions.find(id)?.takeIf { it.state == AuctionState.OPEN }?.let { serverCancel(it) to StackCodec.itemId(it.stackData) } ?: (null to "")
            else -> null to ""
        }
        owner ?: return null
        Notify.cancelled(owner, what, id, reason)
        return owner
    }

    private fun serverCancel(a: Auction): String {
        val base = withBalances(Intent(Market.nextId(), IntentType.AUCTION_CANCEL, LedgerState.PENDING, actor = a.seller, auctionId = a.id, stackData = a.stackData, prevBidder = a.currentBidder, prevBid = a.currentBid))
        write(base)
        Auctions.cancelled(a.id)
        returnAuction(base.copy(state = LedgerState.BOOK_INSERTED).also(::write))
        return a.seller
    }

    private fun returnAuction(intent: Intent) {
        Market.queueStackDelivery(intent.actor, intent.stackData, "${intent.id}:d")
        refundPrev(intent)?.let { write(it.copy(state = LedgerState.PAID)) }
    }

    private fun refundPrev(intent: Intent): Intent? =
        if (intent.prevBidder.isEmpty()) intent else creditOrFreeze(intent, "refund", intent.prevBidder, intent.prevBid.toLong(), intent.prevBalance, auctionMemo(intent, Flow.REFUND))

    private fun held(i: Intent): Long = when (i.state) {
        LedgerState.DEBITED -> when (i.type) {
            IntentType.BUY, IntentType.BID -> i.netSpurs
            IntentType.REPRICE_BID -> i.netSpurs.coerceAtLeast(0)
            IntentType.AUCTION_BID, IntentType.AUCTION_SETTLE -> i.unitPrice.toLong()
            else -> 0
        }
        LedgerState.BOOK_INSERTED -> when (i.type) {
            IntentType.BUY -> { bankStock(i); i.fills.filter { it.owner.isNotEmpty() && it.orderId !in i.paid }.sumOf { gross(it) + it.tariff } }
            IntentType.BID_CANCEL -> i.netSpurs
            IntentType.REPRICE_BID -> (-i.netSpurs).coerceAtLeast(0)
            IntentType.AUCTION_BID, IntentType.AUCTION_CANCEL -> if (i.prevBidder.isNotEmpty()) i.prevBid.toLong() else 0
            IntentType.AUCTION_SETTLE -> { bankFee(i); i.netSpurs }
            IntentType.SELL_NOW -> i.fills.filter { it.owner.isNotEmpty() && !Quests.owns(it.owner) }.sumOf { gross(it) - it.tariff }
            else -> 0
        }
        else -> 0
    }

    private fun bank(id: Long, step: String, who: String, amount: Long, flow: Flow, note: String = "") {
        if (!attached || amount == 0L) return
        val party = Party.parse(who) ?: ServerParty
        val ref = "eco:$id:$step"
        if (amount > 0) ServerBank.collect(party, amount, flow, note, ref, once = true) else ServerBank.pay(party, -amount, flow, note, ref, once = true)
    }

    private fun tradeNote(i: Intent) = Statement.note("kami_libs.note.trade", i.qty, i.item)

    private fun taxNote(i: Intent) = Statement.note("kami_libs.note.market_tax", i.item)

    private fun counterpartyOf(fills: List<LedgerFill>): String = fills.map { Party.parse(it.owner)?.key ?: ServerParty.key }.distinct().singleOrNull().orEmpty()

    private fun bankSale(i: Intent) {
        bank(i.id, "sale", i.actor, i.fills.filter { it.owner.isNotEmpty() }.sumOf(::gross) - i.netSpurs - i.taxSpurs - i.fills.sumOf { it.tariff }, Flow.SERVER_SALE, tradeNote(i))
        bank(i.id, "tax", i.actor, i.taxSpurs, Flow.TAX, taxNote(i))
        i.fills.forEachIndexed { idx, f -> if (Quests.owns(f.owner)) bank(i.id, "quest:$idx", i.actor, -gross(f), Flow.QUEST, tradeNote(i)) }
        taxed(i.actor, i.item, i.taxSpurs)
    }

    private fun bankStock(i: Intent) {
        val tax = i.fills.filter { it.owner.isEmpty() }.sumOf(::taxOf)
        bank(i.id, "stock", i.actor, i.netSpurs - i.fills.filter { it.owner.isNotEmpty() }.sumOf { gross(it) + it.tariff } - tax, Flow.SERVER_PURCHASE, tradeNote(i))
        bank(i.id, "stock_tax", i.actor, tax, Flow.TAX, taxNote(i))
        taxed(i.actor, i.item, tax)
    }

    private fun fee(i: Intent): Long = i.unitPrice - i.netSpurs - i.tariff

    private fun bankFee(i: Intent) {
        val fee = fee(i)
        bank(i.id, "fee", i.seller, fee, if (fee < 0) Flow.RECOVERY else Flow.FEE, Statement.note("kami_libs.note.auction_fee", i.item))
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
    }

    private fun Intent.mark(step: String): Intent = copy(done = done + step).also(::write)

    private fun credit(owner: String, amount: Long, memo: Memo, deposited: () -> Unit = {}): Boolean {
        if (amount == 0L) return true
        val spurs = Money.spurs(amount)
        val id = owner.toUuidOrNull()
        if (spurs != null && id != null && wallet.deposit(id, spurs)) {
            deposited()
            memo.record(id, amount)
            return true
        }
        LOG.error("Could not credit {} spurs to {}", amount, owner)
        return false
    }

    private fun debit(intent: Intent, payer: UUID, amount: Int, memo: Memo): Boolean {
        val staged = intent.copy(preBalance = wallet.balance(payer))
        write(staged)
        val taken = wallet.deduct(payer, amount)
        if (taken >= amount) {
            memo.record(payer, -amount.toLong())
            write(staged.copy(state = LedgerState.DEBITED))
            return true
        }
        if (taken > 0 && !wallet.deposit(payer, taken)) bank(intent.id, "back", payer.toString(), taken.toLong(), Flow.RECOVERY)
        write(staged.copy(state = LedgerState.VOID))
        return false
    }

    private fun resumeDebited(intent: Intent, amount: Int): Intent? {
        if (amount <= 0 || intent.preBalance < 0) return null
        val payer = intent.actor.toUuidOrNull() ?: return null
        val diff = intent.preBalance - wallet.balance(payer)
        return when {
            diff <= 0 -> null
            diff >= amount -> {
                if (diff > amount && !wallet.deposit(payer, (diff - amount).toInt())) bank(intent.id, "back", intent.actor, diff - amount, Flow.RECOVERY)
                intent.copy(state = LedgerState.DEBITED)
            }
            else -> { if (!wallet.deposit(payer, diff.toInt())) bank(intent.id, "back", intent.actor, diff, Flow.RECOVERY); null }
        }
    }

    internal fun earnsXp(player: String, counterparty: String): Boolean {
        val country = Trade.country(player) ?: return false
        val other = Trade.country(counterparty) ?: return true
        return ClaimsApi.neutralForeign(country, other)
    }

    private fun stat(player: String, item: String, qty: Int, spurs: Long, sell: Boolean) {
        if (qty <= 0 || player.toUuidOrNull() == null) return
        val s = "p:$player"
        Stats.add(if (sell) "market.sell" else "market.buy", s, item, qty.toLong())
        Stats.add("market.trade", s)
        Stats.add("market.volume", s, n = spurs)
    }

    private fun taxed(player: String, item: String, spurs: Long) {
        if (spurs > 0 && player.toUuidOrNull() != null) Stats.add("market.tax", "p:$player", item, spurs)
    }

    private fun tariffed(payer: String, country: String, spurs: Long) {
        if (spurs > 0 && payer.toUuidOrNull() != null) Stats.add("market.tariff", "p:$payer", country, spurs)
    }

    private fun traded(player: String, counterparty: String, spurs: Long, item: String, qty: Int, sell: Boolean, xp: Boolean = true, orders: Boolean = true) {
        stat(player, item, qty, spurs, sell)
        if (!xp || spurs <= 0 || qty <= 0) return
        skillXp(player, counterparty, spurs, orders)
        if (!earnsXp(player, counterparty)) return
        val country = Trade.country(player) ?: return
        KamiProgress.post(country, "trade", item, qty.toLong())
        KamiProgress.post(country, "trade_value", item, spurs)
    }

    private fun skillXp(player: String, counterparty: String, spurs: Long, orders: Boolean) {
        val id = player.toUuidOrNull() ?: return
        if (counterparty == player || counterparty.toUuidOrNull() == null) return
        val mine = Trade.country(player)
        val theirs = Trade.country(counterparty)
        val foreign = mine != null && theirs != null && ClaimsApi.neutralForeign(mine, theirs)
        if (!foreign && !orders) return
        Skills.grant(id, SkillClass.TRADER, if (foreign) "trade" else "deal", log10(spurs.toDouble()), if (foreign) "trade:$counterparty" else "deal")
    }

    private fun tradedFills(actor: String, item: String, fills: List<LedgerFill>, actorSells: Boolean, washedOut: Set<Int> = emptySet(), actorSpurs: (LedgerFill) -> Long) {
        fills.forEachIndexed { i, f -> traded(actor, f.owner, actorSpurs(f), item, f.qty, actorSells, i !in washedOut && !Quests.owns(f.owner)) }
        val owned = fills.withIndex().filter { it.value.owner.isNotEmpty() && !Quests.owns(it.value.owner) }
        owned.forEach { (i, f) -> traded(f.owner, actor, net(f), item, f.qty, !actorSells, i !in washedOut) }
        val x = Trade.country(actor) ?: return
        owned.groupBy { Trade.country(it.value.owner) }.forEach { (y, f) -> crossTrade(x, y, f.sumOf { actorSpurs(it.value) }) }
    }

    private fun crossTrade(x: String?, y: String?, value: Long) {
        if (x != null && y != null && ClaimsApi.neutralForeign(x, y)) ClaimsApi.traded(x, y, value)
    }

    private fun creditTariff(intent: Intent, step: String, buyer: String, payer: String, tariff: Long): Intent {
        if (tariff <= 0 || step in intent.done) return intent
        val country = Trade.country(buyer)
        val next = intent.mark(step)
        val note = Statement.note("kami_libs.note.tariff", intent.item)
        if (country == null) return next.also { tariffed(payer, "", tariff); bank(it.id, step, payer, tariff, Flow.TAX, note) }
        val applied = runCatching { ClaimsApi.creditTariff(country, tariff, Party.parse(payer)?.key.orEmpty(), note) }.getOrElse { LOG.error("Tariff of {} for intent {} failed, returning it", tariff, intent.id, it); 0L }
        tariffed(payer, "c:$country", applied)
        val unapplied = tariff - applied
        if (unapplied <= 0) return next
        val to = payer.toUuidOrNull()
        if (to != null && wallet.deposit(to, unapplied.toInt())) Statement.record(to, unapplied, Flow.REFUND) else bank(next.id, step, payer, unapplied, Flow.RECOVERY)
        return next
    }

    private fun stockSale(player: String, item: String, qty: Int) {
        if (qty <= 0) return
        Trade.country(player)?.let { KamiProgress.post(it, "sale_stock", item, qty.toLong()) }
    }

    fun sell(seller: String, item: String, qty: Int, unitPrice: Int, peg: Int? = null, pegLimit: Int? = null): SellResult {
        if (!Config.s.validAmount(qty) || !Config.s.validPrice(unitPrice)) return SellResult.Failed
        val price = Matching.listPrice(seller, item, unitPrice)
        if (Matching.ownCross(seller, item, price, false)) return SellResult.OwnCross
        val lot = Config.s.lotOf(item)
        val quote = Matching.sellQuote(seller, item, qty, price)
        if (quote.filled == 0 && quote.limited) return SellResult.Limit(Limits.marketSlots(seller))
        if (quote.filled == 0 && quote.listed == 0) return SellResult.NotClean(quote.step)
        if (Money.spurs(qty.toLong() / lot * price) == null) return SellResult.Failed
        if (quote.plan?.let { Money.spurs(it.gross) == null } == true) return SellResult.Failed
        val filled = try {
            quote.plan?.let { sellPlanned(seller, item, it) ?: return SellResult.Frozen(quote.filled) } ?: 0
        } catch (_: IntentOpenException) {
            return SellResult.IntentOpen(quote.filled)
        }
        if (quote.listed <= 0) return SellResult.Ok(0, filled, 0, quote.limited)
        return listRest(seller, item, quote.listed, price, lot, filled, peg, pegLimit)
    }

    private fun listRest(seller: String, item: String, rest: Int, price: Int, lot: Int, filled: Int, peg: Int?, pegLimit: Int?): SellResult {
        val restGross = rest.toLong() / lot * price
        val tax = Matching.tax(restGross)
        val id = Market.nextId()
        val intent = Intent(id, IntentType.SELL, LedgerState.PENDING, actor = seller, item = item, qty = rest, unitPrice = price, taxSpurs = tax, netSpurs = restGross - tax, lot = lot, peg = peg, pegLimit = pegLimit)
        write(intent)
        try {
            Matching.insertSell(item, Order(id, seller, price, rest, lot = lot, peg = peg, pegLimit = pegLimit))
            write(intent.copy(state = LedgerState.BOOK_INSERTED))
            write(intent.copy(state = LedgerState.PAID))
        } catch (e: Exception) {
            LOG.error("sell of {} left ledger intent {} open for recovery", item, id, e)
            return SellResult.IntentOpen(filled + rest)
        }
        return SellResult.Ok(id, filled, rest)
    }

    fun sellNow(seller: String, item: String, qty: Int): SellResult {
        if (!Config.s.validAmount(qty)) return SellResult.Failed
        val lot = Config.s.lotOf(item)
        val plan = Matching.sellPlan(item, qty - qty % lot, seller)
        if (plan.filled <= 0 || plan.net <= 0) return if (Stocks.good(item) != null && Stocks.demandLeft(item, seller) <= 0) SellResult.Full else SellResult.NoBuyers
        if (Money.spurs(plan.gross) == null) return SellResult.Failed
        val filled = try {
            sellPlanned(seller, item, plan) ?: return SellResult.Frozen(plan.filled)
        } catch (_: IntentOpenException) {
            return SellResult.IntentOpen(plan.filled)
        }
        return SellResult.Sold(filled, plan.net)
    }

    fun questDeliver(seller: String, questId: Long, qty: Int): SellResult {
        if (!Config.s.validAmount(qty)) return SellResult.Failed
        val (item, plan) = Matching.questPlan(seller, questId, qty) ?: return SellResult.NoBuyers
        if (plan.filled <= 0 || plan.net <= 0) return SellResult.NoBuyers
        if (Money.spurs(plan.gross) == null) return SellResult.Failed
        val filled = try {
            sellPlanned(seller, item, plan) ?: return SellResult.Frozen(plan.filled)
        } catch (_: IntentOpenException) {
            return SellResult.IntentOpen(plan.filled)
        }
        return SellResult.Sold(filled, plan.net)
    }

    private fun sellPlanned(seller: String, item: String, plan: SellPlan): Int? {
        val fills = plan.fills.map { it.toLedger() }
        val intent = Intent(Market.nextId(), IntentType.SELL_NOW, LedgerState.PENDING, actor = seller, item = item, qty = plan.filled, taxSpurs = plan.tax, netSpurs = plan.net, fills = fills, lot = Config.s.lotOf(item))
        write(intent)
        val paid = try {
            soldNow(intent)
        } catch (e: Exception) {
            LOG.error("sell-now {} left a ledger intent open for recovery", intent.id, e)
            throw IntentOpenException()
        }
        runCatching { fills.filterNot { Quests.owns(it.owner) }.forEach { Notify.tell(it.owner, Tone.OK, "order_filled") { arrayOf(Notify.name(seller), Notify.stack(item, it.qty), Phrase.money(gross(it))) } } }
        return plan.filled.takeIf { paid }
    }

    private fun sellLeft(item: String, orderId: Long): Int = Matching.bookFor(item).sells.firstOrNull { it.id == orderId }?.amount ?: 0

    private fun zeroed(i: Intent, idx: Int, qty: Int = i.qty - i.fills[idx].qty, tax: Long = i.taxSpurs, net: Long = i.netSpurs): Intent =
        i.copy(fills = i.fills.mapIndexed { k, x -> if (k == idx) x.copy(qty = 0, gross = 0, tax = 0, tariff = 0) else x }, qty = qty, taxSpurs = tax, netSpurs = net)

    private fun dropSell(i: Intent, idx: Int): Intent? {
        val f = i.fills[idx]
        LOG.warn("Dropping fill {} of intent {}, sell order {} is gone or too small, refunding {}", idx, i.id, f.orderId, gross(f) + f.tariff)
        val refunded = creditOrFreeze(i, "drop:$idx", i.actor, gross(f) + f.tariff, -1, Memo(Flow.REFUND, item = i.item, qty = f.qty)) ?: return null
        return zeroed(refunded, idx, net = refunded.netSpurs - (gross(f) + f.tariff)).also(::write)
    }

    private fun soldNow(intent: Intent): Boolean {
        val washedOut = HashSet<Int>()
        val t = now()
        var current = intent
        if (current.state == LedgerState.PENDING) {
            for ((idx, f) in current.fills.withIndex()) {
                if ("fill:$idx" in current.done) continue
                val key = "${current.id}:d$idx"
                if ((f.owner.isEmpty() || !Market.queued(f.owner, key)) && !Matching.applySale(current.item, current.actor, f.toPlan(), key, t)) washedOut += idx
                current = current.mark("fill:$idx")
            }
            for ((idx, f) in current.fills.withIndex()) current = creditTariff(current, "tariff:$idx", f.owner, current.actor, f.tariff)
            current = current.copy(state = LedgerState.BOOK_INSERTED, preBalance = current.actor.toUuidOrNull()?.let { wallet.balance(it) } ?: current.preBalance)
            write(current)
        }
        val paid = creditOrFreeze(current, "pay", current.actor, current.netSpurs, current.preBalance, Memo(Flow.MARKET_SELL, counterpartyOf(current.fills).ifEmpty { ServerParty.key }, current.item, current.qty))?.let {
            bankSale(it)
            write(it.copy(state = LedgerState.PAID))
        }
        if (paid == null) return false
        tradedFills(current.actor, current.item, current.fills, true, washedOut) { net(it) - it.tariff }
        stockSale(current.actor, current.item, current.fills.filter { it.owner.isEmpty() }.sumOf { it.qty })
        return paid != null
    }

    fun bid(buyer: String, item: String, qty: Int, price: Int, peg: Int? = null, pegLimit: Int? = null): OrderResult {
        if (!Config.s.validAmount(qty) || !Config.s.validPrice(price)) return OrderResult.OutOfRange
        val lot = Config.s.lotOf(item)
        val step = Matching.step(price) * lot
        val amount = qty - qty % step
        if (amount <= 0) return OrderResult.NotClean(step)
        if (Matching.ownCross(buyer, item, price, true)) return OrderResult.OwnCross
        val plan = Matching.plan(item, amount, buyer, price)
        val rest = (amount - plan.filled).let { it - it % step }
        if (rest > 0 && Limits.marketFull(buyer)) return OrderResult.Limit(Limits.marketSlots(buyer))
        if (rest > 0 && Matching.bookFor(item).buys.any { it.owner == buyer }) return OrderResult.Exists
        val escrow = rest.toLong() / lot * price
        val total = Money.spurs(plan.totalSpurs + escrow) ?: return OrderResult.OutOfRange
        val buyerId = UUID.fromString(buyer)
        if (wallet.balance(buyerId) < total) return OrderResult.InsufficientFunds
        var filled = 0
        if (plan.filled > 0) {
            val bought = buy(buyer, item, plan.filled, price)
            if (bought is BuyResult.InsufficientFunds) return OrderResult.InsufficientFunds
            if (bought is BuyResult.IntentOpen) return OrderResult.Ok(0, 0, plan.filled)
            if (bought is BuyResult.Ok) filled = bought.filled
        }
        if (rest <= 0) return OrderResult.Ok(0, 0, filled)
        val intent = Intent(Market.nextId(), IntentType.BID, LedgerState.PENDING, actor = buyer, item = item, qty = rest, unitPrice = price, netSpurs = escrow, lot = lot, peg = peg, pegLimit = pegLimit)
        val spurs = Money.spurs(escrow) ?: return OrderResult.Ok(0, 0, filled)
        if (!debit(intent, buyerId, spurs, Memo(Flow.MARKET_BUY, item = intent.item, qty = intent.qty))) return if (filled > 0) OrderResult.Ok(0, 0, filled) else OrderResult.InsufficientFunds
        placeBid(intent)
        return OrderResult.Ok(intent.id, rest, filled)
    }

    private fun placeBid(intent: Intent) {
        Matching.insertBid(intent.item, Order(intent.id, intent.actor, intent.unitPrice, intent.qty, lot = intent.lot, peg = intent.peg, pegLimit = intent.pegLimit))
        write(intent.copy(state = LedgerState.BOOK_INSERTED))
        write(intent.copy(state = LedgerState.PAID))
    }

    fun cancelBid(owner: String, item: String, orderId: Long? = null, onFrozen: () -> Unit = {}): Long? {
        val bid = Matching.bookFor(item).buys.firstOrNull { it.owner == owner && (orderId == null || it.id == orderId) } ?: return null
        val intent = Intent(Market.nextId(), IntentType.BID_CANCEL, LedgerState.PENDING, actor = owner, item = item, qty = bid.amount, orderId = bid.id, unitPrice = bid.price, netSpurs = Matching.escrow(bid), lot = bid.lot)
        write(intent)
        if (!refundBid(intent)) onFrozen()
        return intent.netSpurs
    }

    private fun refundBid(intent: Intent): Boolean {
        var current = intent
        if (current.state == LedgerState.PENDING) {
            Matching.removeBid(current.item, current.orderId)
            current = current.copy(state = LedgerState.BOOK_INSERTED, preBalance = balanceOf(current.actor))
            write(current)
        }
        if (creditOrFreeze(current, "pay", current.actor, current.netSpurs, current.preBalance, Memo(Flow.REFUND, item = current.item, qty = current.qty)) == null) return false
        write(current.copy(state = LedgerState.PAID))
        return true
    }

    private fun stockSold(intent: Intent): Intent {
        Stocks.sold(intent.actor, intent.item, intent.qty / intent.lot.coerceAtLeast(1))
        val actor = intent.actor.toUuidOrNull()
        val current = intent.copy(state = LedgerState.BOOK_INSERTED, preBalance = actor?.let { wallet.balance(it) } ?: intent.preBalance)
        write(current)
        return current
    }

    fun buy(buyer: String, item: String, qty: Int, limit: Int? = null, variant: String = ""): BuyResult {
        if (!Config.s.validAmount(qty)) return BuyResult.OutOfRange
        val lot = Config.s.lotOf(item)
        val plan = Matching.plan(item, qty - qty % lot, buyer, limit)
        if (plan.filled <= 0) return BuyResult.NothingAvailable
        val total = Money.spurs(plan.totalSpurs) ?: return BuyResult.OutOfRange
        val buyerId = UUID.fromString(buyer)
        if (wallet.balance(buyerId) < total) return BuyResult.InsufficientFunds
        val fills = plan.fills.map { it.toLedger() }
        val scale = Variants.of(variant)?.takeIf { it.core == item }
        val intent = Intent(Market.nextId(), IntentType.BUY, LedgerState.PENDING, actor = buyer, item = item, qty = plan.filled, netSpurs = plan.totalSpurs, fills = fills, variant = variant, variantMul = scale?.mul ?: 1, variantDiv = scale?.div ?: 1, ver = 1)
        if (!debit(intent, buyerId, total, Memo(Flow.MARKET_BUY, counterpartyOf(intent.fills), intent.item, intent.qty))) return BuyResult.InsufficientFunds
        try {
            if (!deliverBuy(intent.copy(state = LedgerState.DEBITED), true)) return BuyResult.Frozen
        } catch (e: Exception) {
            LOG.error("buy {} left a ledger intent open for recovery", intent.id, e)
            return BuyResult.IntentOpen
        }
        fills.forEach { Notify.tell(it.owner, Tone.OK, "sold") { arrayOf(Notify.name(buyer), Notify.stack(item, it.qty), Phrase.money(net(it))) } }
        return BuyResult.Ok(plan.filled, plan.totalSpurs)
    }

    private fun deliverBuy(start: Intent, fresh: Boolean = false): Boolean {
        var intent = start
        if ((fresh || intent.ver >= 1 && !intent.applied) && !Market.queued(intent.actor, "${intent.id}:d")) {
            for (idx in intent.fills.indices) {
                val f = intent.fills[idx]
                if (f.owner.isEmpty() || f.qty <= 0 || sellLeft(intent.item, f.orderId) >= f.qty) continue
                intent = dropSell(intent, idx) ?: return false
            }
            if (intent.qty <= 0) { write(intent.copy(state = LedgerState.PAID)); return true }
        }
        val washedOut = if (intent.applied || Market.queued(intent.actor, "${intent.id}:d")) intent.fills.indices.toSet() else Matching.applyFills(intent.item, intent.fills.map { it.toPlan() }, intent.actor)
        if (!intent.applied) intent = intent.copy(applied = true).also(::write)
        if (intent.variant.isEmpty() || intent.variantMul == intent.variantDiv) Market.queueDelivery(intent.actor, intent.item, intent.qty, "${intent.id}:d")
        else {
            val whole = (intent.qty.toLong() * intent.variantDiv / intent.variantMul).toInt()
            val rest = intent.qty - (whole.toLong() * intent.variantMul / intent.variantDiv).toInt()
            if (whole > 0) Market.queueDelivery(intent.actor, intent.variant, whole, "${intent.id}:d")
            if (rest > 0) Market.queueDelivery(intent.actor, intent.item, rest, "${intent.id}:r")
        }
        payFills(intent.copy(state = LedgerState.BOOK_INSERTED).also(::write))
        tradedFills(intent.actor, intent.item, intent.fills, false, washedOut) { gross(it) + it.tariff }
        return true
    }

    private fun gross(f: LedgerFill): Long = if (f.gross >= 0) f.gross else f.qty.toLong() / f.lot.coerceAtLeast(1) * f.unitPrice

    private fun taxOf(f: LedgerFill): Long = if (f.tax >= 0) f.tax else Matching.tax(gross(f))

    private fun net(f: LedgerFill): Long = gross(f) - taxOf(f)

    fun cancel(owner: String, item: String, orderId: Long): Order? {
        val existing = Matching.bookFor(item).sells.firstOrNull { it.id == orderId && it.owner == owner } ?: return null
        val amount = existing.amount
        val id = Market.nextId()
        val base = Intent(id, IntentType.CANCEL, LedgerState.PENDING, actor = owner, item = item, qty = amount, orderId = orderId, unitPrice = existing.price)
        returnToOwner(base, { Matching.cancelOrder(item, orderId, owner) }, { Market.queueDelivery(owner, item, amount, "$id:d") })
        return existing
    }

    fun repriceStep(owner: String, item: String, newPrice: Int): Int? =
        Matching.bookFor(item).sells.firstOrNull { it.owner == owner && !Matching.isClean(it.amount / it.lot.coerceAtLeast(1), newPrice) }
            ?.let { Matching.step(newPrice) * it.lot.coerceAtLeast(1) }

    fun repriceAll(owner: String, item: String, newPrice: Int): Boolean {
        if (newPrice <= 0 || repriceStep(owner, item, newPrice) != null) return false
        if (Matching.crosses(item, newPrice, owner, false) || Matching.ownCross(owner, item, newPrice, false)) return false
        val orders = Matching.bookFor(item).sells.filter { it.owner == owner }
        if (orders.isEmpty()) return false
        orders.forEach { reprice(owner, item, it.id, newPrice) }
        return true
    }

    internal fun reprice(owner: String, item: String, orderId: Long, newPrice: Int) {
        val order = Matching.bookFor(item).sells.firstOrNull { it.id == orderId && it.owner == owner } ?: return
        if (order.price == newPrice) return
        val id = Market.nextId()
        write(Intent(id, IntentType.REPRICE, LedgerState.PENDING, actor = owner, item = item, orderId = orderId, unitPrice = newPrice))
        order.price = newPrice
        order.placedAt = now()
        Market.dirty = true
        write(Intent(id, IntentType.REPRICE, LedgerState.PAID, actor = owner, item = item, orderId = orderId, unitPrice = newPrice))
    }

    fun repriceBid(owner: String, item: String, newPrice: Int): OrderResult {
        if (!Config.s.validPrice(newPrice)) return OrderResult.OutOfRange
        val bid = Matching.bookFor(item).buys.firstOrNull { it.owner == owner } ?: return OrderResult.OutOfRange
        val lot = bid.lot.coerceAtLeast(1)
        if (!Matching.isClean(bid.amount / lot, newPrice)) return OrderResult.NotClean(Matching.step(newPrice) * lot)
        if (Matching.crosses(item, newPrice, owner, true)) return OrderResult.Crosses
        if (Matching.ownCross(owner, item, newPrice, true)) return OrderResult.OwnCross
        if (newPrice == bid.price) return OrderResult.Ok(bid.id)
        if (Money.spurs(bid.amount.toLong() / lot * newPrice) == null) return OrderResult.OutOfRange
        val delta = bid.amount.toLong() / lot * newPrice - Matching.escrow(bid)
        val id = Market.nextId()
        val intent = Intent(id, IntentType.REPRICE_BID, LedgerState.PENDING, actor = owner, item = item, orderId = bid.id, unitPrice = newPrice, netSpurs = delta, lot = lot)
        if (delta > 0) {
            val total = Money.spurs(delta) ?: return OrderResult.OutOfRange
            val buyerId = UUID.fromString(owner)
            if (wallet.balance(buyerId) < total) return OrderResult.InsufficientFunds
            if (!debit(intent, buyerId, total, Memo(Flow.MARKET_BUY, item = intent.item))) return OrderResult.InsufficientFunds
            repriceBidApply(intent.copy(state = LedgerState.DEBITED))
        } else {
            write(intent)
            repriceBidApply(intent)
        }
        return OrderResult.Ok(bid.id)
    }

    private fun alreadyCredited(who: String, pre: Long, amount: Long): Boolean {
        if (pre < 0) return false
        val actor = who.toUuidOrNull() ?: return false
        return wallet.balance(actor) - pre >= amount
    }

    private fun auctionMemo(intent: Intent, flow: Flow, counterparty: String = "", fee: Long = 0): Memo {
        val data = intent.stackData.ifEmpty { Auctions.find(intent.auctionId)?.stackData.orEmpty() }
        return if (data.isEmpty()) Memo(flow, counterparty, fee = fee) else Memo(flow, counterparty, StackCodec.itemId(data), StackCodec.count(data), fee)
    }

    private fun creditOrFreeze(intent: Intent, step: String, who: String, amount: Long, pre: Long, memo: Memo): Intent? {
        if (step in intent.done || alreadyCredited(who, pre, amount)) return intent
        var next = intent
        if (credit(who, amount, memo) { next = intent.mark(step) }) return next
        Market.freeze(intent.id)
        LOG.error("Freezing ledger intent {}, credit of {} to {} failed permanently", intent.id, amount, who)
        return null
    }

    private fun balanceOf(who: String): Long =
        who.toUuidOrNull()?.let { wallet.balance(it) } ?: -1

    private fun withBalances(intent: Intent): Intent = intent.copy(
        prevBalance = if (intent.prevBidder.isNotEmpty()) balanceOf(intent.prevBidder) else -1,
        sellerBalance = if (intent.seller.isNotEmpty()) balanceOf(intent.seller) else -1
    )

    private fun repriceBidApply(intent: Intent) {
        var current = intent
        if (current.state == LedgerState.PENDING || current.state == LedgerState.DEBITED) {
            Matching.bookFor(current.item).buys.firstOrNull { it.id == current.orderId }?.let { it.price = current.unitPrice; it.placedAt = now() }
            Market.dirty = true
            current = current.copy(state = LedgerState.BOOK_INSERTED)
            if (current.netSpurs < 0) {
                val actor = current.actor.toUuidOrNull()
                if (actor != null) current = current.copy(preBalance = wallet.balance(actor))
            }
            write(current)
        }
        val ok = current.netSpurs >= 0 || creditOrFreeze(current, "pay", current.actor, -current.netSpurs, current.preBalance, Memo(Flow.REFUND, item = current.item)) != null
        if (ok) write(current.copy(state = LedgerState.PAID))
    }

    fun cancelAll(owner: String, item: String): Int {
        val ids = Matching.bookFor(item).sells.filter { it.owner == owner }.map { it.id }
        return ids.sumOf { cancel(owner, item, it)?.amount ?: 0 }
    }

    fun auctionList(seller: String, stack: ItemStack, startPrice: Int, buyNowPrice: Int?, registries: HolderLookup.Provider): ListResult {
        if (Limits.auctionFull(seller)) return ListResult.Limit(Limits.auctionSlots(seller))
        if (buyNowPrice != null && buyNowPrice < startPrice) return ListResult.BadPrice
        val id = Market.nextId()
        val data = StackCodec.encode(stack, registries)
        if (data.length > MAX_STACK_DATA) return ListResult.TooLarge
        val label = stack.hoverName.string
        val intent = Intent(id, IntentType.AUCTION_LIST, LedgerState.PENDING, actor = seller, auctionId = id, stackData = data, label = label, unitPrice = startPrice, buyNow = buyNowPrice ?: -1)
        write(intent)
        try {
            Auctions.insert(id, seller, data, label, startPrice, buyNowPrice)
            write(intent.copy(state = LedgerState.PAID))
        } catch (e: Exception) {
            if (Auctions.find(id) == null) {
                write(intent.copy(state = LedgerState.VOID))
                throw e
            }
            LOG.error("auction_list {} bookkeeping failed after insert", id, e)
            write(intent.copy(state = LedgerState.PAID))
        }
        return ListResult.Ok(id)
    }

    fun auctionBid(bidder: String, auctionId: Long, amount: Int): BidResult {
        val a = Auctions.find(auctionId)?.takeIf { it.state == AuctionState.OPEN && now() < it.expiresAt && it.seller != bidder } ?: return BidResult.NotFound
        if (!Trade.canTrade(bidder, a.seller)) return BidResult.Embargoed(Trade.country(a.seller).orEmpty())
        if (amount <= 0) return BidResult.TooLow
        if (a.buyNowPrice?.let { amount >= it && a.currentBid < it } == true) return when (val r = auctionBuyNow(bidder, auctionId)) {
            BuyNowResult.Ok -> BidResult.BoughtOut
            BuyNowResult.InsufficientFunds -> BidResult.InsufficientFunds
            BuyNowResult.NotAvailable -> BidResult.NotFound
            is BuyNowResult.Embargoed -> BidResult.Embargoed(r.country)
        }
        if (amount < Auctions.minBid(a)) return BidResult.TooLow
        val bidderId = UUID.fromString(bidder)
        if (wallet.balance(bidderId) < amount) return BidResult.InsufficientFunds
        val intent = withBalances(Intent(Market.nextId(), IntentType.AUCTION_BID, LedgerState.PENDING, actor = bidder, auctionId = auctionId, unitPrice = amount, prevBidder = a.currentBidder, prevBid = a.currentBid))
        if (!debit(intent, bidderId, amount, auctionMemo(intent, Flow.AUCTION_BUY, a.seller))) return BidResult.InsufficientFunds
        applyBid(intent)
        if (intent.prevBidder != bidder) Notify.tell(intent.prevBidder, Tone.WARN, "outbid") { arrayOf(Notify.name(bidder), Notify.label(a.label), Phrase.money(amount.toLong())) }
        Notify.tell(a.seller, Tone.INFO, "bid") { arrayOf(Notify.name(bidder), Notify.label(a.label), Phrase.money(amount.toLong())) }
        return BidResult.Ok
    }

    fun auctionBuyNow(buyer: String, auctionId: Long): BuyNowResult {
        val a = Auctions.find(auctionId)?.takeIf { it.state == AuctionState.OPEN && now() < it.expiresAt && it.seller != buyer } ?: return BuyNowResult.NotAvailable
        val price = a.buyNowPrice?.takeIf { a.currentBid < it } ?: return BuyNowResult.NotAvailable
        if (!Trade.canTrade(buyer, a.seller)) return BuyNowResult.Embargoed(Trade.country(a.seller).orEmpty())
        val buyerId = UUID.fromString(buyer)
        if (wallet.balance(buyerId) < price) return BuyNowResult.InsufficientFunds
        val intent = withBalances(settleIntent(a, buyer, price).copy(prevBidder = a.currentBidder, prevBid = a.currentBid, instant = true))
        if (!debit(intent, buyerId, price, auctionMemo(intent, Flow.AUCTION_BUY, a.seller))) return BuyNowResult.InsufficientFunds
        settle(intent.copy(state = LedgerState.DEBITED))
        if (intent.prevBidder != buyer) Notify.tell(intent.prevBidder, Tone.WARN, "bought_out") { arrayOf(Notify.label(a.label), Notify.name(buyer)) }
        Notify.tell(a.seller, Tone.OK, "auction_bought") { arrayOf(Notify.name(buyer), Notify.label(a.label), Phrase.money(price.toLong())) }
        return BuyNowResult.Ok
    }

    fun auctionCancel(seller: String, auctionId: Long): Boolean {
        val a = Auctions.find(auctionId)?.takeIf { it.state == AuctionState.OPEN && it.seller == seller && it.currentBidder.isEmpty() } ?: return false
        val id = Market.nextId()
        val base = Intent(id, IntentType.AUCTION_CANCEL, LedgerState.PENDING, actor = seller, auctionId = a.id, stackData = a.stackData)
        returnToOwner(base, { Auctions.cancelled(a.id) }, { Market.queueStackDelivery(seller, a.stackData, "$id:d") })
        return true
    }

    fun auctionSweepSettle(auctionId: Long) {
        val a = Auctions.find(auctionId) ?: return
        if (a.state != AuctionState.OPEN || a.currentBidder.isEmpty()) return
        settle(withBalances(settleIntent(a, a.currentBidder, a.currentBid)).also(::write))
        Notify.tell(a.seller, Tone.OK, "auction_sold") { arrayOf(Notify.label(a.label), Notify.name(a.currentBidder), Phrase.money(a.currentBid.toLong())) }
        Notify.tell(a.currentBidder, Tone.OK, "auction_won") { arrayOf(Notify.label(a.label), Phrase.money(a.currentBid.toLong())) }
    }

    fun auctionSweepExpireUnsold(auctionId: Long) {
        val a = Auctions.find(auctionId) ?: return
        if (a.state != AuctionState.OPEN) return
        val id = Market.nextId()
        val base = Intent(id, IntentType.AUCTION_CANCEL, LedgerState.PENDING, actor = a.seller, auctionId = a.id, stackData = a.stackData)
        returnToOwner(base, { Auctions.expire(a.id) }, { Market.queueStackDelivery(a.seller, a.stackData, "$id:d") })
        Notify.tell(a.seller, Tone.INFO, "auction_expired") { arrayOf(Notify.label(a.label)) }
    }

    private fun settleIntent(a: Auction, buyer: String, price: Int): Intent {
        val tariff = CleanStep.charge(price.toLong(), Trade.terms(buyer, a.seller).tariffPct)
        val fee = (Config.s.auctionFeeBps * (1.0 - (a.seller.toUuidOrNull()?.let { Skills.value(it, Skills.AUCTION_FEE) } ?: 0.0))).toInt()
        val net = (price - CleanStep.bps(price.toLong(), fee) - tariff).coerceAtLeast(0)
        return Intent(Market.nextId(), IntentType.AUCTION_SETTLE, LedgerState.PENDING, actor = buyer, seller = a.seller, auctionId = a.id, unitPrice = price, netSpurs = net, tariff = tariff, stackData = a.stackData)
    }

    private fun settle(intent: Intent) {
        var current = intent
        if (current.state != LedgerState.BOOK_INSERTED) {
            current = refundPrev(current) ?: return
            Auctions.settle(current.auctionId, current.actor, current.unitPrice)
            current = creditTariff(current, "tariff", current.actor, current.seller, current.tariff)
            current = current.copy(state = LedgerState.BOOK_INSERTED, sellerBalance = balanceOf(current.seller))
            write(current)
        }
        Market.queueStackDelivery(current.actor, current.stackData, "${current.id}:d")
        creditOrFreeze(current, "pay", current.seller, current.netSpurs, current.sellerBalance, auctionMemo(current, Flow.AUCTION_SELL, current.actor, fee(current).coerceAtLeast(0)))?.let {
            bankFee(it)
            write(it.copy(state = LedgerState.PAID))
        }
        val item = StackCodec.itemId(current.stackData)
        val count = StackCodec.count(current.stackData)
        traded(current.actor, current.seller, current.unitPrice.toLong(), item, count, false, orders = false)
        traded(current.seller, current.actor, current.netSpurs, item, count, true, orders = false)
        crossTrade(Trade.country(current.seller), Trade.country(current.actor), current.unitPrice.toLong())
        if (earnsXp(current.seller, current.actor)) Trade.country(current.seller)?.let { KamiProgress.post(it, "auction_sold", item, count.toLong()) }
    }

    private fun applyBid(intent: Intent) {
        var current = intent
        if (current.state != LedgerState.BOOK_INSERTED) {
            Auctions.applyBid(current.auctionId, current.actor, current.unitPrice)
            current = current.copy(state = LedgerState.BOOK_INSERTED)
            write(current)
        }
        refundPrev(current)?.let { write(it.copy(state = LedgerState.PAID)) }
    }

    private fun payFills(start: Intent) {
        var intent = start
        bankStock(intent)
        for (idx in intent.fills.indices) {
            val f = intent.fills[idx]
            if (f.owner.isEmpty() || f.orderId in intent.paid) continue
            val pre = if (f.pre >= 0) f.pre else balanceOf(f.owner)
            if (f.pre < 0) {
                intent = intent.copy(fills = intent.fills.mapIndexed { i, x -> if (i == idx) x.copy(pre = pre) else x })
                write(intent)
            }
            intent = creditOrFreeze(intent, "pay:$idx", f.owner, net(f), pre, Memo(Flow.MARKET_SELL, intent.actor, intent.item, f.qty)) ?: return
            bank(intent.id, "tax:$idx", f.owner, taxOf(f), Flow.TAX, taxNote(intent))
        taxed(f.owner, intent.item, taxOf(f))
            intent = creditTariff(intent, "tariff:$idx", intent.actor, intent.actor, f.tariff)
            intent = intent.copy(paid = intent.paid + f.orderId).also(::write)
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
        if (!attached) return
        val pending = open.values.toList()
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
                        Matching.insertSell(intent.item, Order(intent.id, intent.actor, intent.unitPrice, intent.qty, lot = intent.lot, peg = intent.peg, pegLimit = intent.pegLimit))
                        write(intent.copy(state = LedgerState.PAID))
                    } else {
                        val current = if (intent.state == LedgerState.PENDING) stockSold(intent) else intent
                        creditOrFreeze(current, "pay", current.actor, current.netSpurs, current.preBalance, Memo(Flow.MARKET_SELL, counterpartyOf(current.fills).ifEmpty { ServerParty.key }, current.item, current.qty))?.let {
                            bankSale(it)
                            write(it.copy(state = LedgerState.PAID))
                            traded(current.actor, "", current.netSpurs, current.item, current.qty, true)
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
                    Matching.bookFor(intent.item).sells.firstOrNull { it.id == intent.orderId }?.let { it.price = intent.unitPrice; it.placedAt = now() }
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
                    if (intent.state == LedgerState.PENDING && (intent.prevBidder.isNotEmpty() || Auctions.find(intent.auctionId)?.state == AuctionState.OPEN)) Auctions.cancelled(intent.auctionId)
                    returnAuction(intent)
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
