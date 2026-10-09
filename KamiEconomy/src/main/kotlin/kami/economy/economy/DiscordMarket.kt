package kami.economy.economy

import kami.libs.util.toUuidOrNull
import kami.economy.Config
import kami.economy.KamiEconomy
import kami.economy.Market
import kami.economy.now
import kami.libs.discord.Card
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordFormat
import kami.libs.discord.DiscordPrefs
import kami.libs.discord.DiscordText
import kami.libs.discord.EmbedField
import kami.libs.discord.Links
import kami.libs.discord.Mc
import kami.libs.discord.SlashCommand
import kami.libs.discord.SlashCtx
import kami.libs.discord.SlashOption
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal fun crossed(prev: Int, price: Int, pct: Double) = prev > 0 && price != prev && abs(price - prev) * 100.0 >= pct * prev

internal fun due(last: Long, now: Long, minutes: Long) = minutes > 0 && now - last >= TimeUnit.MINUTES.toMillis(minutes)

internal fun movers(rows: List<Pair<String, Int>>, n: Int): Pair<List<Pair<String, Int>>, List<Pair<String, Int>>> =
    rows.filter { it.second > 0 }.sortedByDescending { it.second }.take(n) to rows.filter { it.second < 0 }.sortedBy { it.second }.take(n)

internal fun realName(text: String, descriptionId: String): String? =
    text.takeIf { it.isNotBlank() && it != descriptionId && !it.startsWith("item.") && !it.startsWith("block.") }

internal fun labels(ids: Collection<String>, name: (String) -> String): List<Pair<String, String>> {
    val named = ids.map { it to name(it) }
    val dupes = named.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys
    return named.map { (id, n) -> id to if (n in dupes) "$n (${id.substringBefore(':')})" else n }
}

object DiscordMarket {
    private const val ID = KamiEconomy.ID
    private const val BATCH = 10
    private const val LINES = 10
    private const val MOVES = 6
    private const val SPARK = 24
    private const val TOP = 3
    private const val FLUSH_MINUTES = 1L
    private const val FLUSH_ATTEMPTS = 3
    private const val AUTOCOMPLETE = 25

    private class Move(val item: String, val from: Int, val to: Int, val volume: Long) {
        val permille get() = if (from > 0) ((to - from) * 1000L / from).toInt() else 0
    }

    private val last = HashMap<String, Int>()
    private val movedAt = HashMap<String, Long>()
    private val pending = LinkedHashMap<String, Move>()
    private val alerts = HashMap<String, LinkedHashMap<String, Move>>()
    private val dmAt = HashMap<String, Long>()
    private val names = HashMap<String, String>()

    private class Entry(val id: String, val label: String, val plain: String) {
        val lower = label.lowercase()
    }

    private var index: List<Entry>? = null
    private var indexSize = -1
    private var seeded = false
    private var flushFails = 0
    private var lastFlush = 0L
    private var lastDigest = 0L

    fun register() {
        Mc.add(
            SlashCommand(
                "market", "Market prices", public = true, subcommands = listOf(
                    SlashCommand("check", "Price, depth and trend of an item", listOf(SlashOption("item", "Item name or id", complete = ::complete)), public = true, run = ::check),
                    SlashCommand("top", "Top movers and most traded items", public = true, run = ::top),
                    SlashCommand("post", "Post a market digest to the market channel now", admin = true, run = ::post)
                )
            )
        )
        Mc.add(SlashCommand("watchlist", "Your market watchlist", linked = true, run = ::watchlist))
    }

    fun reset() {
        last.clear()
        movedAt.clear()
        pending.clear()
        alerts.clear()
        dmAt.clear()
        index = null
        seeded = false
    }

    fun tick(now: Long = now()) {
        if (!DiscordApi.ready) {
            seeded = false
            pending.clear()
            alerts.clear()
            return
        }
        val all = items()
        if (!seeded) {
            all.forEach { i -> Matching.bestAsk(i, "")?.let { last[i] = it } }
            seeded = true
            index = null
            lastFlush = now
            lastDigest = now
            return
        }
        if (all.size != indexSize) {
            indexSize = all.size
            index = null
        }
        val crossedNow = all.mapNotNull { watch(it, now) }
        if (crossedNow.isNotEmpty()) collect(crossedNow)
        if (pending.isNotEmpty() && due(lastFlush, now, FLUSH_MINUTES)) flush(now)
        if (alerts.isNotEmpty()) sendAlerts(now)
        if (due(lastDigest, now, Config.s.discordDigestHours * 60L)) {
            lastDigest = now
            if (DiscordApi.marketOn) DiscordApi.market(digest(now))
        }
    }

    private fun items(): Set<String> = Market.data.books.keys + Stocks.items()

    private fun watch(item: String, now: Long): Move? {
        val price = Matching.bestAsk(item, "") ?: return null
        val prev = last[item]
        if (prev == null) {
            last[item] = price
            return null
        }
        if (!crossed(prev, price, Config.s.discordMoveThresholdPct)) return null
        if (now - (movedAt[item] ?: 0L) < TimeUnit.MINUTES.toMillis(Config.s.discordItemCooldownMinutes.toLong())) return null
        last[item] = price
        movedAt[item] = now
        val move = Move(item, pending[item]?.from ?: prev, price, History.dayVolume(item, now))
        if (DiscordApi.marketOn) pending[item] = move
        return move
    }

    private fun collect(crossedNow: List<Move>) {
        Watchlist.forEach { who, watched ->
            var on: Boolean? = null
            for (move in crossedNow) {
                if (move.item !in watched) continue
                val allowed = on ?: alertable(who).also { on = it }
                if (!allowed) break
                val list = alerts.getOrPut(who) { LinkedHashMap() }
                list[move.item] = Move(move.item, list[move.item]?.from ?: move.from, move.to, move.volume)
            }
        }
    }

    private fun alertable(who: String) = who.toUuidOrNull()?.let { DiscordPrefs.on(it, DiscordPrefs.MARKET_ALERTS) && Links.get(it) != null } == true

    private fun flush(now: Long) {
        val batch = pending.values.sortedByDescending { abs(it.permille) }.take(BATCH)
        lastFlush = now
        if (DiscordApi.market(moves(batch))) flushFails = 0
        else if (++flushFails < FLUSH_ATTEMPTS) return
        batch.forEach { pending.remove(it.item) }
    }

    private fun sendAlerts(now: Long) {
        val cooldown = TimeUnit.MINUTES.toMillis(Config.s.discordAlertCooldownMinutes.toLong())
        val entries = alerts.entries.iterator()
        while (entries.hasNext()) {
            val (who, list) = entries.next()
            if (now - (dmAt[who] ?: 0L) < cooldown) continue
            entries.remove()
            val link = who.toUuidOrNull()?.takeIf { DiscordPrefs.on(it, DiscordPrefs.MARKET_ALERTS) }?.let(Links::get) ?: continue
            val valid = list.values.filter { Watchlist.has(who, it.item) }
            if (valid.isEmpty()) continue
            dmAt[who] = now
            DiscordApi.dm(link.discordId, moves(valid.sortedByDescending { abs(it.permille) }.take(BATCH)))
        }
    }

    private fun moves(list: List<Move>): Card {
        val locale = DiscordText.FALLBACK
        val up = list.count { it.permille > 0 }
        val color = when (up) {
            list.size -> DiscordFormat.GOOD
            0 -> DiscordFormat.BAD
            else -> DiscordFormat.NEUTRAL
        }
        val body = list.take(MOVES).joinToString("\n") {
            DiscordText.get(ID, locale, "kami_economy.discord.move", arrow(it.permille), nameOf(it.item), each(it.item, it.from), each(it.item, it.to), pct(it.permille), DiscordFormat.num(it.volume.toLong()))
        }
        val more = (list.size - MOVES).takeIf { it > 0 }?.let { DiscordText.get(ID, locale, "kami_economy.discord.more", it) }
        return Card.compact(DiscordText.get(ID, locale, "kami_economy.discord.moves"), body, color, footer = more, timestamp = now())
    }

    private fun each(item: String, price: Number) = DiscordFormat.each(price.toDouble() / Config.s.lotOf(item))

    private fun arrow(permille: Int) = if (permille > 0) "▲" else if (permille < 0) "▼" else "="

    private fun pct(permille: Int) = (if (permille > 0) "+" else if (permille < 0) "-" else "") + DiscordFormat.pct(abs(permille) / 10.0)

    private fun digest(now: Long): Card {
        val locale = DiscordText.FALLBACK
        val total = DiscordText.get(ID, locale, "kami_economy.discord.total", DiscordFormat.num(History.dayVolume(now)))
        return Card.compact(DiscordText.get(ID, locale, "kami_economy.discord.digest"), total, DiscordFormat.GOLD, overview(locale, now, false), timestamp = now)
    }

    private fun overview(locale: String, now: Long, watched: Boolean = true): List<EmbedField> {
        fun t(key: String) = DiscordText.get(ID, locale, "kami_economy.discord.$key")
        fun lines(rows: List<Pair<String, Int>>, show: (Int) -> String) = rows.joinToString("\n") { (item, v) -> "${nameOf(item)} ${show(v)}" }.ifEmpty { "–" }
        val known = items()
        val tracked = History.items().filter { it in known }
        val traded = tracked.map { it to History.dayVolume(it, now).toInt() }.filter { it.second > 0 }.sortedByDescending { it.second }.take(TOP)
        val (gain, lose) = movers(tracked.map { it to History.change(it, now) }, TOP)
        val fields = mutableListOf(
            EmbedField(t("gainers"), lines(gain) { "`${pct(it)}`" }, true),
            EmbedField(t("losers"), lines(lose) { "`${pct(it)}`" }, true),
            DiscordFormat.wide(t("traded"), lines(traded) { "×${DiscordFormat.num(it.toLong())}" })
        )
        if (watched) fields += DiscordFormat.wide(t("watched"), lines(Watchlist.summary(TOP).top) { "×${DiscordFormat.num(it.toLong())}" })
        return fields
    }

    private fun post(ctx: SlashCtx) {
        val sent = DiscordApi.marketOn && DiscordApi.market(digest(now()))
        ctx.reply(ctx.text(ID, if (sent) "kami_economy.discord.posted" else "kami_economy.discord.unposted"))
    }

    private fun top(ctx: SlashCtx) {
        val now = now()
        ctx.reply(Card.compact(ctx.text(ID, "kami_economy.discord.top"), color = DiscordFormat.NEUTRAL, fields = overview(ctx.locale, now), timestamp = now))
    }

    private fun watchlist(ctx: SlashCtx) {
        val list = Watchlist.of(ctx.player.toString())
        if (list.isEmpty()) return ctx.reply(ctx.text(ID, "kami_economy.discord.watchlist.empty"))
        val now = now()
        val body = list.take(LINES).joinToString("\n") { item ->
            val price = Matching.bestAsk(item, "")?.let { each(item, it) } ?: "–"
            val change = History.change(item, now)
            "**${nameOf(item)}** $price ${arrow(change)} ${DiscordFormat.pct(abs(change) / 10.0)}"
        }
        ctx.reply(Card.compact(ctx.text(ID, "kami_economy.discord.watchlist"), body, DiscordFormat.NEUTRAL, footer = "${minOf(list.size, LINES)}/${list.size}", timestamp = now))
    }

    private fun check(ctx: SlashCtx) {
        val query = ctx.opt("item").orEmpty()
        val item = resolve(query) ?: return ctx.fail(ctx.text(ID, "kami_economy.discord.unknown", DiscordFormat.literal(query)))
        val now = now()
        val book = Matching.bookFor(item)
        val lot = Config.s.lotOf(item)
        val ask = Matching.bestAsk(item, "")
        val bid = Matching.bestBid(item)
        val permille = History.change(item, now)
        fun t(key: String, vararg a: Any) = ctx.text(ID, "kami_economy.discord.$key", *a)
        fun money(v: Number?) = v?.let { each(item, it) } ?: "–"
        val bidDepth = book.buys.filter { it.amount > 0 && it.price == bid }.sumOf { it.amount.toLong() }
        val askDepth = if (Matching.bestPrice(item) == ask) book.sells.filter { it.amount > 0 && it.price == ask }.sumOf { it.amount.toLong() } else Stocks.supplyLeft(item).takeIf { it != Int.MAX_VALUE }.let { (it ?: 0).toLong() } * lot
        val week = History.series(item, Range.WEEK, now, book.course).buckets
        val range = if (week.isEmpty()) "–" else "${money(week.minOf { it.low }).removeSuffix(" ${DiscordFormat.SPUR}")} – ${money(week.maxOf { it.high })}"
        val closes = History.series(item, Range.DAY, now, book.course).buckets.map { it.close }
        val spark = if (closes.size <= SPARK) closes else (1..SPARK).map { closes[it * closes.size / SPARK - 1] }
        val fields = DiscordFormat.fields(
            t("bid") to "${money(bid)} ×${DiscordFormat.num(bidDepth)}",
            t("ask") to "${money(ask)} ×${DiscordFormat.num(askDepth)}",
            t("range") to range
        ).toMutableList()
        if (spark.isNotEmpty()) fields += DiscordFormat.wide(t("trend"), DiscordFormat.spark(spark))
        val head = t("check.head", money(ask), "${arrow(permille)} ${DiscordFormat.pct(abs(permille) / 10.0)}", DiscordFormat.num(History.dayVolume(item, now)))
        val color = when {
            permille > 0 -> DiscordFormat.GOOD
            permille < 0 -> DiscordFormat.BAD
            else -> DiscordFormat.NEUTRAL
        }
        ctx.reply(Card.compact(nameOf(item), head, color, fields, t("check.footer", item, Watchlist.count(item)), now, icon(item)))
    }

    private fun icon(item: String): String? = Config.s.discordItemIconUrl.takeIf { it.isNotEmpty() }?.let { url ->
        val id = ResourceLocation.tryParse(item) ?: return null
        url.replace("{namespace}", id.namespace).replace("{path}", id.path)
    }

    private fun entries(): List<Entry> = index ?: labels(items(), ::nameOf).map { (id, label) -> Entry(id, label, nameOf(id).lowercase()) }.sortedBy { it.label }.also { index = it }

    private fun complete(query: String): List<String> {
        val q = query.trim().lowercase()
        val all = entries()
        return (all.asSequence().filter { it.lower.startsWith(q) } + all.asSequence().filter { !it.lower.startsWith(q) && (it.lower.contains(q) || it.id.contains(q)) })
            .take(AUTOCOMPLETE).map { it.label }.toList()
    }

    private fun resolve(query: String): String? {
        val q = query.trim().lowercase()
        val all = entries()
        return all.firstOrNull { it.id == q || it.id.substringAfter(':') == q }?.id ?: all.firstOrNull { it.lower == q }?.id ?: all.firstOrNull { it.plain == q }?.id
    }

    private fun nameOf(item: String): String = names[item] ?: run {
        val found = ResourceLocation.tryParse(item)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }
        val real = found?.let { realName(it.description.string, it.descriptionId) }
        real?.also { names[item] = it } ?: pretty(item)
    }

    internal fun pretty(item: String) = item.substringAfter(':').split('_').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
}
