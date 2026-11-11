package kami.claims.net

import kami.libs.util.toUuidOrNull
import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.economy.Treasury
import kami.claims.gov.Perm
import kami.claims.gov.Permissions
import kami.claims.gov.Standing
import kami.libs.stats.Staff
import kami.libs.admin.Subject
import kami.claims.research.Buffs
import kami.claims.research.Capacity
import kami.claims.research.Features
import kami.claims.research.Research
import kami.claims.research.Goals
import kami.claims.research.Levels
import kami.claims.service.AlertLine
import kami.claims.service.Diplomacy
import kami.claims.service.Gifts
import kami.claims.service.Scope
import kami.claims.service.Housing
import kami.claims.service.PlotBlock
import kami.claims.service.Alerts
import kami.claims.service.Oversight
import kami.claims.service.Plan
import kami.claims.service.Planner
import kami.claims.service.Provinces
import kami.claims.service.Service
import kami.claims.service.Upkeep
import kami.claims.service.View
import kami.claims.social.Perms
import kami.claims.war.Fame
import kami.claims.war.Joining
import kami.claims.war.Power
import kami.claims.war.SafeChunks
import kami.claims.war.Stakes
import kami.claims.war.War
import kami.claims.war.Wars
import kami.claims.world.Guard
import kami.libs.text.Phrase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.roundToInt

@Serializable class FlagLine(val pattern: Int = 0, val emblem: Int = 0, val secondary: Int = 0xFFFFFF)
@Serializable class Line(
    val name: String, val members: Int, val chunks: Int, val color: Int = 0, val flag: FlagLine = FlagLine(), val parent: String = "",
    val provinces: Int = 0, val president: String = "", val founded: Long = 0, val relation: String = "", val capitalX: Int = 0, val capitalZ: Int = 0, val income: Long = 0,
    val trade: String = "neutral", val alliance: String = "", val tariff: Int = 0, val theirTariff: Int = 0, val embargo: Boolean = false,
    val level: Int = 1, val researched: Int = 0, val reputation: Int = 0, val fame: Double? = null, val atWar: Boolean = false, val myRating: Int = 0, val power: Double? = null,
    val membership: String = "", val id: String = ""
)
@Serializable class Mem(
    val id: String, val name: String, val rank: String, val jobs: List<String> = emptyList(), val progress: Map<String, Int> = emptyMap(), val seen: Long = 0,
    val online: Boolean = false, val since: Long = 0, val plotsHeld: Int = 0, val auto: Boolean = false, val plotLimit: Int = 0
) {
    val job get() = jobs.firstOrNull().orEmpty()
    val done get() = progress[job] ?: 0
}
@Serializable class TypeLine(
    val name: String, val price: Int, val period: Int, val access: List<String>, val machines: Boolean, val fire: Boolean,
    val job: String = "", val defaults: List<String> = emptyList()
)
@Serializable class ClaimLine(
    val x: Int, val z: Int, val type: String, val capital: Boolean, val free: Boolean, val debt: Int, val owner: String, val roles: Int,
    val at: Long = 0, val ownerId: String = "", val locked: String = "", val rent: Int = 0, val state: String = "", val until: Long = 0,
    val category: String = "", val rentDebt: Long = 0, val workers: Int = 0
)
@Serializable class OfferLine(
    val open: List<String>, val rent: Map<String, Int>, val levels: Map<String, Int> = emptyMap(), val category: String = "", val price: Int = 0,
    val lock: String = "", val level: Int = 0, val custom: Boolean = false
)
@Serializable class MineLine(val category: String, val price: Int, val debt: Long, val limit: Long)
@Serializable class PersonalView(val subject: String, val homes: List<HomeLine>, val funds: Long)
@Serializable class HomeLine(val country: String, val dim: String, val x: Int, val z: Int, val state: String, val rent: Int, val debt: Long, val until: Long, val category: String, val credit: Long = 0, val nextBill: Long = 0, val room: Long = 0)
@Serializable class Break(val type: String, val count: Int, val perDay: Double)
@Serializable class ProvinceOfferLine(val name: String, val mode: String, val amount: Double, val until: Long = 0, val answered: Boolean = false, val color: Int = 0, val members: Int = 0, val chunks: Int = 0)
@Serializable class ProvinceLine(
    val name: String, val mode: String, val amount: Double, val debt: Int, val wantsIndependence: Boolean,
    val color: Int = 0, val members: Int = 0, val chunks: Int = 0, val income: Long = 0, val flag: FlagLine = FlagLine(), val manage: Boolean = false
)
@Serializable class Citizen(val country: String, val role: String, val via: String = "")
@Serializable class PlayerLine(val id: String, val name: String, val citizenships: List<Citizen>, val online: Boolean = false, val giftIn: Int = 0, val giftOut: Int = 0)
@Serializable class Detail(
    val x: Int, val z: Int, val country: String, val relation: Int, val type: String, val owner: String, val roles: List<String>,
    val debt: Int, val price: Int, val period: Int, val free: Boolean, val capital: Boolean, val note: String,
    val access: Map<String, Boolean> = emptyMap(), val reserved: String = "", val at: Long = 0, val ownerId: String = "", val locked: String = "",
    val blocked: Boolean = false, val state: String = "", val until: Long = 0, val taken: Boolean = false, val offer: OfferLine? = null, val mine: MineLine? = null,
    val workerNames: List<String> = emptyList(), val workerIds: List<String> = emptyList()
)
@Serializable class SafeLine(val dim: String, val x: Int, val z: Int)
@Serializable class StakeLine(val chunks: Int = 0, val money: Long = 0, val vassal: Boolean = false, val provinces: List<String> = emptyList())
@Serializable class OfferTerms(val stakes: StakeLine? = null, val ceasefireDays: Int = 0, val peace: Boolean = false, val extendDays: Int = 0, val billPct: Int? = null, val at: Long = 0)
@Serializable class WarLine(
    val id: String, val kind: String, val phase: String, val ends: Long, val attacker: String, val defender: String,
    val joinedA: List<String> = emptyList(), val joinedD: List<String> = emptyList(), val side: String = "", val stakes: StakeLine = StakeLine(),
    val offer: OfferTerms? = null, val offerFrom: String = "", val exhaustionA: Double = 0.0, val exhaustionD: Double = 0.0,
    val billA: Long = 0, val billD: Long = 0, val fee: Long = 0, val escrow: Long = 0, val ceasefires: Int = 0, val extendedDays: Int = 0,
    val result: String = "", val canJoin: Boolean = false, val joinSides: List<String> = emptyList()
)
@Serializable class WarHud(val phase: String, val ends: Long, val enemy: String, val side: String)
@Serializable class GoalLine(val text: String, val value: Long, val max: Long, val page: String)
@Serializable class Info(
    val name: String, val rank: String, val treasury: Long, val upkeep: Long, val income: Long, val jobs: Long, val runway: String,
    val chunks: Int, val free: Int, val color: Int, val nextBilling: Long, val details: Boolean,
    val members: List<Mem>, val requests: List<Mem>, val relations: List<Mem>,
    val claimList: List<ClaimLine>, val breakdown: List<Break>, val plots: Int,
    val parent: String, val taxMode: String, val taxAmount: Double, val provinceDebt: Int, val independenceRequested: Boolean,
    val provinceInvites: List<ProvinceOfferLine>, val provinceRequests: List<String>, val provinces: List<ProvinceLine>, val delegated: Boolean,
    val flag: FlagLine = FlagLine(), val parentColor: Int = 0, val independenceCooldown: Long = 0, val nextBill: Long = 0, val freeAllowed: Int = 0,
    val created: Long = 0, val capitalMoved: Long = 0, val invitesSent: List<Mem> = emptyList(), val pendingDeposits: Long = 0,
    val offer: OfferLine = OfferLine(emptyList(), emptyMap()), val guestPlots: Map<String, Int> = emptyMap(),
    val rentDebtLimit: Long = 0, val moveOutDays: Int = 0, val jobSlots: Int = 0, val maxRent: Int = 0,
    val granted: List<String> = emptyList(), val overlordManage: Boolean = false, val overlordPerms: List<String> = emptyList(),
    val wars: List<WarLine> = emptyList(), val reputation: Int = 0, val fame: Double? = null, val fameCount: Int = 0,
    val warDebts: Map<String, Long> = emptyMap(), val warOverlord: String = "", val safeChunks: List<SafeLine> = emptyList(), val safeLimit: Int = 0,
    val cooldownUntil: Long = 0, val power: Double? = null,
    val ranks: List<RankLine> = emptyList(), val myPerms: List<String> = emptyList(), val groups: Map<String, List<String>> = emptyMap(),
    val government: GovLine? = null, val sectors: List<SectorLine> = emptyList(), val jobSpecs: List<JobSpecLine> = emptyList(),
    val contracts: List<ContractLine> = emptyList(), val audit: List<AuditLine> = emptyList(), val rankCap: Int = 0, val sectorCap: Int = 0,
    val withdrawals: List<WithdrawLine> = emptyList()
) {
    val citizenRent get() = offer.rent["citizen"] ?: 0
}
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
@Serializable data class Snap(
    val info: Info?, val countries: List<Line>, val invites: List<String>, val types: List<TypeLine>,
    val detail: Detail?, val funds: Long, val freeChunks: Int, val jobShare: Int, val maxPlots: Int, val maxProvinceDebt: Int,
    val players: List<PlayerLine>, val msg: String, val ok: Boolean, val open: Boolean, val px: Int, val pz: Int,
    val rid: Int = 0, val reason: String = "", val targetX: Int = 0, val targetZ: Int = 0, val hasTarget: Boolean = false,
    val alerts: List<AlertLine> = emptyList(), val goals: List<GoalLine> = emptyList(), val ledger: List<LedgerLine> = emptyList(),
    val history: List<DayLine> = emptyList(), val preview: PreviewLine? = null, val limits: Limits? = null,
    val rights: List<String> = emptyList(), val kept: List<String> = emptyList(), val me: String = "", val dim: String = "", val rank: String = "",
    val homes: List<HomeLine> = emptyList(), val extraRids: List<Int> = emptyList(), val admin: Boolean = false, val war: List<WarHud> = emptyList(),
    val names: List<String> = emptyList(), val personal: PersonalView? = null
)

class Reply(val msg: String = "", val ok: Boolean = true, val rid: Int = 0, val reason: String = "", val target: Key? = null, val extraRids: List<Int> = emptyList())

object Sync {
    private val json = Json { encodeDefaults = false }
    private val focus = HashMap<UUID, Key>()
    private val viewing = HashMap<UUID, String>()
    private val watching = HashMap<UUID, Set<String>>()
    private val previews = HashMap<UUID, PreviewLine>()
    private val suggestions = HashMap<UUID, List<String>>()
    private val subjects = HashMap<UUID, String>()

    fun focus(p: ServerPlayer, x: Int, z: Int) { focus[p.uuid] = Key(Service.here(p).dim, x, z) }
    fun viewed(p: ServerPlayer): Country? {
        val name = viewing[p.uuid] ?: return null
        val country = Realm.live(name)?.takeIf { it !== Realm.of(p.stringUUID) && Oversight.canView(it, p) }
        if (country == null) viewing.remove(p.uuid)
        return country
    }

    fun view(p: ServerPlayer, name: String) { if (name.isBlank()) viewing.remove(p.uuid) else viewing[p.uuid] = name }
    fun watch(p: ServerPlayer, sections: List<String>) { watching[p.uuid] = sections.toSet() }
    fun subject(p: ServerPlayer, raw: String) { if (raw.isBlank()) subjects.remove(p.uuid) else subjects[p.uuid] = raw.trim() }
    fun subject(p: ServerPlayer): UUID? = subjects[p.uuid]?.let { Subject.resolve(p, it) }
    fun reset() { cache.clear(); shared = null; focus.clear(); viewing.clear(); watching.clear(); previews.clear(); suggestions.clear(); subjects.clear() }
    fun forget(p: ServerPlayer) { cache.remove(p.uuid); focus.remove(p.uuid); viewing.remove(p.uuid); watching.remove(p.uuid); previews.remove(p.uuid); suggestions.remove(p.uuid); subjects.remove(p.uuid) }

    fun preview(p: ServerPlayer, kind: String, type: String, plan: Plan, key: String) {
        previews[p.uuid] = PreviewLine(kind, type, plan.cells.map { PreviewCell(it.key.x, it.key.z, it.outcome.name, it.reason?.json() ?: "") }, plan.costNow, plan.upkeepPerDay, key)
    }

    private fun online(p: ServerPlayer, id: String) = id.toUuidOrNull()?.let { p.server.playerList.getPlayer(it) }?.let { it === p || !Staff.veiled(it) } == true

    private fun flag(c: Country) = FlagLine(c.flag.pattern, c.flag.emblem, c.flag.secondary)

    internal fun citizenships(id: String, scope: (String) -> Boolean): List<Citizen> {
        val list = ArrayList<Citizen>()
        val own = Realm.of(id)
        own?.takeIf { scope(it.id) }?.members?.get(id)?.let { list += Citizen(own.name, Permissions.rank(own, it.rank)?.name.orEmpty()) }
        Realm.data.countries.values.forEach { c ->
            if (id in c.autoAllies && scope(c.id)) c.outsiders[id]?.let { list += Citizen(c.name, it.name.lowercase(), own?.takeIf { o -> scope(o.id) }?.name ?: "") }
        }
        return list
    }

    private fun mem(p: ServerPlayer, id: String, rank: String, m: Member? = null, c: Country? = null, held: Int = 0) = Mem(
        id, Names.of(p.server, id), rank, m?.jobs?.keys?.toList().orEmpty(), m?.jobs?.mapValues { it.value.progress }.orEmpty(), m?.seen ?: 0, online(p, id), m?.since ?: 0,
        held, c?.autoAllies?.contains(id) == true, c?.let { Housing.limit(it, id) } ?: 0
    )

    private fun relationLabel(c: Country, me: String, own: Country?): String = when {
        own?.id == c.id -> "own"
        own != null && (c.parent == own.id || own.parent == c.id || (own.parent != null && own.parent == c.parent)) -> "family"
        c.outsiders[me] == Standing.BANISHED -> "banished"
        c.outsiders[me] == Standing.ALLIED -> "ally"
        else -> "neutral"
    }

    private fun access(p: ServerPlayer, k: Key): Map<String, Boolean> {
        val level = p.serverLevel()
        if (level.dimension().location().toString() != k.dim) return emptyMap()
        val pos = BlockPos(k.x * 16 + 8, p.blockY, k.z * 16 + 8)
        return Action.entries.associate { it.name.lowercase() to Guard.allowed(level, pos, p, it) }
    }

    private fun levelOf(feature: String) = Research.defs.levels.featureLevel(feature) ?: 0

    private fun offer(c: Country, cl: Claim?): OfferLine {
        val o = cl?.let { Housing.offer(c, it) } ?: c.offer
        return OfferLine(
            (o.open + Claimant.CITIZEN).map { it.name.lowercase() },
            Claimant.entries.associate { it.name.lowercase() to if (cl != null) Housing.rent(c, cl, it) else Housing.rent(c, it) },
            Housing.features.filterValues { !Features.unlocked(c, it) }.entries.associate { it.key.name.lowercase() to levelOf(it.value) },
            custom = cl?.offer != null
        )
    }

    private fun renting(c: Country, cl: Claim, me: String): OfferLine {
        val base = offer(c, cl)
        if (Housing.tenanted(cl)) return OfferLine(base.open, base.rent, base.levels, lock = PlotBlock.TAKEN.wire, custom = base.custom)
        val cat = Housing.category(c, me)
        val block = Housing.blocker(c, cl, me)
        val level = if (block == PlotBlock.LEVEL) levelOf(Housing.features.getValue(cat!!)) else 0
        return OfferLine(base.open, base.rent, base.levels, cat?.name?.lowercase().orEmpty(), cat?.let { Housing.price(c, cl, it, me) } ?: 0, block?.wire.orEmpty(), level, base.custom)
    }

    private fun tenancy(c: Country, cl: Claim) = MineLine(Housing.effective(cl).name.lowercase(), Housing.rate(c, cl), cl.rentDebt, c.rentDebtLimit)

    internal fun enemy(c: Country, cl: Claim) = Detail(
        x = cl.x, z = cl.z, country = c.name, relation = 0, type = "", owner = "", roles = emptyList(), debt = 0, price = 0, period = 0,
        free = false, capital = false, note = Phrase.of("kami_claims.detail.claimed_by", c.name).json()
    )

    internal fun stranger(c: Country, cl: Claim, me: String, relation: Int = 0): Detail {
        val note = Phrase.of("kami_claims.detail.claimed_by", c.name).json()
        val free = cl.plot && !Housing.tenanted(cl)
        return Detail(
            x = cl.x, z = cl.z, country = c.name, relation = relation, type = "", owner = "", roles = emptyList(), debt = 0, price = 0, period = 0,
            free = false, capital = false, note = note, offer = if (free) renting(c, cl, me) else null
        )
    }

    internal fun offerPrice(p: ServerPlayer, k: Key): Long? {
        val cl = Realm.index[k]?.takeIf { it.plot && !Housing.tenanted(it) } ?: return null
        val c = Realm.data.countries[cl.country] ?: return null
        val me = p.stringUUID
        val mine = Realm.of(me)
        if (!p.hasPermissions(Config.s.visibility.opLevel) && mine != null && Wars.enemies(mine.id, c.id)) return null
        if (Housing.blocker(c, cl, me) != null) return null
        return Housing.category(c, me)?.let { Housing.price(c, cl, it, me) }?.toLong()?.takeIf { it > 0 }
    }

    internal fun detail(p: ServerPlayer, k: Key): Detail {
        val me = p.stringUUID
        val mine = Realm.of(me)
        val cl = Realm.index[k]
        val c = cl?.let { Realm.data.countries[it.country] }
        val op = p.hasPermissions(Config.s.visibility.opLevel)
        val scope = Scope.of(me, op)
        if (cl == null || c == null) {
            val reservedBy = Realm.reservedFor(k.dim, k.x, k.z)?.let { Realm.data.countries[it] }
            val reserved = reservedBy?.takeIf { scope(it.id) }?.name
            val shown = reservedBy == null || reserved != null
            val problem = if (shown) mine?.let { Service.claimError(it, k, Config.s.defaultType) } else null
            val note = when {
                reserved != null -> Phrase.of("kami_claims.detail.reserved", reserved)
                mine == null -> Phrase.of("kami_claims.detail.nomansland")
                else -> problem ?: Phrase.of("kami_claims.detail.free")
            }
            return Detail(
                x = k.x, z = k.z, country = "", relation = 0, type = "", owner = "", roles = emptyList(), debt = 0, price = 0, period = 0, free = false, capital = false,
                note = note.json(), access = if (shown) access(p, k) else emptyMap(), reserved = reserved ?: "", blocked = reserved != null || problem != null
            )
        }
        if (!op && mine != null && Wars.enemies(mine.id, c.id)) return enemy(c, cl)
        val rel = View.relation(c, me)
        val tenant = cl.tenant == me
        val known = scope(c.id)
        if (!known && !tenant) return stranger(c, cl, me, rel)
        val taken = cl.plot && Housing.tenanted(cl)
        val priv = View.privileged(c, me)
        val own = priv || cl.owner == me
        val jobs = Oversight.may(c, p, Perm.VIEW_JOBS) || Oversight.may(c, p, Perm.ASSIGN_CHUNKS)
        val owned = known && cl.plot && cl.owner != null
        return Detail(
            x = k.x, z = k.z, country = c.name, relation = rel, type = if (known) cl.type else "",
            owner = if (known) cl.owner?.let { Names.of(p.server, it) } ?: "" else "",
            roles = if (own) cl.roles.map { (id, r) -> "${Names.of(p.server, id)}: ${r.name.lowercase()}" } else emptyList(),
            debt = if (priv) cl.debt else 0, price = if (known) Buffs.price(c, cl) else 0, period = if (known) Realm.period(cl) else 0,
            free = priv && cl.free, capital = known && cl.capital, note = "", access = access(p, k), at = if (known) cl.at else 0, ownerId = if (known) cl.owner ?: "" else "",
            locked = if (priv) Planner.unclaimLock(cl)?.json() ?: "" else "",
            state = if (owned) cl.state.name.lowercase() else "", until = if (owned) cl.until else 0, taken = taken,
            offer = if (cl.plot && (!taken || Oversight.may(c, p, Perm.HOUSING))) renting(c, cl, me) else null,
            mine = if (tenant) tenancy(c, cl) else null,
            workerNames = if (jobs) cl.workers.map { Names.of(p.server, it) } else emptyList(), workerIds = if (jobs) cl.workers.toList() else emptyList()
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
            val plot = cl.plot && cl.owner != null
            ClaimLine(
                cl.x, cl.z, cl.type, cl.capital, cl.free && priv, if (priv) cl.debt else 0, cl.owner?.let { o -> Names.of(p.server, o) } ?: "",
                if (mine) cl.roles.size else 0, cl.at, cl.owner ?: "", if (priv) Planner.unclaimLock(cl)?.json() ?: "" else "",
                if (cl.owner != null) Housing.rate(c, cl) else Housing.price(c, cl, Claimant.CITIZEN, null), if (plot) cl.state.name.lowercase() else "", if (plot) cl.until else 0,
                if (plot) cl.category?.name?.lowercase().orEmpty() else "", if (plot && mine) cl.rentDebt else 0, cl.workers.size
            )
        }

    internal fun homes(me: String): List<HomeLine> = Realm.data.claims.filter { it.owner == me && it.plot }.mapNotNull { cl ->
        Realm.data.countries[cl.country]?.let { c -> HomeLine(c.name, cl.dim, cl.x, cl.z, cl.state.name.lowercase(), Housing.rate(c, cl), cl.rentDebt, cl.until, cl.category?.name?.lowercase().orEmpty(), cl.rentCredit, (today() + 1) * Config.s.dayMillis - now(), Treasury.room(c)) }
    }

    fun names(p: ServerPlayer, text: String) { suggestions[p.uuid] = suggest(p, text.trim().take(16)) }

    private var pooled: Pair<Int, List<String>>? = null

    private fun pool(p: ServerPlayer): List<String> = pooled?.takeIf { it.first == Realm.rev }?.second
        ?: (Realm.home.keys + Realm.data.claims.mapNotNull { it.owner }).toSet().map { Names.of(p.server, it) }.distinct().also { pooled = Realm.rev to it }

    private fun suggest(p: ServerPlayer, text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val pool = pool(p)
        val exact = pool.filter { it.equals(text, true) }
        val near = if (text.length > 3) pool.filter { n -> !n.equals(text, true) && n.contains(text, true) }.sortedBy { !it.startsWith(text, true) } else emptyList()
        return (exact + near).take(8)
    }

    private fun invitedCountries(me: String): List<String> =
        Realm.data.countries.values.filter { it.active && (it.invites[me] ?: 0) > now() }.map { it.name }

    private fun info(p: ServerPlayer, c: Country, own: Country?, delegated: Boolean, fame: Double?): Info {
        val s = Config.s
        val me = p.stringUUID
        val claims = Realm.claims(c.id)
        val sum = Upkeep.summary(c)
        val borderTax = Buffs.billedTax(c)
        val priv = delegated || View.privileged(c, me)
        val staff = Oversight.may(c, p, Perm.INVITE)
        val provStaff = Oversight.may(c, p, Perm.PROVINCES)
        val mine = Perm.entries.filter { Oversight.may(c, p, it) && Perms.permitted(p, it) }
        val seesPeople = delegated || Perm.VIEW_PEOPLE in mine
        val seesJobs = delegated || Perm.VIEW_JOBS in mine
        val held = Realm.held(c.id)
        return Info(
            c.name, if (delegated) "" else c.members[me]?.rank.orEmpty(), c.treasury, sum.upkeep, sum.income, sum.jobs,
            sum.runway(c.treasury).json(), claims.size, claims.count { it.free }, View.color(c),
            (today() + 1) * s.dayMillis - now(), priv,
            c.members.entries.sortedBy { Permissions.position(c, it.value.rank) }.map { (id, m) ->
                if (seesPeople || id == me) mem(p, id, m.rank, m, c, held[id]?.size ?: 0) else Mem(id, Names.of(p.server, id), "")
            },
            if (staff) pendingMembers(p, c.requests) else emptyList(),
            if (staff) c.outsiders.map { (id, r) -> mem(p, id, r.name.lowercase(), c = c) } else emptyList(),
            claimLines(p, claims, c, me, priv),
            claims.filter { !it.free }.groupBy { it.type }.map { (t, list) -> Break(t, list.size, list.sumOf { Buffs.price(c, it, borderTax).toDouble() / Realm.period(it) }) },
            held[me]?.size ?: 0,
            c.parent?.let { Realm.country(it)?.name } ?: "", c.taxMode.name.lowercase(), c.taxAmount, c.provinceDebt, c.independenceRequested,
            if (provStaff) c.provinceInvites.filterValues { it.until > now() }.mapNotNull { (pid, o) ->
                Realm.country(pid)?.let { par -> ProvinceOfferLine(par.name, o.mode.name.lowercase(), o.amount, o.until, o.answered, View.color(par), par.members.size, Realm.claimCount(par.id)) }
            } else emptyList(),
            if (provStaff) c.provinceRequests.filterValues { it > now() }.keys.mapNotNull { Realm.country(it)?.name } else emptyList(),
            c.provinces.mapNotNull { pid ->
                Realm.country(pid)?.let { pr -> ProvinceLine(pr.name, pr.taxMode.name.lowercase(), pr.taxAmount, pr.provinceDebt, pr.independenceRequested, View.color(pr), pr.members.size, Realm.claimCount(pr.id), Upkeep.summary(pr).income + pr.pending, flag(pr), Oversight.canManage(pr)) }
            },
            delegated, flag(c), Realm.country(c.parent)?.let { View.color(it) } ?: 0, Provinces.cooldown(c), Alerts.nextBill(c), Realm.freeAllowed(c),
            c.created, c.moved, if (staff) pendingMembers(p, c.invites) else emptyList(), c.pending,
            offer(c, null), c.guestPlots.mapKeys { it.key.name.lowercase() }, c.rentDebtLimit, c.moveOutDays, Levels.capacity(c, Capacity.JOB_SLOTS), s.maxRent,
            if (delegated) Oversight.perms(c, p).map { it.name } else emptyList(), c.overlordManage,
            if (c.parent == null) emptyList() else c.overlordPerms.map { it.name },
            wars(c), c.reputation.roundToInt(), fame, Fame.count(c),
            c.warDebts.mapKeys { Wars.name(it.key) }, c.warOverlord?.let(Wars::name).orEmpty(),
            SafeChunks.active(c).map { SafeLine(it.dim, it.x, it.z) }, SafeChunks.limit(c), c.warCooldownUntil, power(c, me, Perms.has(p, Perms.ADMIN)),
            GovSync.ranks(c), mine.map { it.name }, GovSync.groups(c), GovSync.government(c, me, Perm.DETAILS in mine), GovSync.sectors(c, seesJobs || Perm.SECTORS in mine),
            if (seesJobs) GovSync.jobSpecs(c) else emptyList(), if (seesJobs) GovSync.contracts(c) else emptyList(),
            if (Permissions.holds(c, me, Perm.AUDIT)) GovSync.audit(p.server, c) else emptyList(),
            GovSync.rankCap(c), Levels.capacity(c, Capacity.SECTORS),
            if (Permissions.holds(c, me, Perm.WITHDRAW)) GovSync.withdrawals(p.server, c) else emptyList()
        )
    }

    fun power(c: Country, viewer: String, admin: Boolean) = if (admin || viewer in c.members) Power.score(c) else null

    private fun sideOf(war: War, id: String) = when (war.side(id)) { null -> ""; war.attacker -> "attacker"; else -> "defender" }

    private fun stakes(s: Stakes) = StakeLine(s.chunks.size, s.money, s.vassal, s.provinces.map(Wars::name))

    private fun joinSides(c: Country, w: War) = w.sides.filter { s -> Realm.country(s.main)?.let { Joining.can(c, w, it) } == true }
        .map { if (it === w.attacker) "attacker" else "defender" }

    private fun wars(c: Country): List<WarLine> {
        val joinable = Realm.data.wars.filter { it.active && it.side(c.id) == null && c.id in it.eligible }.associateWith { joinSides(c, it) }.filterValues { it.isNotEmpty() }
        return (Wars.all(c) + joinable.keys).map { w ->
            val party = w.side(c.id) != null
            val sides = joinable[w].orEmpty()
            val o = w.offer?.takeIf { party }
            WarLine(
                w.id, w.kind.name.lowercase(), w.phase.name.lowercase(), w.phaseEnds, Wars.name(w.attacker.main), Wars.name(w.defender.main),
                w.attacker.joined.map(Wars::name), w.defender.joined.map(Wars::name), sideOf(w, c.id), stakes(w.stakes),
                o?.let { OfferTerms(it.stakes?.let { st -> stakes(st) }, it.ceasefireDays, it.peace, it.extendDays, it.billPct, it.at) }, o?.let { Wars.name(it.from) }.orEmpty(),
                w.attacker.exhaustion, w.defender.exhaustion, if (party) w.attacker.bill.values.sum() else 0, if (party) w.defender.bill.values.sum() else 0,
                if (party) w.fee else 0, if (party) w.escrow else 0, w.ceasefires, w.extendedDays, w.result?.name?.lowercase().orEmpty(), sides.isNotEmpty(), sides
            )
        }
    }

    private fun warHud(own: Country?): List<WarHud> = own?.let { c ->
        Wars.all(c).map { w -> WarHud(w.phase.name.lowercase(), w.phaseEnds, Wars.name(w.side(c.id)?.let(w::opponent)?.main ?: ""), sideOf(w, c.id)) }
    }.orEmpty()

    private data class SnapKey(
        val rev: Int, val bucket: Int, val focus: Key?, val viewing: String?, val watching: Set<String>?, val subject: String?,
        val funds: Long, val here: Key, val y: Int, val online: Int
    )

    private val cache = HashMap<UUID, Pair<SnapKey, Snap>>()

    private class Base(
        val chunks: Int, val color: Int, val flag: FlagLine, val parent: String, val president: String, val capitalX: Int, val capitalZ: Int,
        val level: Int, val researched: Int, val reputation: Int, val fame: Double?, val atWar: Boolean
    ) {
        var power: Double? = null
        var powered = false
    }

    private class Shared(val rev: Int, val bucket: Int, val settings: Settings) {
        val types = HashMap<String?, List<TypeLine>>()
        val bases = HashMap<String, Base>()
        val fame by lazy { Fame.scores() }
        val limits = limits(settings)
    }

    private var shared: Shared? = null

    private fun shared(p: ServerPlayer): Shared {
        val rev = Realm.rev
        val bucket = p.server.tickCount / 20
        val s = Config.s
        return shared?.takeIf { it.rev == rev && it.bucket == bucket && it.settings === s } ?: Shared(rev, bucket, s).also { shared = it }
    }

    fun encode(p: ServerPlayer, reply: Reply, open: Boolean, consume: Boolean = true): String {
        val key = SnapKey(
            Realm.rev, p.server.tickCount / 20, focus[p.uuid], viewing[p.uuid], watching[p.uuid], subjects[p.uuid],
            Bank.funds(p.uuid), Service.here(p), p.blockY, p.server.playerList.playerCount
        )
        val base = cache[p.uuid]?.takeIf { it.first == key }?.second ?: build(p).also { cache[p.uuid] = key to it }
        val preview = if (consume) previews.remove(p.uuid) else previews[p.uuid]
        val names = (if (consume) suggestions.remove(p.uuid) else suggestions[p.uuid]).orEmpty()
        return json.encodeToString(base.copy(
            msg = reply.msg, ok = reply.ok, open = open, rid = reply.rid, reason = reply.reason, targetX = reply.target?.x ?: 0, targetZ = reply.target?.z ?: 0,
            hasTarget = reply.target != null, extraRids = reply.extraRids, preview = preview, names = names
        ))
    }

    private fun build(p: ServerPlayer): Snap {
        val s = Config.s
        val me = p.stringUUID
        val own = Realm.of(me)
        val viewed = viewed(p)
        val delegated = viewed != null
        val c = viewed ?: own
        val here = Service.here(p)
        val sections = watching[p.uuid] ?: emptySet()
        val lead = c != null && Oversight.may(c, p, Perm.CLAIM)
        val sh = shared(p)
        val types = sh.types.getOrPut(c?.id) {
            s.types.map { (n, d) ->
                TypeLine(
                    n, d.price, d.period, Action.entries.map { a -> (c?.rules?.get(n)?.get(a)?.takeUnless { ruleLocked(n, a) } ?: d.rule.access[a] ?: Access.NONE).name.lowercase() },
                    d.rule.machines, c?.fire?.get(n) ?: d.rule.fire,
                    d.job ?: "", Action.entries.map { a -> (d.rule.access[a] ?: Access.NONE).name.lowercase() }
                )
            }
        }
        val world = "world" in sections || c == null
        val fame = if (world) sh.fame else null
        val admin = Perms.has(p, Perms.ADMIN)
        val scope = Scope.of(me, p.hasPermissions(Config.s.visibility.opLevel))
        val countries = if (world) Realm.data.countries.values.filter { it.active }.map { x ->
            val b = sh.bases.getOrPut(x.id) {
                val capital = Realm.claimsView(x.id).firstOrNull { it.capital }
                Base(
                    Realm.claimCount(x.id), View.color(x), flag(x), Realm.country(x.parent)?.name ?: "", Permissions.president(x)?.let { Names.of(p.server, it) } ?: "",
                    capital?.x ?: 0, capital?.z ?: 0, Research.level(x), x.research.done.size, x.reputation.roundToInt(), fame?.get(x.id), Wars.involved(x)
                )
            }
            if (admin && !b.powered) { b.power = Power.score(x); b.powered = true }
            Line(
                x.name, x.members.size, b.chunks, b.color, b.flag, b.parent, x.provinces.size,
                b.president, x.created, relationLabel(x, me, own), if (scope(x.id)) b.capitalX else 0, if (scope(x.id)) b.capitalZ else 0,
                trade = own?.let { Diplomacy.relation(it, x) } ?: "neutral",
                alliance = own?.let { o -> when { Diplomacy.allied(o, x) -> "allied"; x.id in o.allianceOffers -> "offer_in"; o.id in x.allianceOffers -> "offer_out"; else -> "" } } ?: "",
                tariff = own?.let { Diplomacy.policy(it, x).tariffPct } ?: 0, theirTariff = own?.let { Diplomacy.policy(x, it).tariffPct } ?: 0,
                embargo = own?.let { Diplomacy.policy(it, x).embargo } ?: false,
                level = b.level, researched = b.researched,
                reputation = b.reputation, fame = b.fame, atWar = b.atWar, myRating = x.ratings[me]?.stars ?: 0,
                power = if (admin) b.power else null,
                membership = x.government.levers.membership.name.lowercase(), id = x.id
            )
        } else emptyList()
        val players = if (world) (Realm.home.keys + p.server.playerList.players.filter { it === p || !Staff.veiled(it) }.map { it.stringUUID }).distinct().mapNotNull { id ->
            val other = id.toUuidOrNull() ?: return@mapNotNull null
            PlayerLine(id, Names.of(p.server, id), citizenships(id) { id == me || scope(it) }, online(p, id), Gifts.pending(other, p.uuid), Gifts.pending(p.uuid, other))
        }.sortedBy { it.name } else emptyList()
        val treasuryView = c != null && (delegated || Permissions.holds(c, me, Perm.TREASURY_VIEW))
        val ledger = if (c != null && ("ledger" in sections || "dashboard" in sections) && treasuryView)
            c.ledger.let { if ("ledger" in sections) it else it.takeLast(20) }.asReversed().map { LedgerLine(it.at, it.kind.name.lowercase(), it.amount, it.balance, it.actor?.let { a -> Names.of(p.server, a) } ?: "", it.note) }
        else emptyList()
        val history = if (c != null && ("history" in sections || "dashboard" in sections) && treasuryView) c.history.map {
            DayLine(it.day, it.treasury, it.income, it.upkeep, it.jobs, it.tributeIn, it.tributeOut, it.deposits, it.withdrawals, it.chunks, it.debtChunks, it.members, it.plots)
        } else emptyList()
        return Snap(
            c?.let { info(p, it, own, delegated, if (fame != null) fame[it.id] else Fame.score(it)) }, countries,
            invitedCountries(me),
            types,
            focus[p.uuid]?.let { detail(p, it) }, Bank.funds(p.uuid), s.freeChunks, (s.jobShare * 100).toInt(), c?.let { Levels.capacity(it, Capacity.PLOTS) } ?: 0, s.maxProvinceDebt,
            players, "", true, false, here.x, here.z,
            alerts = Alerts.of(p, c, delegated), goals = if (c != null && lead) Goals.of(c).map { GoalLine(it.text.json(), it.value, it.max, it.page.orEmpty()) } else emptyList(),
            ledger = ledger, history = history, limits = sh.limits, rights = Provinces.delegatedRights, kept = Provinces.keptRights, me = me, dim = here.dim,
            rank = own?.members?.get(me)?.rank.orEmpty(), homes = homes(me), admin = admin, war = warHud(own),
            personal = subject(p)?.let { PersonalView(it.toString(), homes(it.toString()), Bank.funds(it)) }
        )
    }
}
