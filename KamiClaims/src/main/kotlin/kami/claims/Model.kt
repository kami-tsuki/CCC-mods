package kami.claims

import kami.claims.economy.Treasury
import kami.claims.research.BuffState
import kami.claims.research.Boost
import kami.claims.research.Buffs
import kami.claims.research.Capacity
import kami.claims.research.Levels
import kami.claims.research.Loans
import kami.claims.research.Queue
import kami.claims.research.ResearchState
import kami.claims.net.ResearchSync
import kami.claims.service.Housing
import kami.claims.service.Announce
import kami.claims.service.Work
import kami.claims.war.ChunkRef
import kami.claims.war.War
import kami.claims.war.WarDebt
import kami.libs.economy.Flow
import kami.libs.stats.Stats
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import net.minecraft.server.MinecraftServer
import kotlin.math.floor
import kotlin.math.max

@Serializable
enum class Rank { BANISHED, ALLIED, CITIZEN, OFFICER, CHANCELLOR, PRESIDENT }

@Serializable
enum class Cap { CLAIM, CAPITAL, TAX, RULES, WITHDRAW, INVITE, MEMBERS, RANK, JOBS, PLOT, DETAILS, PROVINCE, TRADE, RESEARCH, HOUSING }

@Serializable
enum class TaxMode { PERCENT, FLAT }

fun tributeOwed(mode: TaxMode, amount: Double, base: Long): Long = when (mode) {
    TaxMode.PERCENT -> floor(base * amount).toLong()
    TaxMode.FLAT -> amount.toLong()
}.coerceAtLeast(0)

fun rentSplit(debt: Long, credit: Long, rate: Long, room: Long, amount: Long): Pair<Long, Long> {
    val arrears = minOf(debt, amount, room)
    return arrears to if (debt > arrears) 0L else minOf(amount - arrears, rate * Housing.CREDIT_DAYS - credit).coerceAtLeast(0)
}

@Serializable
enum class Access { NONE, OFFICER, ASSIGNED, JOB, WORKER, CITIZEN, ALLIED, ANY }

@Serializable
enum class Action { BREAK, PLACE, INTERACT, CONTAINER }

val overlordRanks = listOf(Rank.OFFICER, Rank.CHANCELLOR, Rank.PRESIDENT)

fun ruleLocked(type: String, a: Action) = type == "wilderness" && (a == Action.BREAK || a == Action.PLACE)

@Serializable
enum class Role { OWNER, HOUSEHOLD, ALLIED, BANISHED }

@Serializable
enum class Claimant { CITIZEN, PARENT, PROVINCE, ALLIED, RANDOM }

@Serializable
enum class Tenancy { ACTIVE, REMOVED, MOVING_OUT }

@Serializable
enum class CountryState { ACTIVE, DISBANDED }

enum class Leave { LEAVE, KICK, BAN }

@Serializable
class Offer(val open: MutableSet<Claimant> = mutableSetOf(), val rent: MutableMap<Claimant, Int> = mutableMapOf())

@Serializable
class Job(var progress: Int = 0, var start: Long = today())

data class Key(val dim: String, val x: Int, val z: Int) {
    override fun toString() = "$dim|$x|$z"
}

fun now() = System.currentTimeMillis()
fun today() = now() / Config.s.dayMillis

@Serializable
class Member(
    var rank: Rank,
    val jobs: MutableMap<String, Job> = mutableMapOf(),
    val since: Long = now(),
    var seen: Long = now(),
    val mail: MutableList<String> = mutableListOf(),
    var job: String? = null,
    var progress: Int? = null,
    var start: Long? = null,
    var zone: MutableSet<String>? = null
)

@Serializable
class JobDef(var pay: Int, var quota: Int, var period: Int)

@Serializable
class ProvinceOffer(val until: Long, val mode: TaxMode, val amount: Double, val answered: Boolean = false)

@Serializable
enum class LedgerKind { DEPOSIT, WITHDRAW, CLAIM, UPKEEP, PLOT_TAX, JOB_PAY, TRIBUTE_IN, TRIBUTE_OUT, ADJUST, TARIFF, RESEARCH, LEVEL_REWARD, FEE, LOAN_IN, LOAN_PAYMENT, WAR_FEE, WAR_STAKE, WAR_BILL, WAR_DEBT, LOAN_RESERVE, LOAN_RELEASE, LOAN_RETURN }

@Serializable
class LedgerEntry(val at: Long, val kind: LedgerKind, val amount: Long, val balance: Long, val actor: String? = null, val note: String = "")

@Serializable
class DayStat(
    val day: Long, val at: Long, val treasury: Long, val income: Long, val upkeep: Long, val jobs: Long, val tributeIn: Long, val tributeOut: Long,
    val deposits: Long, val withdrawals: Long, val chunks: Int, val debtChunks: Int, val members: Int, val plots: Int, val types: Map<String, Int> = emptyMap(),
    val ledger: Map<String, Long> = emptyMap()
)

@Serializable
class TradePolicy(val tariffPct: Int = 0, val embargo: Boolean = false)

@Serializable
data class Flag(val pattern: Int = 0, val emblem: Int = 0, val secondary: Int = 0xFFFFFF)

@Serializable
class Claim(
    val country: String,
    val dim: String,
    val x: Int,
    val z: Int,
    var type: String,
    val at: Long = now(),
    val since: Long = today(),
    var free: Boolean = false,
    var capital: Boolean = false,
    var debt: Int = 0,
    var upkeepCycles: Int = 0,
    var unclaimWarned: Boolean = false,
    var owner: String? = null,
    val roles: MutableMap<String, Role> = mutableMapOf(),
    var state: Tenancy = Tenancy.ACTIVE,
    var rentDebt: Long = 0,
    var rentCredit: Long = 0,
    var until: Long = 0,
    var category: Claimant? = null,
    var offer: Offer? = null,
    val workers: MutableSet<String> = mutableSetOf(),
    var tax: Int? = null,
    var lapse: Int? = null
) {
    val key get() = Key(dim, x, z)
    val def get() = Config.s.types[type]
    val plot get() = type == Housing.RESIDENTIAL
    val tenant get() = if (plot && Housing.tenanted(this)) owner else null

    fun vacate() {
        owner = null
        roles.clear()
        state = Tenancy.ACTIVE
        rentDebt = 0
        rentCredit = 0
        until = 0
        category = null
    }
}

@Serializable
class Loan(val id: String, val principal: Long, var total: Long, var paid: Long, val perDay: Long, val takenDay: Long, var overdue: Long = 0, var overdueDay: Long = 0, val lender: String? = null, var missed: Int = 0)

@Serializable
enum class LendState { DISABLED, ACTIVE, LENT }

@Serializable
class LendOffer(
    val id: String, val amount: Long, val termDays: Int, val interestPct: Int, val tariff: Boolean, val minRep: Int, val maxRep: Int, val relist: Boolean,
    var state: LendState = LendState.DISABLED, var borrower: String? = null, val created: Long = now()
)

@Serializable
class Rating(val stars: Int, val at: Long, val hours: Double)

@Serializable
class Country(
    var name: String,
    val created: Long = now(),
    var treasury: Long = 0,
    var pending: Long = 0,
    var lastActive: Long = now(),
    var moved: Long = 0,
    var color: Int = 0,
    val members: MutableMap<String, Member> = mutableMapOf(),
    val outsiders: MutableMap<String, Rank> = mutableMapOf(),
    val rules: MutableMap<String, MutableMap<Action, Access>> = mutableMapOf(),
    val fire: MutableMap<String, Boolean> = mutableMapOf(),
    val jobs: MutableMap<String, JobDef> = mutableMapOf(),
    val invites: MutableMap<String, Long> = mutableMapOf(),
    val requests: MutableMap<String, Long> = mutableMapOf(),
    var parent: String? = null,
    var taxMode: TaxMode = TaxMode.FLAT,
    var taxAmount: Double = 0.0,
    var provinceDebt: Int = 0,
    var independenceRequested: Boolean = false,
    var independenceDeclinedAt: Long = 0,
    var overlordManage: Boolean = false,
    val overlordCaps: MutableMap<Cap, Rank> = mutableMapOf(),
    var flag: Flag = Flag(),
    val ledger: MutableList<LedgerEntry> = mutableListOf(),
    val history: MutableList<DayStat> = mutableListOf(),
    val provinces: MutableSet<String> = mutableSetOf(),
    val provinceInvites: MutableMap<String, ProvinceOffer> = mutableMapOf(),
    val provinceRequests: MutableMap<String, Long> = mutableMapOf(),
    val autoAllies: MutableSet<String> = mutableSetOf(),
    val alliances: MutableSet<String> = mutableSetOf(),
    val allianceOffers: MutableMap<String, Long> = mutableMapOf(),
    val tradePolicy: MutableMap<String, TradePolicy> = mutableMapOf(),
    val research: ResearchState = ResearchState(),
    var xp: Long = 0,
    val xpToday: MutableMap<String, Long> = mutableMapOf(),
    var xpDay: Long = 0,
    val xpFrac: MutableMap<String, Double> = mutableMapOf(),
    val boosts: MutableMap<String, MutableList<Boost>> = mutableMapOf(),
    var rewardedLevel: Int = 0,
    var level: Int = 1,
    var slug: String = "",
    val tokens: MutableMap<String, Int> = mutableMapOf(),
    val counters: MutableMap<String, Long> = mutableMapOf(),
    val buffs: BuffState = BuffState(),
    val loans: MutableList<Loan> = mutableListOf(),
    val loanCooldowns: MutableMap<String, Long> = mutableMapOf(),
    val lendOffers: MutableList<LendOffer> = mutableListOf(),
    var loanBlockDay: Long = 0,
    val offer: Offer = Offer(mutableSetOf(Claimant.CITIZEN), Config.s.rentDefaults.toMutableMap()),
    var rentDebtLimit: Long = 50,
    var moveOutDays: Int = 3,
    val reclaimLocks: MutableMap<String, Long> = mutableMapOf(),
    val rankPlots: MutableMap<Rank, Int> = Config.s.rankPlots.toMutableMap(),
    val guestPlots: MutableMap<Claimant, Int> = Config.s.guestPlots.toMutableMap(),
    val playerPlots: MutableMap<String, Int> = mutableMapOf(),
    var state: CountryState = CountryState.ACTIVE,
    var tax: Int? = null,
    var shutdown: Int? = null,
    var release: Int? = null,
    var warCooldownUntil: Long = 0,
    val warPairs: MutableMap<String, Long> = mutableMapOf(),
    val warPairsBy: MutableMap<String, Long> = mutableMapOf(),
    val warDeclared: MutableList<Long> = mutableListOf(),
    val warDebts: MutableMap<String, Long> = mutableMapOf(),
    var warOverlord: String? = null,
    var reputation: Double = Config.s.war.repStart,
    var minReputation: Int = 0,
    val repWeek: MutableMap<String, Double> = mutableMapOf(),
    var repWeekStart: Long = 0,
    val tradeLog: MutableMap<String, MutableList<Long>> = mutableMapOf(),
    val policyLog: MutableMap<String, MutableList<Long>> = mutableMapOf(),
    var tributeOf: String? = null,
    var tributeSince: Long = 0,
    var tributeHeld: Long = 0,
    var tributeHigh: Long = 0,
    val ratings: MutableMap<String, Rating> = mutableMapOf(),
    val safeChunks: MutableList<ChunkRef> = mutableListOf()
) {
    val id get() = slug.ifEmpty { name.lowercase() }
    val active get() = state == CountryState.ACTIVE
    fun rank(id: String) = members[id]?.rank ?: outsiders[id]
    fun president() = members.entries.firstOrNull { it.value.rank == Rank.PRESIDENT }?.key

    fun resetOverlord() {
        overlordManage = false
        overlordCaps.clear()
    }
    fun job(name: String): JobDef? = jobs[name] ?: Config.s.jobs[name]?.let { JobDef(it.pay, it.quota, it.period) }
}

@Serializable
class Reserve(val dim: String, val x: Int, val z: Int, val country: String, val until: Long)

@Serializable
class TempBlock(val dim: String, val pos: Long, val start: Long, val due: Long, val fluid: String = "") {
    @Transient var stage = -1
}

@Serializable
class Data(
    val countries: MutableMap<String, Country> = mutableMapOf(),
    val claims: MutableList<Claim> = mutableListOf(),
    val reserves: MutableList<Reserve> = mutableListOf(),
    var day: Long = -1,
    var schema: Int = 0,
    val letters: MutableMap<String, MutableList<String>> = mutableMapOf(),
    val temp: MutableList<TempBlock> = mutableListOf(),
    val wars: MutableList<War> = mutableListOf(),
    var warClock: Long = 0
) {
    companion object {
        const val CURRENT = 1
    }
}

object Realm {
    var data = Data()
    val index = HashMap<Key, Claim>()
    private val byCountry = HashMap<String, MutableList<Claim>>()
    val home = HashMap<String, String>()
    var rev = 0
    var claimRev = 0

    fun changed(territory: Boolean = true) {
        claimRev++
        rev++
    }

    fun load(server: MinecraftServer) {
        Announce.server = server
        reset(RealmDb.load())
    }

    fun reset(next: Data) {
        rev++
        claimRev++
        Buffs.clearBorders()
        data = next
        val orphans = data.claims.filter { it.country !in data.countries }
        data.claims.removeAll { it.country !in data.countries }
        index.clear()
        home.clear()
        byCountry.clear()
        data.claims.forEach { index[it.key] = it; byCountry.getOrPut(it.country) { mutableListOf() } += it }
        data.countries.values.filter { it.active }.forEach { c -> c.members.keys.forEach { home[it] = c.id }; c.level = c.level.coerceAtLeast(1) }
        migrate(data)
        data.countries.values.forEach { c -> if (c.parent != null && country(c.parent) == null) { c.parent = null; c.provinceDebt = 0; c.independenceRequested = false } }
        data.countries.values.forEach { c -> c.provinces.removeAll { it !in data.countries || country(it)?.parent != c.id } }
        data.countries.values.forEach { c ->
            c.alliances.removeAll { country(it)?.alliances?.contains(c.id) != true }
            c.allianceOffers.keys.removeAll { it !in data.countries }
            c.tradePolicy.keys.removeAll { it !in data.countries }
        }
        syncAllies()
        orphans.forEach(Housing::refund)
    }

    private fun migrate(data: Data) {
        if (data.schema >= 1) return
        val citizen = Claimant.CITIZEN
        data.countries.values.forEach { c ->
            c.tax?.let { c.offer.rent[citizen] = it }
            val shutdown = c.shutdown ?: 3
            val release = c.release ?: 3
            if (shutdown != 3 || release != 3) c.rentDebtLimit = (shutdown + release).toLong() * (c.offer.rent[citizen] ?: 0)
            c.tax = null
            c.shutdown = null
            c.release = null
            c.members.forEach { (id, m) ->
                m.job?.let { job ->
                    m.jobs[job] = Job(m.progress ?: 0, m.start ?: today())
                    val zone = m.zone.orEmpty()
                    claims(c.id).filter { it.type == Config.s.jobs[job]?.type && (zone.isEmpty() || it.key.toString() in zone) }.forEach { it.workers += id }
                }
                m.job = null
                m.progress = null
                m.start = null
                m.zone = null
            }
        }
        data.claims.forEach { cl ->
            val c = data.countries.getValue(cl.country)
            cl.tax?.takeIf { it >= 0 }?.let { cl.offer = Offer(mutableSetOf(citizen), mutableMapOf(citizen to it)) }
            if (cl.owner != null) cl.category = citizen
            val rent = cl.offer?.rent?.get(citizen) ?: c.offer.rent[citizen] ?: 0
            cl.rentDebt = (cl.lapse ?: 0).toLong() * rent
            if (cl.owner != null && cl.rentDebt > c.rentDebtLimit) {
                cl.state = Tenancy.MOVING_OUT
                cl.until = now() + c.moveOutDays * Config.s.dayMillis
            }
            cl.tax = null
            cl.lapse = null
        }
        data.schema = Data.CURRENT
    }

    fun country(name: String?) = name?.let { n -> data.countries[n] ?: data.countries[n.lowercase()] ?: data.countries.values.firstOrNull { it.name.equals(n, true) } }
    fun of(id: String) = live(home[id])
    fun live(name: String?) = country(name)?.takeIf { it.active }
    fun claims(name: String): List<Claim> = byCountry[name]?.toList() ?: emptyList()
    fun claimCount(name: String) = byCountry[name]?.size ?: 0
    fun claimsView(name: String): List<Claim> = byCountry[name].orEmpty()
    fun held(name: String): Map<String, List<Key>> = byCountry[name].orEmpty().asSequence().filter { it.owner != null }.groupBy({ it.owner!! }, { it.key })
    fun at(dim: String, x: Int, z: Int) = index[Key(dim, x, z)]

    fun reservedFor(dim: String, x: Int, z: Int): String? {
        val t = now()
        return data.reserves.firstOrNull { it.until >= t && it.dim == dim && it.x == x && it.z == z }?.country
    }

    fun add(c: Claim) {
        data.claims += c
        index[c.key] = c
        byCountry.getOrPut(c.country) { mutableListOf() } += c
        Stats.add("country.claim", "c:${c.country}")
        Buffs.dropBorders(c.country)
        neighbors(c).forEach { k -> index[k]?.let { Buffs.dropBorders(it.country) } }
        data.reserves.removeAll { it.dim == c.dim && it.x == c.x && it.z == c.z }
        changed()
    }

    fun unclaim(c: Claim, reserve: Boolean) {
        Housing.refund(c)
        data.claims.remove(c)
        index.remove(c.key)
        byCountry[c.country]?.remove(c)
        Stats.add("country.unclaim", "c:${c.country}")
        Buffs.dropBorders(c.country)
        neighbors(c).forEach { k -> index[k]?.let { Buffs.dropBorders(it.country) } }
        if (reserve) data.reserves += Reserve(c.dim, c.x, c.z, c.country, now() + Config.s.reserveDays * Config.s.dayMillis)
        changed()
    }

    private fun unclaimAll(batch: List<Claim>) {
        if (batch.isEmpty()) return
        batch.forEach(Housing::refund)
        val gone = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Claim, Boolean>()).apply { addAll(batch) }
        data.claims.removeAll(gone)
        byCountry[batch.first().country]?.removeAll(gone)
        Stats.add("country.unclaim", "c:${batch.first().country}", n = batch.size.toLong())
        batch.forEach { c ->
            index.remove(c.key)
            Buffs.dropBorders(c.country)
            neighbors(c).forEach { k -> index[k]?.let { Buffs.dropBorders(it.country) } }
        }
        changed()
    }

    fun join(c: Country, id: String, rank: Rank) {
        c.members[id] = Member(rank)
        Stats.add("country.join", "c:${c.id}", "p:$id")
        c.outsiders.remove(id)
        c.invites.remove(id)
        c.requests.remove(id)
        home[id] = c.id
        syncAllies()
        ResearchSync.refresh(c)
        ResearchSync.menus(c)
        changed()
    }

    fun leave(c: Country, id: String, reason: Leave) {
        c.members.remove(id)
        Stats.add("country.leave", "c:${c.id}", "p:$id")
        home.remove(id)
        claims(c.id).forEach { it.roles.remove(id) }
        Work.forget(c, id)
        syncAllies()
        Housing.departed(c, id, reason)
        ResearchSync.menus(null)
        changed()
    }

    fun disband(c: Country) {
        if (!c.active) return
        WarDebt.forgive(c.id)
        Stats.event("country.disband", "c:${c.id}")
        Loans.dissolve(c)
        Treasury.drain(c, Flow.DISBAND, "disband")
        val tenants = claims(c.id).filter { it.owner != null }
        unclaimAll(claims(c.id).filter { it.owner == null })
        c.members.keys.forEach { home.remove(it) }
        c.members.clear()
        c.invites.clear()
        c.requests.clear()
        c.parent?.let { country(it)?.provinces?.remove(c.id) }
        c.parent = null
        val children = c.provinces.mapNotNull { country(it) }
        children.forEach { it.parent = null; it.provinceDebt = 0; it.independenceRequested = false }
        c.provinces.clear()
        Queue.rescope(*children.toTypedArray())
        c.alliances.clear()
        c.allianceOffers.clear()
        data.countries.values.forEach { it.alliances.remove(c.id); it.allianceOffers.remove(c.id); it.tradePolicy.remove(c.id) }
        if (tenants.isEmpty()) return erase(c)
        c.state = CountryState.DISBANDED
        Announce.fire("disbanded", c, mapOf("country" to c.name))
        tenants.forEach { Housing.moveOut(c, it, "dissolved") }
        syncAllies()
        changed()
    }

    fun erase(c: Country) {
        if (c.active) Announce.fire("disbanded", c, mapOf("country" to c.name))
        unclaimAll(claims(c.id).toList())
        Treasury.drain(c, Flow.DISBAND, "erase")
        data.countries.remove(c.id)
        Buffs.dropBorders(c.id)
        Levels.forget(c.id)
        syncAllies()
        changed()
    }

    fun family(id: String): List<Country> {
        val c = country(id) ?: return emptyList()
        val top = c.parent?.let { country(it) } ?: c
        return listOf(top) + top.provinces.mapNotNull { country(it) }
    }

    fun partners(c: Country): Set<String> =
        (family(c.id) + c.alliances.mapNotNull { country(it) }).filter { it.id != c.id }.flatMap { it.members.keys }.toSet() - c.members.keys

    fun syncAllies() = data.countries.values.forEach { c ->
        val others = partners(c)
        others.forEach { pid -> if (c.outsiders[pid] == null) { c.outsiders[pid] = Rank.ALLIED; c.autoAllies += pid } }
        (c.autoAllies - others).forEach { pid -> if (c.outsiders[pid] == Rank.ALLIED) c.outsiders.remove(pid); c.autoAllies.remove(pid) }
    }

    fun neighbors(c: Claim) = neighbors(c.key)
    fun neighbors(k: Key) = listOf(Key(k.dim, k.x + 1, k.z), Key(k.dim, k.x - 1, k.z), Key(k.dim, k.x, k.z + 1), Key(k.dim, k.x, k.z - 1))

    private fun flood(start: Key, within: Set<Key>): Set<Key> {
        val seen = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) neighbors(queue.removeFirst()).forEach { k -> if (k in within && seen.add(k)) queue.addLast(k) }
        return seen
    }

    fun removable(target: Claim) = removableBatch(target.country, target.dim, setOf(target.key))

    fun removableBatch(country: String, dim: String, keys: Set<Key>): Boolean {
        val all = claims(country).filter { it.dim == dim }.mapTo(HashSet()) { it.key }
        val seen = HashSet<Key>()
        return keys.asSequence().filter { it in all && it !in seen }.all { k ->
            val rest = flood(k, all).also(seen::addAll) - keys
            rest.isEmpty() || flood(rest.first(), rest).size == rest.size
        }
    }

    fun freeAllowed(c: Country): Int {
        if (now() - c.lastActive > Config.s.inactiveDays * Config.s.dayMillis) return 0
        return Config.s.freeChunks + Levels.capacity(c, Capacity.FREE_CHUNKS) + minOf(Config.s.freeBonusCap, c.members.size / max(1, Config.s.freeBonusMembers))
    }

    fun refreshFree(c: Country) {
        val allowed = freeAllowed(c)
        claims(c.id).sortedWith(compareBy({ !it.capital }, { it.at })).forEachIndexed { i, cl -> cl.free = i < allowed }
    }

    fun price(cl: Claim) = cl.def?.price ?: 0
    fun period(cl: Claim) = max(1, cl.def?.period ?: 1)
}
