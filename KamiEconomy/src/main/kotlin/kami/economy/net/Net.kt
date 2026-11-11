package kami.economy.net

import kami.economy.LOG
import kami.libs.admin.Subject
import kami.libs.claims.ClaimsApi
import kami.libs.claims.Locks
import kami.libs.text.Phrase
import kami.economy.Config
import kami.economy.KamiEconomy
import kami.economy.Market
import kami.economy.Order
import kami.economy.economy.Auctions
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
import kami.economy.economy.Pricing
import kami.economy.economy.Stocks
import kami.economy.economy.Variant
import kami.economy.economy.Variants
import kami.economy.economy.StackCodec
import kami.economy.economy.TradeStatus
import kami.economy.economy.Watchlist
import kami.economy.economy.SellResult
import kami.economy.economy.BuyResult
import kami.economy.economy.OrderResult
import kami.economy.client.ClientHooks
import kami.economy.client.villager.VillagerClient
import kami.economy.villager.VillagerSync
import kami.economy.quest.QuestBoost
import kami.economy.quest.Quests
import kami.libs.stats.Staff
import kami.libs.net.ActPayload
import kami.libs.net.Packets
import kami.libs.net.SnapshotPayload
import kami.libs.net.TickCooldown
import kami.libs.util.RateLimit
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import kotlin.math.abs

private val netPackets = Packets.forMod(KamiEconomy.ID)
private val actChannel = ActPayload.channel(netPackets, maxArgs = 6, maxArg = 30000, withCountry = false)
private val snapshotChannel = SnapshotPayload.channel(netPackets, maxBytes = 262_144)

class PriceEntry(val item: String, val price: Int, val lot: Int = 1)

class PriceDelta(val entries: List<PriceEntry>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PriceDelta>(netPackets.id("price_delta"))
        val CODEC = netPackets.codec<PriceDelta>(
            { b, v -> b.writeCollection(v.entries) { bb, e -> bb.writeUtf(e.item, 64); bb.writeVarInt(e.price); bb.writeVarInt(e.lot) } },
            { b -> PriceDelta(b.readList { bb -> PriceEntry(bb.readUtf(64), bb.readVarInt(), bb.readVarInt()) }) }
        )
    }
}

class VariantsPayload(val map: Map<String, Variant>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<VariantsPayload>(netPackets.id("variants"))
        val CODEC = netPackets.codec<VariantsPayload>(
            { b, v -> b.writeCollection(v.map.entries) { bb, e -> bb.writeUtf(e.key, 128); bb.writeUtf(e.value.core, 128); bb.writeVarInt(e.value.mul); bb.writeVarInt(e.value.div) } },
            { b -> VariantsPayload(b.readList { bb -> bb.readUtf(128) to Variant(bb.readUtf(128), bb.readVarInt(), bb.readVarInt()) }.toMap()) }
        )
    }
}

data class InfoVendor(val item: String, val dim: String, val x: Int, val y: Int, val z: Int, val price: Int, val count: Int, val sell: Boolean)

data class InfoQuest(val item: String, val lotPrice: Int, val lot: Int, val left: Int, val expiresAt: Long, val boosts: List<QuestBoost>)

private fun FriendlyByteBuf.writeVendor(v: InfoVendor) {
    writeUtf(v.item, 128); writeUtf(v.dim, 64); writeVarInt(v.x); writeVarInt(v.y); writeVarInt(v.z); writeVarInt(v.price); writeVarInt(v.count); writeBoolean(v.sell)
}

private fun FriendlyByteBuf.readVendor() = InfoVendor(readUtf(128), readUtf(64), readVarInt(), readVarInt(), readVarInt(), readVarInt(), readVarInt(), readBoolean())

private fun FriendlyByteBuf.writeQuest(q: InfoQuest) {
    writeUtf(q.item, 128); writeVarInt(q.lotPrice); writeVarInt(q.lot); writeVarInt(q.left); writeVarLong(q.expiresAt)
    writeCollection(q.boosts) { bb, e -> bb.writeUtf(e.kind, 32); bb.writeVarInt(e.pct); bb.writeVarInt(e.hours) }
}

private fun FriendlyByteBuf.readQuest() = InfoQuest(readUtf(128), readVarInt(), readVarInt(), readVarInt(), readVarLong(), readList { bb -> QuestBoost(bb.readUtf(32), bb.readVarInt(), bb.readVarInt()) })

class PriceInfoPayload(
    val item: String, val lot: Int, val bid: Int, val ask: Int, val change: Int, val tariff: Int, val embargo: Boolean,
    val closes: List<Int> = emptyList(), val vendors: List<InfoVendor> = emptyList(), val quests: List<InfoQuest> = emptyList()
) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PriceInfoPayload>(netPackets.id("price_info"))
        val CODEC = netPackets.codec<PriceInfoPayload>(
            { b, v ->
                b.writeUtf(v.item, 128); b.writeVarInt(v.lot); b.writeVarInt(v.bid); b.writeVarInt(v.ask); b.writeVarInt(v.change); b.writeVarInt(v.tariff); b.writeBoolean(v.embargo)
                b.writeCollection(v.closes) { bb, e -> bb.writeVarInt(e) }
                b.writeCollection(v.vendors) { bb, e -> bb.writeVendor(e) }
                b.writeCollection(v.quests) { bb, e -> bb.writeQuest(e) }
            },
            { b ->
                PriceInfoPayload(
                    b.readUtf(128), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readBoolean(),
                    b.readList { bb -> bb.readVarInt() }, b.readList { bb -> bb.readVendor() }, b.readList { bb -> bb.readQuest() }
                )
            }
        )
    }
}

class JeiPayload(val quests: List<InfoQuest>, val vendors: List<InfoVendor>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<JeiPayload>(netPackets.id("jei_data"))
        val CODEC = netPackets.codec<JeiPayload>(
            { b, v -> b.writeCollection(v.quests) { bb, e -> bb.writeQuest(e) }; b.writeCollection(v.vendors) { bb, e -> bb.writeVendor(e) } },
            { b -> JeiPayload(b.readList { bb -> bb.readQuest() }, b.readList { bb -> bb.readVendor() }) }
        )
    }
}

object Net {
    private val READS = setOf("open", "view", "detail", "notify", "watch", "watch_import", "quote", "price_info", "jei")
    private val reads = RateLimit<UUID>(20, 1000L)
    private val priceInfo = RateLimit<UUID>(4, 1000L)
    private val jeiLimit = RateLimit<UUID>(1, 5000L)
    private val ORDER_ACTS = setOf("cancel", "cancel_bid", "reprice", "reprice_bid")
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
        r.playToClient(VariantsPayload.TYPE, VariantsPayload.CODEC) { v, _ -> ClientHooks.variants(v.map) }
        r.playToClient(PriceInfoPayload.TYPE, PriceInfoPayload.CODEC) { v, _ -> ClientHooks.priceInfo(v) }
        r.playToClient(JeiPayload.TYPE, JeiPayload.CODEC) { v, _ -> ClientHooks.jei(v) }
        r.playToClient(VillagerSync.TYPE, VillagerSync.CODEC) { s, _ -> VillagerClient.sync(s) }
        WatchSync.register(r)
    }

    private fun allPrices(): List<PriceEntry> {
        val asks = Pricing.asks()
        return asks.items.filter { Variants.of(it) == null }.mapNotNull { item ->
            val price = asks.of(item) ?: Stocks.course(item).takeIf { it >= 1.0 }?.let { Math.round(it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() } ?: return@mapNotNull null
            PriceEntry(item, price, Config.s.lotOf(item))
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

    fun sendVariants(p: ServerPlayer) = PacketDistributor.sendToPlayer(p, VariantsPayload(Variants.map))

    fun broadcastVariants(server: MinecraftServer) = server.playerList.players.forEach(::sendVariants)

    fun canOpen(p: ServerPlayer) = p.connection.hasChannel(snapshotChannel.type)

    fun send(p: ServerPlayer, msg: String = "", ok: Boolean = true, open: Boolean = false) {
        if (canOpen(p)) snapshotChannel.send(p, Sync.encode(p, msg, ok, open))
    }

    fun forget(p: ServerPlayer) {
        lastAct.forget(p.uuid)
        reads.forget(p.uuid)
        priceInfo.forget(p.uuid)
        jeiLimit.forget(p.uuid)
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
        if (a.name in READS && !reads.allow(p.uuid)) return
        when (a.name) {
            "open" -> { openPlayers += p.uuid; send(p, open = true) }
            "close" -> openPlayers -= p.uuid
            "view" -> {
                Sync.view(p, a.args.getOrNull(0) ?: "dashboard", a.args.getOrNull(1) ?: "", a.args.getOrNull(2) ?: "", a.args.getOrNull(3) ?: "")
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
            "watch" -> {
                val item = Variants.core(a.args.getOrNull(0).orEmpty())
                if (a.args.getOrNull(1) == "off") Watchlist.remove(p.stringUUID, item) else if (known(item)) Watchlist.add(p.stringUUID, item)
                WatchSync.send(p)
                send(p)
            }
            "watch_import" -> {
                Watchlist.import(p.stringUUID, a.args.getOrNull(0).orEmpty().split(',').map(Variants::core).distinct().filter(::known).take(Watchlist.CAP))
                WatchSync.send(p)
                send(p)
            }
            "price_info" -> a.args.getOrNull(0)?.takeIf { it.length <= 128 && (Variants.of(it) != null || known(it)) && priceInfo.allow(p.uuid) }?.let { PacketDistributor.sendToPlayer(p, Sync.priceInfo(p, it)) }
            "jei" -> if (jeiLimit.allow(p.uuid)) PacketDistributor.sendToPlayer(p, Sync.jei(p))
            "quote" -> {
                val qty = a.args.getOrNull(2)?.toIntOrNull() ?: 0
                val price = a.args.getOrNull(3)?.toIntOrNull() ?: 0
                val item = a.args.getOrNull(1) ?: ""
                val ok = item.length <= 128 && Config.s.validAmount(qty) && (price == 0 || Config.s.validPrice(price))
                Sync.quote(p, a.args.getOrNull(0) ?: "", if (ok) item else "", if (ok) qty else 0, if (ok) price else 0)
                send(p)
            }
            else -> {
                val tick = p.server.tickCount
                if (!lastAct.ready(p.uuid, tick, Config.s.guiCooldown)) return
                if (a.name in ORDER_ACTS && runCatching { Subject.requireSelf(p, Sync.subject(p)?.toString()) }.isFailure)
                    return send(p, Phrase.of("kami_libs.admin.read_only").json(), false)
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

    private fun List<String>.pegArgs(from: Int, item: String): Pair<Int?, Int?> {
        val peg = getOrNull(from)?.takeIf { it.isNotEmpty() }?.also { if (Stocks.good(item) == null) fail("kami_economy.action.invalid_peg") }?.let { it.toIntOrNull()?.takeIf { v -> v in -500..500 } ?: fail("kami_economy.action.invalid_peg") } ?: return null to null
        return peg to (getOrNull(from + 1)?.toIntOrNull()?.takeIf { it > 0 } ?: fail("kami_economy.action.invalid_peg"))
    }

    private fun orderReply(me: String, result: OrderResult, onOk: (OrderResult.Ok) -> Pair<Phrase, Boolean>): Pair<Phrase, Boolean> = when (result) {
        is OrderResult.Ok -> onOk(result)
        OrderResult.OutOfRange -> outOfRange()
        is OrderResult.NotClean -> notClean(result.step)
        OrderResult.InsufficientFunds -> Phrase.of("kami_economy.action.no_funds") to false
        OrderResult.Crosses -> Phrase.of("kami_economy.action.bid_crosses") to false
        OrderResult.OwnCross -> Phrase.of("kami_economy.action.own_cross") to false
        OrderResult.Exists -> Phrase.of("kami_economy.action.bid_exists") to false
        is OrderResult.Limit -> Limits.marketDenial(me) to false
    }

    private fun giveBack(p: ServerPlayer, me: String, id: String, count: Int) {
        if (count > 0 && !KamiEconomy.give(p, id, count)) {
            LOG.error("Could not restore {} x{} to {}, queued as delivery", id, count, p.name.string)
            Market.queueDelivery(me, id, count, parked = true)
        }
    }

    private fun questDeliver(p: ServerPlayer, me: String, args: List<String>): Pair<Phrase, Boolean> {
        val id = args.getOrNull(0)?.toLongOrNull() ?: return Phrase.of("kami_economy.quest.closed") to false
        val raw = args.intArg(1, "kami_economy.action.invalid_quantity")
        if (!Config.s.validAmount(raw)) return outOfRange()
        if (Staff.active("p:$me")) return Phrase.of("kami_economy.quest.staff") to false
        val quest = Quests.get(id)?.takeIf { it.open && it.left > 0 } ?: return Phrase.of("kami_economy.quest.closed") to false
        val item = quest.item
        TradeStatus.denial("sell_market", item)?.let { return it to false }
        val lot = quest.lot
        val qty = minOf(raw, quest.left).let { it - it % lot }
        if (qty <= 0) return Phrase.of("kami_economy.action.below_lot", lot) to false
        val matching = p.inventory.items.filter { !it.isEmpty && Blacklist.itemId(it) == item && Blacklist.classify(it) == Classification.ALLOWED }
        if (matching.sumOf { it.count } < qty) return Phrase.of("kami_economy.action.not_enough_items", qty) to false
        var remaining = qty
        for (stack in matching) {
            if (remaining <= 0) break
            val take = minOf(remaining, stack.count)
            stack.shrink(take)
            remaining -= take
        }
        val attempt = runCatching { Ledger.questDeliver(me, id, qty) }
        if (attempt.isFailure) {
            LOG.error("quest_deliver failed for $item", attempt.exceptionOrNull())
            giveBack(p, me, item, qty)
            return Phrase.of("kami_economy.action.sell_failed") to false
        }
        return when (val result = attempt.getOrThrow()) {
            is SellResult.Sold -> {
                giveBack(p, me, item, qty - result.filled)
                KamiEconomy.deliver(p)
                Phrase.of("kami_economy.quest.delivered", result.filled, item, Phrase.money(result.net)) to true
            }
            is SellResult.IntentOpen -> {
                giveBack(p, me, item, qty - result.kept)
                Phrase.of("kami_economy.action.sell_processing") to false
            }
            is SellResult.Frozen -> {
                giveBack(p, me, item, qty - result.kept)
                Phrase.of("kami_economy.action.frozen") to false
            }
            else -> {
                giveBack(p, me, item, qty)
                (if (result == SellResult.NoBuyers) Phrase.of("kami_economy.quest.closed") else Phrase.of("kami_economy.action.sell_failed")) to false
            }
        }
    }

    private fun sell(p: ServerPlayer, me: String, market: Boolean, args: List<String>): Pair<Phrase, Boolean> {
        val variant = args.itemArg()
        val item = Variants.core(variant)
        val rawQty = args.intArg(1, "kami_economy.action.invalid_quantity")
        val held = if (variant == item) rawQty else rawQty - rawQty % Variants.lot(variant, Config.s.lotOf(item))
        val qty = Variants.coreQty(variant, held) ?: 0
        val price = if (market) 1 else args.intArg(2, "kami_economy.action.invalid_price")
        if (qty <= 0 || price <= 0) return Phrase.of("kami_economy.action.invalid_sell") to false
        val (peg, pegLimit) = if (market) null to null else args.pegArgs(3, item)
        if (!Config.s.validAmount(held) || !Config.s.validPrice(price)) return outOfRange()
        val matching = p.inventory.items.filter { !it.isEmpty && Blacklist.itemId(it) == variant && Blacklist.classify(it) == Classification.ALLOWED }
        if (matching.sumOf { it.count } < held) return Phrase.of("kami_economy.action.not_enough_items", held) to false
        val lot = Config.s.lotOf(item)
        val listPrice = Matching.listPrice(me, item, price)
        val step = Matching.step(listPrice) * lot
        var remaining = held
        for (stack in matching) {
            if (remaining <= 0) break
            val take = minOf(remaining, stack.count)
            stack.shrink(take)
            remaining -= take
        }
        fun restore(count: Int) {
            val v = Variants.of(variant)
            if (v == null) return giveBack(p, me, item, count)
            val whole = (count.toLong() * v.div / v.mul).toInt()
            giveBack(p, me, variant, whole)
            giveBack(p, me, item, count - (whole.toLong() * v.mul / v.div).toInt())
        }
        val attempt = runCatching { if (market) Ledger.sellNow(me, item, qty) else Ledger.sell(me, item, qty, price, peg, pegLimit) }
        if (attempt.isFailure) {
            LOG.error("sell failed for $item", attempt.exceptionOrNull())
            restore(qty)
            return Phrase.of("kami_economy.action.sell_failed") to false
        }
        return when (val result = attempt.getOrThrow()) {
            is SellResult.Sold -> {
                restore(qty - result.filled)
                KamiEconomy.deliver(p)
                val returned = qty - result.filled
                val reason = if (returned > qty % lot) Phrase.of("kami_economy.stock.capped") else Phrase.of("kami_economy.action.return.lot", lot)
                withReturn(Phrase.of("kami_economy.action.sold_net", result.filled, item, Phrase.money(result.net)), item, returned, reason) to true
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
            is SellResult.Frozen -> {
                restore(qty - result.kept)
                Phrase.of("kami_economy.action.frozen") to false
            }
            else -> {
                restore(qty)
                when (result) {
                    is SellResult.NotClean -> notClean(result.step)
                    SellResult.Full -> Phrase.of("kami_economy.market.stock.full") to false
                    is SellResult.Limit -> Limits.marketDenial(me) to false
                    SellResult.NoBuyers -> Phrase.of("kami_economy.action.no_buyers") to false
                    SellResult.OwnCross -> Phrase.of("kami_economy.action.own_cross") to false
                    else -> Phrase.of("kami_economy.action.sell_failed") to false
                }
            }
        }
    }

    private fun coreArg(args: List<String>) = Variants.core(args.itemArg())

    private fun coreQtyArg(args: List<String>, core: String): Int {
        val variant = args.itemArg()
        val raw = args.intArg(1, "kami_economy.action.invalid_quantity")
        if (!Config.s.validAmount(raw)) return 0
        if (variant == core) return raw
        val lot = Variants.lot(variant, Config.s.lotOf(core))
        return Variants.coreQty(variant, raw - raw % lot) ?: 0
    }

    private fun known(item: String): Boolean = Stocks.good(item) != null || Blacklist.sellable(item)

    private fun bid(p: ServerPlayer, me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = coreArg(args)
        val qty = coreQtyArg(args, item)
        val price = args.intArg(2, "kami_economy.action.invalid_price")
        if (!known(item)) return Phrase.of("kami_economy.action.invalid_item") to false
        val (peg, pegLimit) = args.pegArgs(3, item)
        val lot = Config.s.lotOf(item)
        return orderReply(me, Ledger.bid(me, item, qty, price, peg, pegLimit)) {
            if (it.filled > 0) KamiEconomy.deliver(p)
            val step = Matching.step(price) * lot
            val msg = when {
                it.filled <= 0 -> Phrase.of("kami_economy.action.bid_order", it.qty, item, Phrase.money(price.toLong()))
                it.qty <= 0 -> Phrase.of("kami_economy.action.bid_filled", it.filled, item)
                else -> Phrase.of("kami_economy.action.bid_part", it.filled, item, it.qty, Phrase.money(price.toLong()))
            }
            (if (qty % step != 0) Phrase.of("kami_economy.action.rounded", msg, stepReason(step, lot)) else msg) to true
        }
    }

    private fun cancelBid(me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = coreArg(args)
        var frozen = false
        val refund = Ledger.cancelBid(me, item, onFrozen = { frozen = true }) ?: return Phrase.of("kami_economy.action.no_bid") to false
        if (frozen) return Phrase.of("kami_economy.action.frozen") to false
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
        val slot = args.getOrNull(4)?.toIntOrNull()
        val stack = p.inventory.items.filterIndexed { index, _ -> slot == null || index == slot }
            .firstOrNull { !it.isEmpty && Blacklist.itemId(it) == item && it.count >= qty && Blacklist.classify(it) != Classification.BLOCKED }
            ?: return Phrase.of("kami_economy.action.not_enough_items", qty) to false
        if (Limits.auctionFull(me)) return Limits.auctionDenial(me) to false
        val taken = stack.copyWithCount(qty)
        stack.shrink(qty)
        val listed = runCatching { Ledger.auctionList(me, taken, startPrice, buyNow, p.server.registryAccess()) }
        if (listed.getOrNull() !is ListResult.Ok) {
            listed.exceptionOrNull()?.let { LOG.error("auction_list failed for ${taken.item}", it) }
            if (!p.inventory.add(taken)) KamiEconomy.dropOwned(p, taken)
            return when {
                listed.isFailure -> Phrase.of("kami_economy.action.auction_failed") to false
                listed.getOrNull() == ListResult.TooLarge -> Phrase.of("kami_economy.action.auction_too_large") to false
                listed.getOrNull() == ListResult.BadPrice -> Phrase.of("kami_economy.market.auction.buy_now.invalid") to false
                else -> Limits.auctionDenial(me) to false
            }
        }
        return Phrase.of("kami_economy.action.auction_listed", taken.hoverName.string) to true
    }

    private fun auctionBid(p: ServerPlayer, me: String, args: List<String>): Pair<Phrase, Boolean> {
        val id = args.getOrNull(0)?.toLongOrNull() ?: return Phrase.of("kami_economy.action.invalid_auction") to false
        val amount = args.getOrNull(1)?.toIntOrNull() ?: return Phrase.of("kami_economy.action.invalid_bid") to false
        if (amount > Config.s.maxPrice) return outOfRange()
        return when (val result = Ledger.auctionBid(me, id, amount)) {
            BidResult.Ok -> { KamiEconomy.deliver(p); Phrase.of("kami_economy.action.bid_placed") to true }
            BidResult.BoughtOut -> { KamiEconomy.deliver(p); Phrase.of("kami_economy.action.purchased") to true }
            BidResult.TooLow -> Phrase.of("kami_economy.action.bid_low", Phrase.money((Auctions.find(id)?.let(Auctions::minBid) ?: 0).toLong())) to false
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
        val variant = args.itemArg()
        val item = Variants.core(variant)
        val qty = coreQtyArg(args, item)
        if (!known(item)) return Phrase.of("kami_economy.action.invalid_item") to false
        return when (val result = Ledger.buy(me, item, qty, variant = variant.takeIf { it != item }.orEmpty())) {
            is BuyResult.Ok -> {
                KamiEconomy.deliver(p)
                Phrase.of("kami_economy.action.bought", result.filled, item, Phrase.money(result.spent)) to true
            }
            BuyResult.InsufficientFunds -> Phrase.of("kami_economy.action.no_funds") to false
            BuyResult.NothingAvailable -> Phrase.of("kami_economy.action.nothing_available") to false
            BuyResult.Frozen -> Phrase.of("kami_economy.action.frozen") to false
            BuyResult.IntentOpen -> Phrase.of("kami_economy.action.buy_processing") to false
            BuyResult.OutOfRange -> outOfRange()
        }
    }

    private fun cancel(p: ServerPlayer, me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = coreArg(args)
        val amount = Ledger.cancelAll(me, item)
        KamiEconomy.deliver(p)
        return if (amount > 0) Phrase.plural("kami_economy.action.listing_cancelled", amount.toLong()) to true else Phrase.of("kami_economy.action.no_listing") to false
    }

    private fun reprice(me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = coreArg(args)
        val newPrice = args.intArg(1, "kami_economy.action.invalid_price")
        if (!Config.s.validPrice(newPrice)) return outOfRange()
        Ledger.repriceStep(me, item, newPrice)?.let { return notClean(it) }
        if (!Ledger.repriceAll(me, item, newPrice)) return Phrase.of("kami_economy.action.no_listing") to false
        unpeg(Matching.bookFor(item).sells, me)
        return Phrase.of("kami_economy.action.repriced", Phrase.money(newPrice.toLong())) to true
    }

    private fun repriceBid(me: String, args: List<String>): Pair<Phrase, Boolean> {
        val item = coreArg(args)
        val newPrice = args.intArg(1, "kami_economy.action.invalid_price")
        if (!Config.s.validPrice(newPrice)) return outOfRange()
        return orderReply(me, Ledger.repriceBid(me, item, newPrice)) { unpeg(Matching.bookFor(item).buys, me); Phrase.of("kami_economy.action.repriced", Phrase.money(newPrice.toLong())) to true }
    }

    private fun unpeg(orders: List<Order>, me: String) {
        orders.filter { it.owner == me && it.peg != null }.forEach { it.peg = null; it.pegLimit = null; Market.dirty = true }
    }

    private fun statusItem(name: String, args: List<String>): String? = when (name) {
        "auction_bid", "auction_buy" -> args.getOrNull(0)?.toLongOrNull()?.let(Auctions::find)?.let { StackCodec.itemId(it.stackData) }
        "quest_deliver" -> null
        else -> args.getOrNull(0)
    }

    private fun act(p: ServerPlayer, name: String, args: List<String>): Pair<Phrase, Boolean> {
        val me = p.stringUUID
        Gate.denial(p.uuid, name)?.let { return it to false }
        statusItem(name, args)?.let { id -> TradeStatus.denial(name, id) ?: Variants.core(id).takeIf { it != id }?.let { TradeStatus.denial(name, it) } }?.let { return it to false }
        return try {
            when (name) {
                "sell", "sell_market" -> sell(p, me, name == "sell_market", args)
                "quest_deliver" -> questDeliver(p, me, args)
                "bid" -> bid(p, me, args)
                "cancel_bid" -> cancelBid(me, args)
                "auction_list" -> auctionList(p, me, args)
                "auction_bid" -> auctionBid(p, me, args)
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
