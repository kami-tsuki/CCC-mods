package kami.economy.admin

import kami.economy.Config
import kami.economy.QuestSettings
import kami.economy.economy.Stocks
import kami.economy.net.Net
import kami.economy.quest.Dishes
import kami.economy.quest.PickInfo
import kami.economy.quest.Quest
import kami.economy.quest.QuestBoost
import kami.economy.quest.Rarity
import kami.economy.quest.QuestSpec
import kami.economy.quest.QuestState
import kami.economy.quest.Quests
import kami.libs.admin.Admin
import kami.libs.admin.AdminCtx
import kami.libs.admin.Page
import kami.libs.admin.flag
import kami.libs.admin.int
import kami.libs.admin.long
import kami.libs.admin.page
import kami.libs.admin.text
import kami.libs.claims.ClaimsApi
import kami.libs.config.Configs
import kami.libs.db.Db
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation

@Serializable
class QuestTop(val country: String, val qty: Int)

@Serializable
class QuestAdminRow(
    val id: Long, val item: String, val rarity: Int, val state: String, val lotPrice: Int, val lot: Int, val amount: Int, val filled: Int,
    val course: Double, val offset: Double, val createdAt: Long, val expiresAt: Long, val endedAt: Long, val winner: String?, val boosts: List<QuestBoost>,
    val deliveries: Int, val players: Int, val countries: Int, val paid: Long, val top: List<QuestTop>
)

@Serializable
class QuestSpecReq(
    val item: String, val rarity: Int, val amount: Int, val hours: Int,
    val lotPrice: Int? = null, val offset: Double? = null, val boosts: List<QuestBoost>? = null
)

@Serializable
class QuestPreviewRes(val rarity: Int, val pick: PickInfo?, val offset: Double, val lotPrice: Int, val amount: Int, val boosts: List<QuestBoost>, val course: Double, val item: String)

@Serializable
class DishRow(
    val item: String, val mod: String, val score: Int, val tier: Int, val price: Double, val lot: Int, val course: Double,
    val inputs: List<String>, val stations: List<String>, val guessed: Boolean, val quest: Boolean
)

@Serializable
class RarityStat(val rarity: Int, val total: Int, val open: Int, val done: Int, val expired: Int, val cancelled: Int, val payout: Long, val filled: Long, val amount: Long)

@Serializable
class BoostStat(val kind: String, val count: Int, val pctSum: Long, val hoursSum: Long)

@Serializable
class QuestStatsRes(
    val total: Int, val open: Int, val payout: Long, val deliveries: Int, val rarities: List<RarityStat>, val boosts: List<BoostStat>,
    val nextSpawn: Long, val lastSpawn: List<Long>
)

object QuestAdmin {
    private const val MAX_AMOUNT = 100_000_000
    private const val MAX_BOOSTS = 4

    fun register() {
        Admin.view("market.quest_list") { _, a -> Db.encode(Page.serializer(QuestAdminRow.serializer()), list(a)) }
        Admin.view("market.quest_preview") { ctx, a -> Db.encode(QuestPreviewRes.serializer().nullable, preview(ctx, a)) }
        Admin.view("market.quest_dishes") { _, a -> Db.encode(Page.serializer(DishRow.serializer()), dishes(a)) }
        Admin.view("market.quest_settings") { _, _ -> Db.encode(QuestSettings.serializer(), Config.s.quests) }
        Admin.view("market.quest_stats") { _, _ -> Db.encode(QuestStatsRes.serializer(), stats()) }
        Admin.act("market.quest_cancel") { ctx, a -> done(ctx, a, Quests::cancel) }
        Admin.act("market.quest_end") { ctx, a -> done(ctx, a, Quests::end) }
        Admin.act("market.quest_edit") { ctx, a -> edit(ctx, a) }
        Admin.act("market.quest_spawn") { ctx, a -> spawn(ctx, a) }
        Admin.act("market.quest_clock") { ctx, a ->
            val rarity = a.int(0)?.takeIf { it in 0..Rarity.MAX } ?: ctx.fail("kami_economy.admin.quest.invalid")
            Quests.resetClock(rarity)
            Admin.done(rarity)
        }
        Admin.act("market.quest_settings_save") { ctx, a -> saveSettings(ctx, a) }
    }

    private fun list(a: List<String>): Page<QuestAdminRow> {
        val search = AdminSearch(a, 0)
        val page = Page.of(Quests.list().filter { search.hit(it.item) }, a.page(2))
        return Page(page.rows.map(::row), page.total, page.page, page.size)
    }

    private fun countryName(id: String) = ClaimsApi.country(id)?.name ?: id

    private fun row(q: Quest): QuestAdminRow {
        val d = Quests.deliveriesOf(q.id)
        return QuestAdminRow(
            q.id, q.item, q.rarity, q.state.name.lowercase(), q.lotPrice, q.lot, q.amount, q.filled, q.course, q.offset, q.createdAt, q.expiresAt,
            q.endedAt ?: 0, q.winner?.let(::countryName), q.boosts, d.size, d.map { it.player }.distinct().size, d.mapNotNull { it.country }.distinct().size,
            d.sumOf { it.paid }, Quests.leaders(q.id).take(3).map { QuestTop(countryName(it.first), it.second) }
        )
    }

    private fun questId(ctx: AdminCtx, a: List<String>) = a.long(0)?.takeIf { Quests.get(it) != null } ?: ctx.fail("kami_economy.admin.quest.unknown", a.text(0).orEmpty())

    private fun done(ctx: AdminCtx, a: List<String>, op: (Long) -> Boolean): JsonObject {
        val id = questId(ctx, a)
        if (!op(id)) ctx.fail("kami_economy.admin.quest.closed", id)
        Net.broadcastOpen(ctx.player.server)
        return Admin.done(id)
    }

    private fun boosts(ctx: AdminCtx, raw: String?): List<QuestBoost>? {
        raw ?: return null
        val list = runCatching { Configs.json().decodeFromString(ListSerializer(QuestBoost.serializer()), raw) }.getOrNull() ?: ctx.fail("kami_economy.admin.quest.invalid")
        if (list.size > MAX_BOOSTS) ctx.fail("kami_economy.admin.quest.invalid")
        return list
    }

    private fun edit(ctx: AdminCtx, a: List<String>): JsonObject {
        val id = questId(ctx, a)
        fun <T : Any> opt(i: Int, parse: (String) -> T?): T? = a.text(i)?.let { parse(it) ?: ctx.fail("kami_economy.admin.quest.invalid") }
        val lotPrice = opt(1, String::toIntOrNull)
        val amount = opt(2) { it.toIntOrNull()?.takeIf { v -> v in 1..MAX_AMOUNT } }
        val expiresAt = opt(3, String::toLongOrNull)
        val boosts = boosts(ctx, a.text(4))
        if (!Quests.edit(id, lotPrice, amount, expiresAt, boosts)) ctx.fail("kami_economy.admin.quest.rejected", id)
        Net.broadcastOpen(ctx.player.server)
        return Admin.done(id)
    }

    private fun spawn(ctx: AdminCtx, a: List<String>): JsonObject {
        val req = a.text(0)?.let { runCatching { Configs.json().decodeFromString(QuestSpecReq.serializer(), it) }.getOrNull() } ?: ctx.fail("kami_economy.admin.quest.invalid")
        val known = ResourceLocation.tryParse(req.item)?.let(BuiltInRegistries.ITEM::containsKey) == true
        if (!known) ctx.fail("kami_economy.admin.unknown_item", req.item)
        if (req.rarity !in 0..Rarity.MAX || req.amount !in 1..MAX_AMOUNT || req.hours !in 1..QuestSettings.MAX_HOURS) ctx.fail("kami_economy.admin.quest.invalid")
        if (req.offset != null && (req.offset.isNaN() || req.offset !in -0.9..5.0)) ctx.fail("kami_economy.admin.quest.invalid")
        if (req.boosts != null && req.boosts.size > MAX_BOOSTS) ctx.fail("kami_economy.admin.quest.invalid")
        val q = Quests.spawn(QuestSpec(req.item, req.rarity, req.amount, req.hours, req.lotPrice, req.offset, req.boosts), a.flag(1)) ?: ctx.fail("kami_economy.admin.quest.spawn_failed", req.item)
        Net.broadcastOpen(ctx.player.server)
        return Admin.done(q.id, "item" to JsonPrimitive(q.item))
    }

    private fun preview(ctx: AdminCtx, a: List<String>): QuestPreviewRes? {
        val rarity = a.text(0)?.let { it.toIntOrNull()?.takeIf { r -> r in 0..Rarity.MAX } ?: ctx.fail("kami_economy.admin.quest.invalid") }
        val p = Quests.preview(rarity) ?: return null
        val item = p.pick?.chosen.orEmpty()
        return QuestPreviewRes(p.rarity, p.pick, p.offset, p.lotPrice, p.amount, p.boosts, Stocks.course(item), item)
    }

    private fun dishes(a: List<String>): Page<DishRow> {
        val search = AdminSearch(a, 0)
        val onlyQuest = a.flag(2)
        val rows = Dishes.all().filter { (!onlyQuest || it.quest) && search.hit(it.item) }.sortedBy { it.item }.map {
            DishRow(it.item, it.item.substringBefore(':'), it.score, it.tier, it.price, Config.s.lotOf(it.item), Stocks.course(it.item), it.inputs, it.stations, it.guessed, it.quest)
        }
        return Page.of(rows, a.page(3))
    }

    private fun stats(): QuestStatsRes {
        val all = Quests.list()
        val clock = Quests.clockState()
        val rarities = (0..Rarity.MAX).map { r ->
            val l = all.filter { it.rarity == r }
            RarityStat(
                r, l.size, l.count { it.open }, l.count { it.state == QuestState.DONE }, l.count { it.state == QuestState.EXPIRED }, l.count { it.state == QuestState.CANCELLED },
                l.sumOf { q -> Quests.deliveriesOf(q.id).sumOf { it.paid } }, l.sumOf { it.filled.toLong() }, l.sumOf { it.amount.toLong() }
            )
        }
        val granted = all.filter { it.state == QuestState.DONE && it.winner != null }.flatMap { it.boosts }.groupBy { it.kind }
            .map { (k, l) -> BoostStat(k, l.size, l.sumOf { it.pct.toLong() }, l.sumOf { it.hours.toLong() }) }
        return QuestStatsRes(all.size, all.count { it.open }, rarities.sumOf { it.payout }, all.sumOf { Quests.deliveriesOf(it.id).size }, rarities, granted, clock.next, clock.last.toList())
    }

    private fun saveSettings(ctx: AdminCtx, a: List<String>): JsonObject {
        val parsed = a.text(0)?.let { runCatching { Configs.json().decodeFromString(QuestSettings.serializer(), it) }.getOrNull() } ?: ctx.fail("kami_economy.admin.quest.invalid")
        val before = Config.s.quests
        val after = parsed.copy(seed = before.seed).sane()
        Config.save(Config.s.copy(quests = after))
        return Admin.done("quests", audit = mapOf("before" to Db.json.encodeToString(QuestSettings.serializer(), before), "after" to Db.json.encodeToString(QuestSettings.serializer(), after)))
    }

}
