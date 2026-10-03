package kami.economy.net

import kami.economy.LOG
import kami.libs.claims.ClaimsApi
import kami.libs.claims.Locks
import kami.libs.text.Phrase
import kami.economy.Config
import kami.economy.KamiEconomy
import kami.economy.Market
import kami.economy.economy.Blacklist
import kami.economy.economy.BidResult
import kami.economy.economy.BuyNowResult
import kami.economy.economy.ListResult
import kami.economy.economy.Classification
import kami.economy.economy.Gate
import kami.economy.economy.Ledger
import kami.economy.economy.Limits
import kami.economy.economy.Matching
import kami.economy.economy.Notify
import kami.economy.economy.Stocks
import kami.economy.economy.SellResult
import kami.economy.economy.BuyResult
import kami.economy.economy.OrderResult
import kami.economy.client.ClientHooks
import kami.libs.net.ActPayload
import kami.libs.net.Packets
import kami.libs.net.SnapshotPayload
import kami.libs.net.TickCooldown
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import kotlin.math.abs

private val netPackets = Packets.forMod(KamiEconomy.ID)
private val actChannel = ActPayload.channel(netPackets, maxArgs = 4, maxArg = 96, withCountry = false)
private val snapshotChannel = SnapshotPayload.channel(netPackets, maxBytes = 262_144)

class PriceEntry(val item: String, val price: Int)

class PriceDelta(val entries: List<PriceEntry>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PriceDelta>(netPackets.id("price_delta"))
        val CODEC = netPackets.codec<PriceDelta>(
            { b, v -> b.writeCollection(v.entries) { bb, e -> bb.writeUtf(e.item, 64); bb.writeVarInt(e.price) } },
            { b -> PriceDelta(b.readList { bb -> PriceEntry(bb.readUtf(64), bb.readVarInt()) }) }
        )
    }
}

object Net {
    private val lastAct = TickCooldown(0)
    private val lastPrice = HashMap<String, Int>()
    private val openPlayers = HashSet<UUID>()

    fun act(name: String, args: List<String> = emptyList()) = actChannel(name, args)
    fun snapshot(json: String) = snapshotChannel(json)

    fun register(e: RegisterPayloadHandlersEvent) {
        val r = e.registrar("1").optional()
        r.playToServer(actChannel.type, actChannel.codec) { a, ctx -> (ctx.player() as? ServerPlayer)?.let { handle(it, a) } }
        r.playToClient(snapshotChannel.type, snapshotChannel.codec) { s, _ -> ClientHooks.snapshot(s) }
        r.playToClient(PriceDelta.TYPE, PriceDelta.CODEC) { d, _ -> ClientHooks.priceDelta(d) }
    }

    private fun allPrices(): List<PriceEntry> {
        val items = (Market.data.books.keys + Stocks.items()).distinct()
        return items.mapNotNull { item ->
            val price = Matching.effectiveSellPrice(item) ?: return@mapNotNull null
            PriceEntry(item, price)
        }
    }

    fun pushPrices(server: MinecraftServer) {
        val changes = allPrices().filter { e ->
            val prev = lastPrice[e.item]
            val threshold = (prev ?: 0) * Config.s.priceDeltaThresholdPct
            (prev == null || abs(e.price - prev) > threshold).also { if (it) lastPrice[e.item] = e.price }
        }
        if (changes.isEmpty()) return
        val payload = PriceDelta(changes)
        server.playerList.players.forEach { PacketDistributor.sendToPlayer(it, payload) }
    }

    fun sendFullPrices(p: ServerPlayer) {
        val all = allPrices()
        if (all.isEmpty()) return
        PacketDistributor.sendToPlayer(p, PriceDelta(all))
    }

    fun canOpen(p: ServerPlayer) = p.connection.hasChannel(snapshotChannel.type)

    fun send(p: ServerPlayer, msg: String = "", ok: Boolean = true, open: Boolean = false) {
        if (canOpen(p)) PacketDistributor.sendToPlayer(p, snapshot(Sync.encode(p, msg, ok, open)))
    }

    fun forget(p: ServerPlayer) {
        lastAct.forget(p.uuid)
        openPlayers.remove(p.uuid)
        Sync.forget(p)
    }

    fun broadcastOpen(server: MinecraftServer, except: UUID? = null) {
        if (openPlayers.isEmpty()) return
        openPlayers.forEach { uuid ->
            if (uuid == except) return@forEach
            server.playerList.getPlayer(uuid)?.let { send(it) }
        }
    }

    private fun handle(p: ServerPlayer, a: ActPayload) {
        when (a.name) {
            "open" -> { openPlayers += p.uuid; send(p, open = true) }
            "close" -> openPlayers -= p.uuid
            "view" -> {
                Sync.view(p, a.args.getOrNull(0) ?: "dashboard", a.args.getOrNull(1) ?: "")
                send(p)
            }
            "detail" -> {
                Sync.detail(p, a.args.getOrNull(0) ?: "", a.args.getOrNull(1) ?: "week")
                send(p)
            }
            "notify" -> {
                Notify.mute(p.stringUUID, a.args.getOrNull(0) ?: "", a.args.getOrNull(1) == "off")
                send(p)
            }
            "quote" -> {
                Sync.quote(p, a.args.getOrNull(0) ?: "", a.args.getOrNull(1) ?: "", a.args.getOrNull(2)?.toIntOrNull() ?: 0, a.args.getOrNull(3)?.toIntOrNull() ?: 0)
                send(p)
            }
            else -> {
                val tick = p.server.tickCount
                if (!lastAct.ready(p.uuid, tick, Config.s.guiCooldown)) return
                val revision = Market.revision
                val (msg, ok) = try {
                    act(p, a.name, a.args)
                } catch (e: Exception) {
                    LOG.error("Action ${a.name} failed", e)
                    Phrase.of("kami_economy.action.error") to false
                }
                send(p, msg.json(), ok)
                if (ok && Market.revision != revision) broadcastOpen(p.server, except = p.uuid)
            }
        }
    }

    private fun outOfRange() = Phrase.of("kami_economy.action.out_of_range", Config.s.maxAmount, Phrase.money(Config.s.maxPrice.toLong())) to false

    private fun notClean(step: Int) = Phrase.of("kami_economy.action.not_clean", step) to false

    private fun withReturn(msg: Phrase, item: String, count: Int, reason: Phrase): Phrase =
        if (count > 0) Phrase.of("kami_economy.action.with_return", msg, Notify.stack(item, count), reason) else msg

    private fun stepReason(step: Int, lot: Int): Phrase =
        if (step > lot) Phrase.of("kami_economy.action.return.step", step) else Phrase.of("kami_economy.action.return.lot", lot)

    private class Fail(val phrase: Phrase) : Exception()
    private fun fail(key: String): Nothing = throw Fail(Phrase.of(key))
    private fun List<String>.itemArg(i: Int = 0): String = getOrNull(i) ?: fail("kami_economy.action.invalid_item")
    private fun List<String>.intArg(i: Int, key: String): Int = getOrNull(i)?.toIntOrNull() ?: fail(key)

    private fun orderReply(me: String, result: OrderResult, onOk: (OrderResult.Ok) -> Pair<Phrase, Boolean>): Pair<Phrase, Boolean> = when (result) {
        is OrderResult.Ok -> onOk(result)
        OrderResult.OutOfRange -> outOfRange()
        is OrderResult.NotClean -> notClean(result.step)
        OrderResult.InsufficientFunds -> Phrase.of("kami_economy.action.no_funds") to false
        OrderResult.Crosses -> Phrase.of("kami_economy.action.bid_crosses") to false
        OrderResult.Exists -> Phrase.of("kami_economy.action.bid_exists") to false
        is OrderResult.Limit -> Limits.marketDenial(me) to false
    }

    private fun sell(p: ServerPlayer, me: String, market: Boolean, args: List<String>): Pair<Phrase, Boolean> {
        val item = args.itemArg()
        val qty = args.intArg(1, "kami_economy.action.invalid_quantity")
        val price = if (market) 1 else args.intArg(2, "kami_economy.action.invalid_price")
        if (qty <= 0 || price <= 0) return Phrase.of("kami_economy.action.invalid_sell") to false
        if (!Config.s.validAmount(qty) || !Config.s.validPrice(price)) return outOfRange()
        val matching = p.inventory.items.filter { !it.isEmpty && Blacklist.itemId(it) == item && Blacklist.classify(it) == Classification.ALLOWED }
        if (matching.sumOf { it.count } < qty) return Phrase.of("kami_economy.action.not_enough_items", qty) to false
        val lot = Config.s.lotOf(item)
        val listPrice = Matching.listPrice(me, item, price)
        val step = Matching.step(listPrice) * lot
        var remaining = qty
        for (stack in matching) {
            if (remaining <= 0) break
            val take = minOf(remaining, stack.count)
            stack.shrink(take)
            remaining -= take
        }
        fun restore(count: Int) {
            if (count > 0 && !KamiEconomy.give(p, item, count)) {
                LOG.error("Could not restore {} x{} to {}, queued as delivery", item, count, p.name.string)
                Market.queueDelivery(me, item, count, parked = true)
            }
        }
        val attempt = runCatching { if (market) Ledger.sellNow(me, item, qty) else Ledger.sell(me, item, qty, price) }
        if (attempt.isFailure) {
            LOG.error("sell failed for $item", attempt.exceptionOrNull())
            restore(qty)
            return Phrase.of("kami_economy.action.sell_failed") to false
        }
        return when (val result = attempt.getOrThrow()) {
            is SellResult.Sold -> {
                restore(qty - result.filled)
                KamiEconomy.deliver(p)
                withReturn(Phrase.of("kami_economy.action.sold", result.filled, item), item, qty % lot, Phrase.of("kami_economy.action.return.lot", lot)) to true
            }
            is SellResult.Ok -> {
                val returned = qty - result.filled - result.listed
                restore(returned)
                KamiEconomy.deliver(p)
                val msg = when {
                    result.filled <= 0 -> Phrase.of("kami_economy.action.listed", result.listed, item, Phrase.money(listPrice.toLong()))
                    result.listed <= 0 -> Phrase.of("kami_economy.action.sold", result.filled, item)
                    else -> Phrase.of("kami_economy.action.listed_part", result.filled, item, result.listed, Phrase.money(listPrice.toLong()))
                }
                val reason = if (result.limited) Phrase.of("kami_economy.action.return.slots") else stepReason(step, lot)
                withReturn(msg, item, returned, reason) to true
            }
            is SellResult.IntentOpen -> {
                restore(qty - result.kept)
                Phrase.of("kami_economy.action.sell_processing") to false
            }
            else -> {
                restore(qty)
                when (result) {
                    is SellResult.NotClean -> notClean(result.step)
                    SellResult.Full -> Phrase.of("kami_economy.market.stock.full") to false
                    is SellResult.Limit -> Limits.marketDenial(me) to false
                    SellResult.NoBuyers -> Phrase.of("kami_economy.action.no_buyers") to false
                    else -> Phrase.of("kami_economy.action.sell_failed") to false
                }
            }
        }
    }

    private fun known(item: String): Boolean = Stocks.good(item) != null || Blacklist.sellable(item)

    private fun bid(me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = args.itemArg()
        val qty = args.intArg(1, "kami_economy.action.invalid_quantity")
        val price = args.intArg(2, "kami_economy.action.invalid_price")
        if (!known(item)) return Phrase.of("kami_economy.action.invalid_item") to false
        val lot = Config.s.lotOf(item)
        return orderReply(me, Ledger.bid(me, item, qty, price)) {
            withReturn(Phrase.of("kami_economy.action.bid_order", it.qty, item, Phrase.money(price.toLong())), item, qty - it.qty, stepReason(Matching.step(price) * lot, lot)) to true
        }
    }

    private fun cancelBid(me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = args.itemArg()
        val refund = Ledger.cancelBid(me, item) ?: return Phrase.of("kami_economy.action.no_bid") to false
        return Phrase.of("kami_economy.action.bid_cancelled", Phrase.money(refund)) to true
    }

    private fun embargo(country: String) = Locks.embargo(ClaimsApi.country(country)?.name ?: country)

    private fun auctionList(p: ServerPlayer, me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = args.itemArg()
        val qty = args.intArg(1, "kami_economy.action.invalid_quantity")
        val startPrice = args.intArg(2, "kami_economy.action.invalid_price")
        val buyNow = args.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
        if (qty <= 0 || startPrice <= 0) return Phrase.of("kami_economy.action.invalid_listing") to false
        if (!Config.s.validPrice(startPrice) || (buyNow != null && !Config.s.validPrice(buyNow))) return outOfRange()
        val stack = p.inventory.items.firstOrNull { !it.isEmpty && Blacklist.itemId(it) == item && it.count >= qty && Blacklist.classify(it) != Classification.BLOCKED }
            ?: return Phrase.of("kami_economy.action.not_enough_items", qty) to false
        if (Limits.auctionFull(me)) return Limits.auctionDenial(me) to false
        val taken = stack.copyWithCount(qty)
        stack.shrink(qty)
        val listed = runCatching { Ledger.auctionList(me, taken, startPrice, buyNow, p.server.registryAccess()) }
        if (listed.getOrNull() !is ListResult.Ok) {
            listed.exceptionOrNull()?.let { LOG.error("auction_list failed for ${taken.item}", it) }
            if (!p.inventory.add(taken)) p.drop(taken, false)
            return if (listed.isFailure) Phrase.of("kami_economy.action.auction_failed") to false else Limits.auctionDenial(me) to false
        }
        return Phrase.of("kami_economy.action.auction_listed", taken.hoverName.string) to true
    }

    private fun auctionBid(me: String, args: List<String>): Pair<Phrase, Boolean> {
        val id = args.getOrNull(0)?.toLongOrNull() ?: return Phrase.of("kami_economy.action.invalid_auction") to false
        val amount = args.getOrNull(1)?.toIntOrNull() ?: return Phrase.of("kami_economy.action.invalid_bid") to false
        if (amount > Config.s.maxPrice) return outOfRange()
        return when (val result = Ledger.auctionBid(me, id, amount)) {
            BidResult.Ok -> Phrase.of("kami_economy.action.bid_placed") to true
            BidResult.TooLow -> Phrase.of("kami_economy.action.bid_low") to false
            BidResult.InsufficientFunds -> Phrase.of("kami_economy.action.no_funds") to false
            BidResult.NotFound -> Phrase.of("kami_economy.action.auction_missing") to false
            is BidResult.Embargoed -> embargo(result.country) to false
        }
    }

    private fun auctionBuy(p: ServerPlayer, me: String, args: List<String>): Pair<Phrase, Boolean> {
        val id = args.getOrNull(0)?.toLongOrNull() ?: return Phrase.of("kami_economy.action.invalid_auction") to false
        return when (val result = Ledger.auctionBuyNow(me, id)) {
            BuyNowResult.Ok -> { KamiEconomy.deliver(p); Phrase.of("kami_economy.action.purchased") to true }
            BuyNowResult.InsufficientFunds -> Phrase.of("kami_economy.action.no_funds") to false
            BuyNowResult.NotAvailable -> Phrase.of("kami_economy.action.no_buyout") to false
            is BuyNowResult.Embargoed -> embargo(result.country) to false
        }
    }

    private fun auctionCancel(p: ServerPlayer, me: String, args: List<String>): Pair<Phrase, Boolean> {
        val id = args.getOrNull(0)?.toLongOrNull() ?: return Phrase.of("kami_economy.action.invalid_auction") to false
        return if (Ledger.auctionCancel(me, id)) { KamiEconomy.deliver(p); Phrase.of("kami_economy.action.auction_cancelled") to true } else Phrase.of("kami_economy.action.auction_cancel_denied") to false
    }

    private fun buy(p: ServerPlayer, me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = args.itemArg()
        val qty = args.intArg(1, "kami_economy.action.invalid_quantity")
        if (!known(item)) return Phrase.of("kami_economy.action.invalid_item") to false
        return when (val result = Ledger.buy(me, item, qty)) {
            is BuyResult.Ok -> {
                KamiEconomy.deliver(p)
                Phrase.of("kami_economy.action.bought", result.filled, item, Phrase.money(result.spent)) to true
            }
            BuyResult.InsufficientFunds -> Phrase.of("kami_economy.action.no_funds") to false
            BuyResult.NothingAvailable -> Phrase.of("kami_economy.action.nothing_available") to false
            BuyResult.OutOfRange -> outOfRange()
        }
    }

    private fun cancel(p: ServerPlayer, me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = args.itemArg()
        val amount = Ledger.cancelAll(me, item)
        KamiEconomy.deliver(p)
        return if (amount > 0) Phrase.plural("kami_economy.action.listing_cancelled", amount.toLong()) to true else Phrase.of("kami_economy.action.no_listing") to false
    }

    private fun reprice(me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = args.itemArg()
        val newPrice = args.intArg(1, "kami_economy.action.invalid_price")
        if (!Config.s.validPrice(newPrice)) return outOfRange()
        Ledger.repriceStep(me, item, newPrice)?.let { return notClean(it) }
        return if (Ledger.repriceAll(me, item, newPrice)) Phrase.of("kami_economy.action.repriced", Phrase.money(newPrice.toLong())) to true else Phrase.of("kami_economy.action.no_listing") to false
    }

    private fun repriceBid(me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = args.itemArg()
        val newPrice = args.intArg(1, "kami_economy.action.invalid_price")
        if (!Config.s.validPrice(newPrice)) return outOfRange()
        return orderReply(me, Ledger.repriceBid(me, item, newPrice)) { Phrase.of("kami_economy.action.repriced", Phrase.money(newPrice.toLong())) to true }
    }

    private fun act(p: ServerPlayer, name: String, args: List<String>): Pair<Phrase, Boolean> {
        val me = p.stringUUID
        Gate.denial(p.uuid, name)?.let { return it to false }
        return try {
            when (name) {
                "sell", "sell_market" -> sell(p, me, name == "sell_market", args)
                "bid" -> bid(me, args)
                "cancel_bid" -> cancelBid(me, args)
                "auction_list" -> auctionList(p, me, args)
                "auction_bid" -> auctionBid(me, args)
                "auction_buy" -> auctionBuy(p, me, args)
                "auction_cancel" -> auctionCancel(p, me, args)
                "buy" -> buy(p, me, args)
                "cancel" -> cancel(p, me, args)
                "reprice" -> reprice(me, args)
                "reprice_bid" -> repriceBid(me, args)
                "claim" -> { KamiEconomy.deliver(p); Phrase.of("kami_economy.action.claimed") to true }
                else -> Phrase.of("kami_economy.action.unknown") to false
            }
        } catch (f: Fail) {
            f.phrase to false
        }
    }
}
