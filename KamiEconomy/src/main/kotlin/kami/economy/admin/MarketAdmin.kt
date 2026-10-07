package kami.economy.admin

import kami.economy.Config
import kami.economy.Market
import kami.economy.StarterGood
import kami.economy.economy.Auctions
import kami.economy.economy.History
import kami.economy.economy.Ledger
import kami.economy.economy.Notify
import kami.economy.economy.StackCodec
import kami.economy.economy.Starters
import kami.economy.economy.TradeFlag
import kami.economy.economy.TradeStatus
import kami.economy.economy.Watchlist
import kami.economy.net.Net
import kami.libs.admin.Admin
import kami.libs.admin.AdminCtx
import kami.libs.admin.AdminMetrics
import kami.libs.admin.Page
import kami.libs.admin.Players
import kami.libs.admin.long
import kami.libs.admin.page
import kami.libs.admin.text
import kami.libs.config.Configs
import kami.libs.db.Db
import kami.libs.mc.ItemSpec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer

object MarketAdmin {
    private const val MAX_ENTRIES = 50
    private const val WATCH_TOP = 10
    private val SIDES = setOf("sell", "buy", "auction")

    fun register() {
        AdminMetrics.register("kami_economy.metric.volume24h") { History.dayVolume() }
        Admin.view("market.orders") { ctx, a -> encode(Page.serializer(OrderRow.serializer()), orders(ctx.player.server, a)) }
        Admin.view("market.starter") { _, _ -> encode(StarterList.serializer(), starters()) }
        Admin.view("market.watch") { _, _ -> encode(WatchStats.serializer(), watchStats()) }
        Admin.view("market.frozen") { _, _ -> encode(ListSerializer(FrozenRow.serializer()), frozen()) }
        Admin.view("market.status") { _, a -> encode(Page.serializer(StatusRow.serializer()), statuses(a)) }
        Admin.act("market.cancel") { ctx, a -> cancel(ctx, a) }
        Admin.act("market.status_set") { ctx, a -> setStatus(ctx, a) }
        Admin.act("market.starter_upsert") { ctx, a ->
            val good = a.text(0)?.let { runCatching { Configs.json().decodeFromString(StarterGood.serializer(), it) }.getOrNull() } ?: ctx.fail("kami_economy.admin.invalid_good")
            val id = ResourceLocation.tryParse(good.item.removePrefix("#"))
            if (id == null || !good.item.startsWith("#") && !BuiltInRegistries.ITEM.containsKey(id)) ctx.fail("kami_economy.admin.unknown_item", good.item)
            val (before, after) = Starters.upsert(good, a.text(1).orEmpty())
            diff(good.item, before, after)
        }
        Admin.act("market.starter_remove") { ctx, a ->
            val key = a.text(0) ?: ctx.fail("kami_economy.admin.unknown_good", "")
            diff(key, Starters.remove(key, a.text(1).orEmpty()), null)
        }
        Admin.act("market.frozen_release") { ctx, a ->
            val id = frozenId(ctx, a)
            val paid = Ledger.release(id) ?: ctx.fail("kami_economy.command.frozen.unknown", id)
            Admin.done(paid.firstOrNull()?.first ?: id)
        }
        Admin.act("market.frozen_close") { ctx, a ->
            val id = frozenId(ctx, a)
            val actor = Ledger.frozen().firstOrNull { it.first == id }?.second?.actor
            if (!Ledger.resolve(id)) ctx.fail("kami_economy.command.frozen.unknown", id)
            Admin.done(actor?.takeIf { it.isNotEmpty() } ?: id)
        }
    }

    private fun orders(server: MinecraftServer, a: List<String>): Page<OrderRow> {
        val page = a.page(4)
        val owner = a.text(2)?.let { Players.resolve(server, it)?.toString() ?: return Page.of(emptyList(), page) }
        val side = a.text(3)?.takeIf { it in SIDES }
        val search = AdminSearch(a, 0)
        val rows = ArrayList<OrderRow>()
        if (side != "auction") Market.data.books.values.forEach { b ->
            if (side != "buy") b.sells.filter { !it.synthetic && it.owner.isNotEmpty() }.forEach { rows += OrderRow("sell", b.item, it.id, it.owner, "", it.price, it.amount, it.lot, it.placedAt) }
            if (side != "sell") b.buys.filter { !it.synthetic && it.owner.isNotEmpty() }.forEach { rows += OrderRow("buy", b.item, it.id, it.owner, "", it.price, it.amount, it.lot, it.placedAt) }
        }
        if (side == null || side == "auction") Auctions.open().forEach {
            val price = if (it.currentBidder.isNotEmpty()) it.currentBid else it.startPrice
            rows += OrderRow("auction", StackCodec.itemId(it.stackData), it.id, it.seller, "", price, StackCodec.count(it.stackData), 1, it.expiresAt - Config.s.auctionDurationMillis, it.label)
        }
        val hits = rows.filter { (owner == null || it.owner == owner) && search.hit(it.item, it.label) }.sortedByDescending { it.placedAt }
        val p = Page.of(hits, page)
        return Page(p.rows.map { it.copy(ownerName = Notify.playerName(it.owner) ?: it.owner.take(8)) }, p.total, p.page, p.size)
    }

    private fun watchStats() = Watchlist.summary(WATCH_TOP).let { s -> WatchStats(s.total, s.players, s.items, s.top.map { WatchTop(it.first, it.second) }) }

    private fun starters(): StarterList {
        val goods = Config.s.starterGoods
        return StarterList(goods.map { g ->
            val items = Starters.items(g.item)
            val status = TradeStatus.all()[g.item]
            StarterRow(
                g, items.mapNotNull { Market.data.stocks[it]?.lots }.takeIf { !g.infinite && it.isNotEmpty() }?.sum(), items.size,
                status?.let { TradeFlag.csv(it.flags) }.orEmpty(), status?.reason.orEmpty(), TradeStatus.rev(g.item)
            )
        }, Starters.rev(goods))
    }

    private fun statuses(a: List<String>): Page<StatusRow> {
        val search = AdminSearch(a, 0)
        val rows = TradeStatus.all().filter { (k, s) -> search.hit(k, s.reason) }.entries.sortedByDescending { it.value.at }.map { (k, s) ->
            StatusRow(k, TradeFlag.csv(s.flags), s.reason, s.by, Notify.playerName(s.by) ?: s.by.take(8), s.at, TradeStatus.rev(k))
        }
        return Page.of(rows, a.page(2))
    }

    private fun setStatus(ctx: AdminCtx, a: List<String>): JsonObject {
        val item = a.text(0) ?: ctx.fail("kami_economy.admin.unknown_item", "")
        val id = ResourceLocation.tryParse(ItemSpec.base(item.removePrefix("#")))
        if (id == null || !item.startsWith("#") && !BuiltInRegistries.ITEM.containsKey(id)) ctx.fail("kami_economy.admin.unknown_item", item)
        a.text(2)?.let { if (it != TradeStatus.rev(item)) ctx.fail("kami_economy.admin.status.stale") }
        val flags = TradeFlag.parse(a.text(1).orEmpty())
        val before = TradeStatus.set(item, flags, ctx.reason, ctx.player.stringUUID)
        Net.broadcastOpen(ctx.player.server)
        return Admin.done(item, audit = mapOf("before" to before?.let { TradeFlag.csv(it.flags) }.orEmpty(), "after" to TradeFlag.csv(flags)))
    }

    private fun frozen(): List<FrozenRow> = Ledger.frozen().map { (id, i) ->
        val owed = i?.let(Ledger::owed).orEmpty()
        val item = i?.item?.ifEmpty { i.stackData.takeIf { it.isNotEmpty() }?.let(StackCodec::itemId) }.orEmpty()
        FrozenRow(
            id, i?.type?.name.orEmpty(), i?.state?.name.orEmpty(), i?.actor.orEmpty(), i?.actor?.let(Notify::playerName).orEmpty(), item, i?.qty ?: 0,
            owed.sumOf { it.second }, owed.joinToString(", ") { Notify.playerName(it.first).orEmpty() }
        )
    }

    private fun cancel(ctx: AdminCtx, a: List<String>): JsonObject {
        val entries = a.text(0)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (entries.isEmpty() || entries.size > MAX_ENTRIES) ctx.fail("kami_economy.admin.bad_entries", MAX_ENTRIES)
        val parsed = entries.map { e ->
            val side = e.substringBefore(':')
            val id = e.substringAfterLast(':').toLongOrNull()
            if (side !in SIDES || id == null) ctx.fail("kami_economy.admin.bad_entries", MAX_ENTRIES)
            Triple(side, e.substringAfter(':').substringBeforeLast(':', ""), id)
        }
        val owners = parsed.mapNotNull { (side, item, id) -> Ledger.cancelAsServer(side, item, id, ctx.reason) }
        if (owners.isEmpty()) ctx.fail("kami_economy.admin.nothing_cancelled")
        Net.broadcastOpen(ctx.player.server)
        return Admin.done(owners.distinct().joinToString(","), "cancelled" to JsonPrimitive(owners.size))
    }

    private fun diff(key: String, before: StarterGood?, after: StarterGood?): JsonObject {
        fun fields(g: StarterGood?) = g?.let { Db.json.encodeToJsonElement(StarterGood.serializer(), it).jsonObject }.orEmpty()
        val old = fields(before)
        val new = fields(after)
        val changed = (old.keys + new.keys).filter { old[it] != new[it] }
        fun part(g: StarterGood?, m: Map<String, JsonElement>) = if (g == null) "null" else JsonObject(changed.filter { it in m }.associateWith { m.getValue(it) }).toString()
        return Admin.done(key, audit = mapOf("before" to part(before, old), "after" to part(after, new)))
    }

    private fun frozenId(ctx: AdminCtx, a: List<String>): Long = a.long(0) ?: ctx.fail("kami_economy.command.frozen.unknown", a.text(0).orEmpty())

    private fun <T> encode(serializer: KSerializer<T>, value: T) = Db.json.encodeToJsonElement(serializer, value)
}
