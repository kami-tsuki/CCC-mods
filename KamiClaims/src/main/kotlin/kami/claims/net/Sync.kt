package kami.claims.net

import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.research.Capacity
import kami.claims.research.Goals
import kami.claims.research.Levels
import kami.claims.service.AlertLine
import kami.claims.service.Diplomacy
import kami.claims.service.Alerts
import kami.claims.service.Plan
import kami.claims.service.Planner
import kami.claims.service.Provinces
import kami.claims.service.Service
import kami.claims.service.Upkeep
import kami.claims.service.View
import kami.claims.world.Guard
import kami.libs.text.Phrase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

@Serializable class FlagLine(val pattern: Int = 0, val emblem: Int = 0, val secondary: Int = 0xFFFFFF)
@Serializable class Line(
    val name: String, val members: Int, val chunks: Int, val color: Int = 0, val flag: FlagLine = FlagLine(), val parent: String = "",
    val provinces: Int = 0, val president: String = "", val founded: Long = 0, val relation: String = "", val capitalX: Int = 0, val capitalZ: Int = 0, val income: Long = 0,
    val trade: String = "neutral", val alliance: String = "", val tariff: Int = 0, val theirTariff: Int = 0, val embargo: Boolean = false
)
@Serializable class Mem(
    val id: String, val name: String, val rank: String, val job: String = "", val progress: Int = 0, val seen: Long = 0,
    val online: Boolean = false, val since: Long = 0, val plots: Int = 0, val auto: Boolean = false
)
@Serializable class JobLine(val name: String, val type: String, val pay: Int, val quota: Int, val period: Int, val actions: List<String> = emptyList(), val blocks: List<String> = emptyList())
@Serializable class TypeLine(
    val name: String, val price: Int, val period: Int, val access: List<String>, val machines: Boolean, val fire: Boolean, val fluid: Boolean,
    val job: String = "", val defaults: List<String> = emptyList()
)
@Serializable class ClaimLine(
    val x: Int, val z: Int, val type: String, val capital: Boolean, val free: Boolean, val debt: Int, val owner: String, val tax: Int, val lapse: Int, val roles: Int,
    val at: Long = 0, val ownerId: String = "", val locked: String = ""
)
@Serializable class Break(val type: String, val count: Int, val perDay: Double)
@Serializable class ProvinceOfferLine(val name: String, val mode: String, val amount: Double, val until: Long = 0, val answered: Boolean = false, val color: Int = 0, val members: Int = 0, val chunks: Int = 0)
@Serializable class ProvinceLine(
    val name: String, val mode: String, val amount: Double, val debt: Int, val wantsIndependence: Boolean,
    val color: Int = 0, val members: Int = 0, val chunks: Int = 0, val income: Long = 0, val flag: FlagLine = FlagLine()
)
@Serializable class Citizen(val country: String, val role: String, val via: String = "")
@Serializable class PlayerLine(val id: String, val name: String, val citizenships: List<Citizen>, val online: Boolean = false)
@Serializable class Detail(
    val x: Int, val z: Int, val country: String, val relation: Int, val type: String, val owner: String, val roles: List<String>,
    val debt: Int, val tax: Int, val lapse: Int, val price: Int, val period: Int, val free: Boolean, val capital: Boolean, val note: String,
    val access: Map<String, Boolean> = emptyMap(), val reserved: String = "", val at: Long = 0, val ownerId: String = "", val locked: String = "",
    val blocked: Boolean = false
)
@Serializable class GoalLine(val text: String, val value: Long, val max: Long, val page: String)
@Serializable class Info(
    val name: String, val rank: String, val treasury: Long, val upkeep: Long, val income: Long, val jobs: Long, val runway: String, val tax: Int,
    val chunks: Int, val free: Int, val shutdown: Int, val release: Int, val color: Int, val nextBilling: Long, val details: Boolean,
    val members: List<Mem>, val requests: List<Mem>, val relations: List<Mem>, val jobList: List<JobLine>,
    val claimList: List<ClaimLine>, val breakdown: List<Break>, val plots: Int,
    val parent: String, val taxMode: String, val taxAmount: Double, val provinceDebt: Int, val independenceRequested: Boolean,
    val provinceInvites: List<ProvinceOfferLine>, val provinceRequests: List<String>, val provinces: List<ProvinceLine>, val delegated: Boolean,
    val flag: FlagLine = FlagLine(), val parentColor: Int = 0, val independenceCooldown: Long = 0, val nextBill: Long = 0, val freeAllowed: Int = 0,
    val created: Long = 0, val capitalMoved: Long = 0, val invitesSent: List<Mem> = emptyList(), val pendingDeposits: Long = 0
)
@Serializable class LedgerLine(val at: Long, val kind: String, val amount: Long, val balance: Long, val actor: String, val note: String)
@Serializable class DayLine(
    val day: Long, val treasury: Long, val income: Long, val upkeep: Long, val jobs: Long, val tributeIn: Long, val tributeOut: Long,
    val deposits: Long, val withdrawals: Long, val chunks: Int, val debtChunks: Int, val members: Int, val plots: Int
)
@Serializable class PreviewCell(val x: Int, val z: Int, val outcome: String, val reason: String = "")
@Serializable class PreviewLine(val kind: String, val type: String, val cells: List<PreviewCell>, val cost: Long, val upkeepPerDay: Double, val key: String)
@Serializable class Limits(
    val maxDebt: Int, val reserveDays: Int, val capitalCooldownDays: Int, val maxRect: Int, val maxJobPay: Int, val tributeMin: Double, val tributeMax: Double,
    val nameMin: Int, val nameMax: Int, val freeBonusMembers: Int, val freeBonusCap: Int, val inactiveDays: Int, val successionDays: Int,
    val independenceCooldownDays: Int, val dayMillis: Long, val maxProvinceDebt: Int
)
@Serializable class Snap(
    val info: Info?, val countries: List<Line>, val invites: List<String>, val types: List<TypeLine>, val jobNames: List<String>,
    val caps: Map<String, String>, val detail: Detail?, val funds: Long, val freeChunks: Int, val jobShare: Int, val maxPlots: Int, val maxProvinceDebt: Int,
    val players: List<PlayerLine>, val msg: String, val ok: Boolean, val open: Boolean, val px: Int, val pz: Int,
    val rid: Int = 0, val reason: String = "", val targetX: Int = 0, val targetZ: Int = 0, val hasTarget: Boolean = false,
    val alerts: List<AlertLine> = emptyList(), val goals: List<GoalLine> = emptyList(), val ledger: List<LedgerLine> = emptyList(),
    val history: List<DayLine> = emptyList(), val preview: PreviewLine? = null, val limits: Limits? = null,
    val delegable: List<String> = emptyList(), val kept: List<String> = emptyList(), val me: String = "", val dim: String = "", val rank: String = ""
)

class Reply(val msg: String = "", val ok: Boolean = true, val rid: Int = 0, val reason: String = "", val target: Key? = null)

object Sync {
    private val json = Json { encodeDefaults = false }
    private val focus = HashMap<UUID, Key>()
    private val viewing = HashMap<UUID, String>()
    private val watching = HashMap<UUID, Set<String>>()
    private val previews = HashMap<UUID, PreviewLine>()

    fun focus(p: ServerPlayer, x: Int, z: Int) { focus[p.uuid] = Key(Service.here(p).dim, x, z) }
    fun view(p: ServerPlayer, name: String) { if (name.isBlank()) viewing.remove(p.uuid) else viewing[p.uuid] = name }
    fun watch(p: ServerPlayer, sections: List<String>) { watching[p.uuid] = sections.toSet() }
    fun forget(p: ServerPlayer) { focus.remove(p.uuid); viewing.remove(p.uuid); watching.remove(p.uuid); previews.remove(p.uuid) }

    fun preview(p: ServerPlayer, kind: String, type: String, plan: Plan, key: String) {
        previews[p.uuid] = PreviewLine(kind, type, plan.cells.map { PreviewCell(it.key.x, it.key.z, it.outcome.name, it.reason?.json() ?: "") }, plan.costNow, plan.upkeepPerDay, key)
    }

    private fun online(p: ServerPlayer, id: String) = runCatching { p.server.playerList.getPlayer(UUID.fromString(id)) != null }.getOrDefault(false)

    private fun flag(c: Country) = FlagLine(c.flag.pattern, c.flag.emblem, c.flag.secondary)

    private fun citizenships(id: String): List<Citizen> {
        val list = ArrayList<Citizen>()
        val own = Realm.of(id)
        own?.members?.get(id)?.let { list += Citizen(own.name, it.rank.name.lowercase()) }
        Realm.data.countries.values.forEach { c ->
            if (id in c.autoAllies) c.outsiders[id]?.let { list += Citizen(c.name, it.name.lowercase(), own?.name ?: "") }
        }
        return list
    }

    private fun mem(p: ServerPlayer, id: String, rank: String, m: Member? = null, c: Country? = null) = Mem(
        id, Names.of(p.server, id), rank, m?.job ?: "", m?.progress ?: 0, m?.seen ?: 0, online(p, id), m?.since ?: 0,
        c?.let { Realm.claims(it.id).count { cl -> cl.owner == id } } ?: 0, c?.autoAllies?.contains(id) == true
    )

    private fun relationLabel(c: Country, me: String, own: Country?): String = when {
        own?.id == c.id -> "own"
        own != null && (c.parent == own.id || own.parent == c.id || (own.parent != null && own.parent == c.parent)) -> "family"
        c.outsiders[me] == Rank.BANISHED -> "banished"
        c.outsiders[me] == Rank.ALLIED -> "ally"
        else -> "neutral"
    }

    private fun access(p: ServerPlayer, k: Key): Map<String, Boolean> {
        val level = p.serverLevel()
        if (level.dimension().location().toString() != k.dim) return emptyMap()
        val pos = BlockPos(k.x * 16 + 8, p.blockY, k.z * 16 + 8)
        return Action.entries.associate { it.name.lowercase() to Guard.allowed(level, pos, p, it) }
    }

    private fun detail(p: ServerPlayer, k: Key): Detail {
        val me = p.stringUUID
        val mine = Realm.of(me)
        val cl = Realm.index[k]
        val c = cl?.let { Realm.data.countries[it.country] }
        val access = access(p, k)
        if (cl == null || c == null) {
            val reserved = Realm.reservedFor(k.dim, k.x, k.z)?.let { Realm.data.countries[it]?.name }
            val problem = mine?.let { Service.claimError(it, k, Config.s.defaultType) }
            val note = when {
                reserved != null -> Phrase.of("kami_claims.detail.reserved", reserved)
                mine == null -> Phrase.of("kami_claims.detail.nomansland")
                else -> problem ?: Phrase.of("kami_claims.detail.free")
            }
            return Detail(k.x, k.z, "", 0, "", "", emptyList(), 0, -1, 0, 0, 0, false, false, note.json(), access, reserved ?: "", blocked = reserved != null || problem != null)
        }
        val rel = View.relation(c, me)
        if (rel == 0) return Detail(k.x, k.z, c.name, 0, "", "", emptyList(), 0, -1, 0, 0, 0, false, false, Phrase.of("kami_claims.detail.claimed_by", c.name).json(), access)
        val priv = View.privileged(c, me)
        val own = priv || cl.owner == me
        return Detail(
            k.x, k.z, c.name, rel, cl.type, cl.owner?.let { Names.of(p.server, it) } ?: "",
            if (own) cl.roles.map { (id, r) -> "${Names.of(p.server, id)}: ${r.name.lowercase()}" } else emptyList(),
            if (priv) cl.debt else 0, if (own) (if (cl.tax >= 0) cl.tax else c.tax) else -1, if (own) cl.lapse else 0,
            Realm.price(cl), Realm.period(cl), priv && cl.free, cl.capital, "", access, "", cl.at, cl.owner ?: "",
            if (priv) Planner.unclaimLock(cl)?.json() ?: "" else ""
        )
    }

    private fun limits(s: Settings) = Limits(
        s.maxDebt, s.reserveDays, s.capitalCooldownDays, s.maxRect, s.maxJobPay, s.provinceTaxRateBounds[0], s.provinceTaxRateBounds[1],
        s.nameLength[0], s.nameLength[1], s.freeBonusMembers, s.freeBonusCap, s.inactiveDays, s.successionDays, s.independenceCooldownDays, s.dayMillis, s.maxProvinceDebt
    )

    private fun pendingMembers(p: ServerPlayer, map: Map<String, Long>): List<Mem> =
        map.filterValues { it > now() }.keys.map { mem(p, it, "") }

    private fun claimLines(p: ServerPlayer, claims: List<Claim>, c: Country, me: String, priv: Boolean): List<ClaimLine> =
        claims.sortedWith(compareBy({ it.type }, { it.x }, { it.z })).map { cl ->
            val mine = priv || cl.owner == me
            ClaimLine(
                cl.x, cl.z, cl.type, cl.capital, cl.free && priv, if (priv) cl.debt else 0, cl.owner?.let { o -> Names.of(p.server, o) } ?: "",
                if (mine) (if (cl.tax >= 0) cl.tax else c.tax) else -1, if (mine) cl.lapse else 0, if (mine) cl.roles.size else 0,
                cl.at, cl.owner ?: "", if (priv) Planner.unclaimLock(cl)?.json() ?: "" else ""
            )
        }

    private fun invitedCountries(me: String): List<String> =
        Realm.data.countries.values.filter { (it.invites[me] ?: 0) > now() }.map { it.name }

    private fun info(p: ServerPlayer, c: Country, own: Country?, delegated: Boolean): Info {
        val s = Config.s
        val me = p.stringUUID
        val claims = Realm.claims(c.id)
        val sum = Upkeep.summary(c)
        val priv = delegated || View.privileged(c, me)
        val staff = !delegated && Service.rankOf(c, p) >= s.min(Cap.INVITE)
        val provStaff = !delegated && Service.rankOf(c, p) >= s.min(Cap.PROVINCE)
        val rank = if (delegated) Service.rankOf(own!!, p) else Service.rankOf(c, p)
        return Info(
            c.name, rank.name.lowercase(), c.treasury, sum.upkeep, sum.income, sum.jobs,
            sum.runway(c.treasury).json(), c.tax, claims.size, claims.count { it.free }, c.shutdown, c.release, View.color(c),
            (today() + 1) * s.dayMillis - now(), priv,
            c.members.map { (id, m) -> mem(p, id, m.rank.name.lowercase(), m, c) }.sortedByDescending { Rank.valueOf(it.rank.uppercase()) },
            if (staff) pendingMembers(p, c.requests) else emptyList(),
            if (staff) c.outsiders.map { (id, r) -> mem(p, id, r.name.lowercase(), c = c) } else emptyList(),
            s.jobs.keys.mapNotNull { j ->
                c.job(j)?.let { d -> val cfg = s.jobs.getValue(j); JobLine(j, cfg.type, d.pay, d.quota, d.period, cfg.actions.map { it.name.lowercase() }, cfg.blocks) }
            },
            claimLines(p, claims, c, me, priv),
            claims.filter { !it.free }.groupBy { it.type }.map { (t, list) -> Break(t, list.size, list.sumOf { Realm.price(it).toDouble() / Realm.period(it) }) },
            claims.count { it.owner == me },
            c.parent?.let { Realm.country(it)?.name } ?: "", c.taxMode.name.lowercase(), c.taxAmount, c.provinceDebt, c.independenceRequested,
            if (provStaff) c.provinceInvites.filterValues { it.until > now() }.mapNotNull { (pid, o) ->
                Realm.country(pid)?.let { par -> ProvinceOfferLine(par.name, o.mode.name.lowercase(), o.amount, o.until, o.answered, View.color(par), par.members.size, Realm.claims(par.id).size) }
            } else emptyList(),
            if (provStaff) c.provinceRequests.filterValues { it > now() }.keys.mapNotNull { Realm.country(it)?.name } else emptyList(),
            c.provinces.mapNotNull { pid ->
                Realm.country(pid)?.let { pr -> ProvinceLine(pr.name, pr.taxMode.name.lowercase(), pr.taxAmount, pr.provinceDebt, pr.independenceRequested, View.color(pr), pr.members.size, Realm.claims(pr.id).size, Upkeep.summary(pr).income, flag(pr)) }
            },
            delegated, flag(c), Realm.country(c.parent)?.let { View.color(it) } ?: 0, Provinces.cooldown(c), Alerts.nextBill(c), Realm.freeAllowed(c),
            c.created, c.moved, if (staff) pendingMembers(p, c.invites) else emptyList(), c.pending
        )
    }

    fun encode(p: ServerPlayer, reply: Reply, open: Boolean): String {
        val s = Config.s
        val me = p.stringUUID
        val own = Realm.of(me)
        val viewed = viewing[p.uuid]?.let { Realm.country(it) }
            ?.takeIf { own != null && it.parent == own.id && Service.rankOf(own, p) >= Rank.CHANCELLOR }
        val delegated = viewed != null
        val c = viewed ?: own
        val here = Service.here(p)
        val sections = watching[p.uuid] ?: emptySet()
        val lead = c != null && (delegated || Service.rankOf(c, p) >= s.min(Cap.CLAIM))
        val types = s.types.map { (n, d) ->
            TypeLine(
                n, d.price, d.period, Action.entries.map { a -> (c?.rules?.get(n)?.get(a) ?: d.rule.access[a] ?: Access.NONE).name.lowercase() },
                c?.machines?.get(n) ?: d.rule.machines, c?.fire?.get(n) ?: d.rule.fire, c?.fluid?.get(n) ?: d.rule.fluid,
                d.job ?: "", Action.entries.map { a -> (d.rule.access[a] ?: Access.NONE).name.lowercase() }
            )
        }
        val world = "world" in sections || c == null
        val countries = if (world) Realm.data.countries.values.map { x ->
            val capital = Realm.claims(x.id).firstOrNull { it.capital }
            Line(
                x.name, x.members.size, Realm.claims(x.id).size, View.color(x), flag(x), Realm.country(x.parent)?.name ?: "", x.provinces.size,
                x.president()?.let { Names.of(p.server, it) } ?: "", x.created, relationLabel(x, me, own), capital?.x ?: 0, capital?.z ?: 0,
                trade = own?.let { Diplomacy.relation(it, x) } ?: "neutral",
                alliance = own?.let { o -> when { Diplomacy.allied(o, x) -> "allied"; x.id in o.allianceOffers -> "offer_in"; o.id in x.allianceOffers -> "offer_out"; else -> "" } } ?: "",
                tariff = own?.let { Diplomacy.policy(it, x).tariffPct } ?: 0, theirTariff = own?.let { Diplomacy.policy(x, it).tariffPct } ?: 0,
                embargo = own?.let { Diplomacy.policy(it, x).embargo } ?: false
            )
        } else emptyList()
        val players = if (world) Realm.home.keys.map { id -> PlayerLine(id, Names.of(p.server, id), citizenships(id), online(p, id)) }.sortedBy { it.name } else emptyList()
        val ledger = if (c != null && ("ledger" in sections || "dashboard" in sections) && (delegated || View.privileged(c, me)))
            c.ledger.let { if ("ledger" in sections) it else it.takeLast(20) }.asReversed().map { LedgerLine(it.at, it.kind.name.lowercase(), it.amount, it.balance, it.actor?.let { a -> Names.of(p.server, a) } ?: "", it.note) }
        else emptyList()
        val history = if (c != null && ("history" in sections || "dashboard" in sections)) c.history.map {
            DayLine(it.day, it.treasury, it.income, it.upkeep, it.jobs, it.tributeIn, it.tributeOut, it.deposits, it.withdrawals, it.chunks, it.debtChunks, it.members, it.plots)
        } else emptyList()
        val snap = Snap(
            c?.let { info(p, it, own, delegated) }, countries,
            invitedCountries(me),
            types, s.jobs.keys.toList(), s.caps.mapKeys { it.key.name.lowercase() }.mapValues { it.value.name.lowercase() },
            focus[p.uuid]?.let { detail(p, it) }, Bank.funds(p.uuid), s.freeChunks, (s.jobShare * 100).toInt(), c?.let { Levels.capacity(it, Capacity.PLOTS) } ?: 0, s.maxProvinceDebt,
            players, reply.msg, reply.ok, open, here.x, here.z,
            reply.rid, reply.reason, reply.target?.x ?: 0, reply.target?.z ?: 0, reply.target != null,
            Alerts.of(p, c, delegated), if (c != null && lead && !delegated) Goals.of(c).map { GoalLine(it.text.json(), it.value, it.max, it.page.orEmpty()) } else emptyList(), ledger, history, previews.remove(p.uuid), limits(s),
            Provinces.delegatedRights, Provinces.keptRights, me, here.dim, own?.let { Service.rankOf(it, p).name.lowercase() } ?: ""
        )
        return json.encodeToString(snap)
    }
}
