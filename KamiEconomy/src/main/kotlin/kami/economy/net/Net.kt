package kami.economy.net

import kami.economy.Config
import kami.economy.KamiEconomy
import kami.economy.economy.Blacklist
import kami.economy.economy.BidResult
import kami.economy.economy.BuyNowResult
import kami.economy.economy.Classification
import kami.economy.economy.Ledger
import kami.economy.economy.SellResult
import kami.economy.economy.BuyResult
import kami.economy.client.ClientHooks
import kami.libs.net.Packets
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import java.util.UUID
import kotlin.math.abs

private fun id(path: String) = Packets.id(KamiEconomy.ID, path)

private fun <T : CustomPacketPayload> codec(write: (FriendlyByteBuf, T) -> Unit, read: (FriendlyByteBuf) -> T) =
    Packets.codec(write, read)

class Act(val name: String, val args: List<String>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        const val MAX_ARGS = 4
        val TYPE = CustomPacketPayload.Type<Act>(id("act"))
        val CODEC = codec<Act>(
            { b, v ->
                b.writeUtf(v.name, 32)
                b.writeVarInt(v.args.size)
                v.args.forEach { b.writeUtf(it, 96) }
            },
            { b ->
                val name = b.readUtf(32)
                val count = b.readVarInt().also { require(it in 0..MAX_ARGS) }
                Act(name, List(count) { b.readUtf(96) })
            }
        )
    }
}

class Snapshot(val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<Snapshot>(id("snapshot"))
        val CODEC = codec<Snapshot>({ b, v -> b.writeUtf(v.json, 262_144) }, { b -> Snapshot(b.readUtf(262_144)) })
    }
}

class PriceEntry(val item: String, val price: Int)

class PriceDelta(val entries: List<PriceEntry>) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PriceDelta>(id("price_delta"))
        val CODEC = codec<PriceDelta>(
            { b, v -> b.writeCollection(v.entries) { bb, e -> bb.writeUtf(e.item, 64); bb.writeVarInt(e.price) } },
            { b -> PriceDelta(b.readList { bb -> PriceEntry(bb.readUtf(64), bb.readVarInt()) }) }
        )
    }
}

object Net {
    private val lastAct = HashMap<UUID, Int>()
    private val lastPrice = HashMap<String, Int>()
    private val openPlayers = HashSet<UUID>()

    fun register(e: RegisterPayloadHandlersEvent) {
        val r = e.registrar("1").optional()
        r.playToServer(Act.TYPE, Act.CODEC) { a, ctx -> (ctx.player() as? ServerPlayer)?.let { handle(it, a) } }
        r.playToClient(Snapshot.TYPE, Snapshot.CODEC) { s, _ -> ClientHooks.snapshot(s) }
        r.playToClient(PriceDelta.TYPE, PriceDelta.CODEC) { d, _ -> ClientHooks.priceDelta(d) }
    }

    private fun allPrices(): List<PriceEntry> {
        val items = (kami.economy.Market.data.books.keys + Config.s.endlessByItem.keys).distinct()
        return items.mapNotNull { item ->
            val price = kami.economy.economy.Matching.effectiveSellPrice(item) ?: return@mapNotNull null
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

    fun canOpen(p: ServerPlayer) = p.connection.hasChannel(Snapshot.TYPE)

    fun send(p: ServerPlayer, msg: String = "", ok: Boolean = true, open: Boolean = false) {
        if (canOpen(p)) PacketDistributor.sendToPlayer(p, Snapshot(Sync.encode(p, msg, ok, open)))
    }

    fun forget(p: ServerPlayer) {
        lastAct.remove(p.uuid)
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

    private fun handle(p: ServerPlayer, a: Act) {
        when (a.name) {
            "open" -> { openPlayers += p.uuid; send(p, open = true) }
            "close" -> openPlayers -= p.uuid
            "search" -> {
                Sync.search(p, a.args.getOrNull(0) ?: "", a.args.getOrNull(1) ?: "name", a.args.getOrNull(2)?.toIntOrNull() ?: 0)
                send(p)
            }
            "detail" -> {
                Sync.detail(p, a.args.getOrNull(0) ?: "", a.args.getOrNull(1) ?: "raw")
                send(p)
            }
            "quote" -> {
                Sync.quote(p, a.args.getOrNull(0) ?: "", a.args.getOrNull(1)?.toIntOrNull() ?: 0)
                send(p)
            }
            "auctions" -> {
                Sync.auctionPage(p, a.args.getOrNull(0)?.toIntOrNull() ?: 0)
                send(p)
            }
            else -> {
                val tick = p.server.tickCount
                if (tick - (lastAct[p.uuid] ?: -100) < Config.s.guiCooldown) return
                lastAct[p.uuid] = tick
                val (msg, ok) = try {
                    act(p, a.name, a.args)
                } catch (e: Exception) {
                    KamiEconomy.LOG.error("Action ${a.name} failed", e)
                    "Internal error." to false
                }
                send(p, msg, ok)
                if (ok) broadcastOpen(p.server, except = p.uuid)
            }
        }
    }

    private fun act(p: ServerPlayer, name: String, args: List<String>): Pair<String, Boolean> {
        val me = p.stringUUID
        return when (name) {
            "sell" -> {
                val item = args.getOrNull(0) ?: return "Invalid item." to false
                val qty = args.getOrNull(1)?.toIntOrNull() ?: return "Invalid quantity." to false
                val price = args.getOrNull(2)?.toIntOrNull() ?: return "Invalid price." to false
                if (qty <= 0 || price <= 0) return "Invalid sell request." to false
                val matching = p.inventory.items.filter { !it.isEmpty && Blacklist.itemId(it) == item && Blacklist.classify(it) == Classification.ALLOWED }
                if (matching.sumOf { it.count } < qty) return "You don't have $qty to sell." to false
                val instant = Config.s.endlessByItem.containsKey(item) && Config.s.sellInfiniteEnabled
                var remaining = qty
                for (stack in matching) {
                    if (remaining <= 0) break
                    val take = minOf(remaining, stack.count)
                    stack.shrink(take)
                    remaining -= take
                }
                val result = Ledger.sell(me, item, qty, price)
                if (result is SellResult.Ok) (if (instant) "Sold $qty x $item instantly." else "Listed $qty x $item at $price each.") to true
                else {
                    val resolved = KamiEconomy.registry.findItem(item)
                    if (resolved != null) {
                        var left = qty
                        while (left > 0) {
                            val n = left.coerceAtMost(resolved.defaultMaxStackSize)
                            val restore = net.minecraft.world.item.ItemStack(resolved, n)
                            if (!p.inventory.add(restore)) p.drop(restore, false)
                            left -= n
                        }
                    }
                    "Sell failed." to false
                }
            }
            "auction_list" -> {
                val stack = p.inventory.items.getOrNull(args.getOrNull(0)?.toIntOrNull() ?: -1) ?: return "Invalid item." to false
                val startPrice = args.getOrNull(1)?.toIntOrNull() ?: return "Invalid price." to false
                val buyNow = args.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
                if (stack.isEmpty || startPrice <= 0) return "Invalid auction listing." to false
                if (Blacklist.classify(stack) == Classification.BLOCKED) return "That item cannot be traded." to false
                val taken = stack.copy()
                stack.shrink(stack.count)
                val listed = runCatching { Ledger.auctionList(me, taken, startPrice, buyNow, p.server.registryAccess()) }
                if (listed.isFailure) {
                    KamiEconomy.LOG.error("auction_list failed for ${taken.item}", listed.exceptionOrNull())
                    if (!p.inventory.add(taken)) p.drop(taken, false)
                    return "Failed to list for auction, item returned." to false
                }
                "Listed ${taken.hoverName.string} for auction." to true
            }
            "auction_bid" -> {
                val id = args.getOrNull(0)?.toLongOrNull() ?: return "Invalid auction." to false
                val amount = args.getOrNull(1)?.toIntOrNull() ?: return "Invalid bid." to false
                when (Ledger.auctionBid(me, id, amount)) {
                    BidResult.Ok -> "Bid placed." to true
                    BidResult.TooLow -> "Bid too low." to false
                    BidResult.InsufficientFunds -> "Not enough funds." to false
                    BidResult.NotFound -> "Auction not found." to false
                }
            }
            "auction_buy" -> {
                val id = args.getOrNull(0)?.toLongOrNull() ?: return "Invalid auction." to false
                when (Ledger.auctionBuyNow(me, id)) {
                    BuyNowResult.Ok -> { KamiEconomy.deliver(p); "Purchased." to true }
                    BuyNowResult.InsufficientFunds -> "Not enough funds." to false
                    BuyNowResult.NotAvailable -> "Not available for buyout." to false
                }
            }
            "auction_cancel" -> {
                val id = args.getOrNull(0)?.toLongOrNull() ?: return "Invalid auction." to false
                if (Ledger.auctionCancel(me, id)) { KamiEconomy.deliver(p); "Auction cancelled." to true } else "Cannot cancel that auction." to false
            }
            "buy" -> {
                val item = args.getOrNull(0) ?: return "Invalid item." to false
                val qty = args.getOrNull(1)?.toIntOrNull() ?: return "Invalid quantity." to false
                when (val result = Ledger.buy(me, item, qty)) {
                    is BuyResult.Ok -> {
                        KamiEconomy.deliver(p)
                        "Bought ${result.filled} x $item for ${result.spent} spurs." to true
                    }
                    BuyResult.InsufficientFunds -> "Not enough funds." to false
                    BuyResult.NothingAvailable -> "Nothing available to buy." to false
                }
            }
            "cancel" -> {
                val item = args.getOrNull(0) ?: return "Invalid item." to false
                val amount = Ledger.cancelAll(me, item)
                KamiEconomy.deliver(p)
                if (amount > 0) "Listing cancelled, $amount item(s) and their payment reclaimed." to true else "No listing found." to false
            }
            "reprice" -> {
                val item = args.getOrNull(0) ?: return "Invalid item." to false
                val newPrice = args.getOrNull(1)?.toIntOrNull() ?: return "Invalid price." to false
                if (Ledger.repriceAll(me, item, newPrice)) "Price updated to $newPrice each." to true else "No listing found." to false
            }
            "claim" -> {
                KamiEconomy.deliver(p)
                "Claimed pending items." to true
            }
            else -> "Unknown action." to false
        }
    }
}
