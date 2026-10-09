package kami.claims.admin

import kami.libs.util.toUuidOrNull
import kami.claims.Country
import kami.claims.Names
import kami.claims.Realm
import kami.claims.research.Research
import kami.claims.service.Fail
import kami.claims.war.Fame
import kami.claims.war.Power
import kami.claims.war.Wars
import kami.libs.admin.Admin
import kami.libs.admin.AdminCtx
import kami.libs.admin.AdminMetrics
import kami.libs.admin.AdminPerms
import kami.libs.admin.Page
import kami.libs.admin.Players
import kami.libs.admin.StatsViews
import kami.libs.admin.long
import kami.libs.admin.page
import kami.libs.admin.text
import kami.libs.db.Db
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.server.MinecraftServer
import java.util.UUID
import kotlin.math.roundToInt

object CountryRequests {
    const val LIST = "countries.list"
    const val DETAIL = "countries.detail"
    const val NAMES = "countries.names"
    const val DISBAND = "countries.disband"
    const val RENAME = "countries.rename"
    const val LEADER = "countries.leader"
    const val UNCLAIM = "countries.unclaim"
    const val WAR_END = "countries.war_end"
    const val RESEARCH = "countries.research"
    const val MONEY = "countries.money"
}

object AdminCountries {
    private const val LEDGER = 50

    fun register() {
        AdminMetrics.register("kami_claims.metric.countries") { Realm.data.countries.values.count { it.active }.toLong() }
        AdminMetrics.register("kami_claims.metric.wars") { Realm.data.wars.count { it.active }.toLong() }
        StatsViews.resolvers += { k -> k.takeIf { it.startsWith("c:") }?.let { Realm.country(it.substring(2))?.name } }
        Admin.view(CountryRequests.LIST) { ctx, a ->
            val text = a.text(0)?.lowercase().orEmpty()
            val rows = Realm.data.countries.values.filter { text.isEmpty() || text in it.name.lowercase() || text in it.id }.sortedBy { it.name.lowercase() }
            val p = Page.of(rows, a.page(1))
            val fame = Fame.scores()
            Db.encode(Page.serializer(CountryRow.serializer()), Page(p.rows.map { row(ctx.player.server, it, fame[it.id]) }, p.total, p.page, p.size))
        }
        Admin.view(CountryRequests.DETAIL) { ctx, a -> Db.encode(CountryDetail.serializer(), detail(ctx.player.server, country(ctx, a))) }
        Admin.view(CountryRequests.NAMES) { _, _ -> JsonObject(Realm.data.countries.values.associate { it.id to JsonPrimitive(it.name) }) }
        act(CountryRequests.DISBAND) { _, c, _ -> CountryAdmin.disband(c) }
        act(CountryRequests.RENAME) { ctx, c, a -> CountryAdmin.rename(c, a.text(0) ?: missing(ctx)) }
        act(CountryRequests.LEADER) { ctx, c, a -> CountryAdmin.leader(c, a.text(0) ?: missing(ctx), ctx.player.server) }
        act(CountryRequests.UNCLAIM) { ctx, c, a -> CountryAdmin.unclaim(c, a.text(0) ?: missing(ctx)) }
        act(CountryRequests.WAR_END) { ctx, c, a -> CountryAdmin.endWar(c, a.text(0) ?: missing(ctx), ctx.reason) }
        act(CountryRequests.RESEARCH) { ctx, c, a -> CountryAdmin.research(c, a.text(0) ?: missing(ctx), a.getOrNull(1)) }
        act(CountryRequests.MONEY) { ctx, c, a ->
            val give = when (a.text(0) ?: missing(ctx)) {
                "give" -> true
                "take" -> false
                else -> ctx.fail("kami_claims.admin.bad_op", a[0])
            }
            val amount = a.long(1)?.takeIf { it in 1..CountryAdmin.MAX_MONEY } ?: ctx.fail("kami_claims.admin.bad_amount", a.getOrNull(1).orEmpty())
            CountryAdmin.money(c, give, amount, ctx.player.stringUUID)
        }
    }

    fun revision(c: Country): String = listOf(
        c.name, c.state, c.members.entries.sortedBy { it.key }.map { it.key to it.value.rank }, Realm.claimCount(c.id), c.parent, c.provinces.sorted(),
        Wars.all(c).map { it.id to it.phase }, c.research.done.keys.sorted(), c.research.queue.map { it.node }
    ).toString().hashCode().toUInt().toString(16)

    fun fresh(c: Country, rev: String?) {
        if (rev != revision(c)) throw Fail("kami_claims.admin.stale")
    }

    private fun act(name: String, run: (AdminCtx, Country, List<String>) -> Any?) = Admin.act(name) { ctx, a ->
        val c = country(ctx, a)
        guard(ctx, c)
        val result = try {
            fresh(c, a.getOrNull(1))
            run(ctx, c, a.drop(2))
        } catch (e: Fail) {
            ctx.fail(e.phrase.key, *e.phrase.args.map { it.json() }.toTypedArray())
        }
        val applied = (result as? Number)?.let { "applied" to JsonPrimitive(it) }
        Admin.done(c.id, "rev" to JsonPrimitive(revision(c)), *listOfNotNull(applied).toTypedArray(), audit = applied?.let { mapOf("applied" to result.toString()) }.orEmpty())
    }

    private fun guard(ctx: AdminCtx, c: Country) {
        val id = c.president()?.takeIf { it != ctx.player.stringUUID }?.toUuidOrNull() ?: return
        if (!AdminPerms.outranks(ctx.player, Players.profile(ctx.player.server, id))) ctx.fail("kami_claims.admin.outranked")
    }

    private fun country(ctx: AdminCtx, a: List<String>) = Realm.country(a.getOrNull(0)) ?: ctx.fail("kami_claims.error.unknown_country")

    private fun missing(ctx: AdminCtx): Nothing = ctx.fail("kami_claims.error.missing_argument")

    private fun row(server: MinecraftServer, c: Country, fame: Double?) = CountryRow(
        c.id, c.name, c.president(), c.president()?.let { Names.of(server, it) }.orEmpty(), c.members.size, Realm.claimCount(c.id), c.treasury,
        Research.level(c), Wars.all(c).size, Realm.country(c.parent)?.name, c.provinces.size, c.color, c.active,
        Power.score(c), c.reputation.roundToInt(), fame, Fame.count(c)
    )

    private fun detail(server: MinecraftServer, c: Country) = CountryDetail(
        row(server, c, Fame.score(c)), revision(c), c.created,
        c.members.entries.sortedByDescending { it.value.rank }.map { (id, m) -> MemberRow(id, Names.of(server, id), m.rank.name, m.since, m.seen) },
        c.ledger.takeLast(LEDGER).asReversed().map { LedgerRow(it.at, it.kind.name, it.amount, it.balance, it.actor?.let { a -> Names.of(server, a) }, it.note) },
        Wars.all(c).map { w ->
            val attacker = c.id in w.attacker.countries
            WarRow(w.id, Wars.name((if (attacker) w.defender else w.attacker).main), w.phase.name, attacker)
        },
        c.provinces.mapNotNull { Realm.country(it)?.name },
        ResearchSummary(c.research.done.size, Research.defs.treesFor(c).sumOf { it.nodes.size }, c.research.queue.map { it.node })
    )
}
