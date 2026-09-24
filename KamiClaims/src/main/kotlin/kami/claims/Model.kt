package kami.claims

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.max

@Serializable
enum class Rank { BANISHED, ALLIED, CITIZEN, OFFICER, CHANCELLOR, PRESIDENT }

@Serializable
enum class Cap { CLAIM, CAPITAL, TAX, RULES, WITHDRAW, INVITE, MEMBERS, RANK, JOBS, PLOT, DETAILS, PROVINCE }

@Serializable
enum class TaxMode { PERCENT, FLAT }

@Serializable
enum class Access { NONE, OFFICER, JOB, WORKER, CITIZEN, ALLIED, ANY }

@Serializable
enum class Action { BREAK, PLACE, INTERACT, CONTAINER }

@Serializable
enum class Role { OWNER, HOUSEHOLD, ALLIED, BANISHED }

data class Key(val dim: String, val x: Int, val z: Int) {
    override fun toString() = "$dim|$x|$z"
}

fun now() = System.currentTimeMillis()
fun today() = now() / Config.s.dayMillis

@Serializable
class Member(
    var rank: Rank,
    var job: String? = null,
    val since: Long = now(),
    var seen: Long = now(),
    var progress: Int = 0,
    var start: Long = today(),
    val zone: MutableSet<String> = mutableSetOf(),
    val mail: MutableList<String> = mutableListOf()
)

@Serializable
class JobDef(var pay: Int, var quota: Int, var period: Int)

@Serializable
class ProvinceOffer(val until: Long, val mode: TaxMode, val amount: Double)

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
    var owner: String? = null,
    var tax: Int = -1,
    var lapse: Int = 0,
    val roles: MutableMap<String, Role> = mutableMapOf()
) {
    val key get() = Key(dim, x, z)
    val def get() = Config.s.types[type]
}

@Serializable
class Country(
    val name: String,
    val created: Long = now(),
    var treasury: Long = 0,
    var pending: Long = 0,
    var lastActive: Long = now(),
    var moved: Long = 0,
    var color: Int = 0,
    var tax: Int = Config.s.residentialTax,
    var shutdown: Int = Config.s.shutdownDays,
    var release: Int = Config.s.releaseDays,
    val members: MutableMap<String, Member> = mutableMapOf(),
    val outsiders: MutableMap<String, Rank> = mutableMapOf(),
    val rules: MutableMap<String, MutableMap<Action, Access>> = mutableMapOf(),
    val machines: MutableMap<String, Boolean> = mutableMapOf(),
    val fire: MutableMap<String, Boolean> = mutableMapOf(),
    val fluid: MutableMap<String, Boolean> = mutableMapOf(),
    val jobs: MutableMap<String, JobDef> = mutableMapOf(),
    val invites: MutableMap<String, Long> = mutableMapOf(),
    val requests: MutableMap<String, Long> = mutableMapOf(),
    var parent: String? = null,
    var taxMode: TaxMode = TaxMode.FLAT,
    var taxAmount: Double = 0.0,
    var provinceDebt: Int = 0,
    var independenceRequested: Boolean = false,
    val provinces: MutableSet<String> = mutableSetOf(),
    val provinceInvites: MutableMap<String, ProvinceOffer> = mutableMapOf(),
    val provinceRequests: MutableMap<String, Long> = mutableMapOf(),
    val autoAllies: MutableSet<String> = mutableSetOf()
) {
    val id get() = name.lowercase()
    fun rank(id: String) = members[id]?.rank ?: outsiders[id]
    fun president() = members.entries.firstOrNull { it.value.rank == Rank.PRESIDENT }?.key
    fun job(name: String): JobDef? = jobs[name] ?: Config.s.jobs[name]?.let { JobDef(it.pay, it.quota, it.period) }
}

@Serializable
class Reserve(val dim: String, val x: Int, val z: Int, val country: String, val until: Long)

@Serializable
class Data(
    val countries: MutableMap<String, Country> = mutableMapOf(),
    val claims: MutableList<Claim> = mutableListOf(),
    val reserves: MutableList<Reserve> = mutableListOf(),
    var day: Long = -1
)

object Realm {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private var file: Path? = null
    var data = Data()
    val index = HashMap<Key, Claim>()
    private val byCountry = HashMap<String, MutableList<Claim>>()
    val home = HashMap<String, String>()
    var dirty = false
    var rev = 0

    fun changed() {
        rev++
        dirty = true
    }

    fun load(server: MinecraftServer) {
        val path = server.getWorldPath(LevelResource.ROOT).resolve("kami_claims.json")
        file = path
        reset(if (Files.exists(path)) runCatching { json.decodeFromString<Data>(Files.readString(path)) }.getOrElse {
            KamiClaims.LOG.error("Unreadable claims data, kept as .bad", it)
            Files.move(path, path.resolveSibling("kami_claims.json.bad"), StandardCopyOption.REPLACE_EXISTING)
            Data()
        } else Data())
    }

    fun save(force: Boolean = false) {
        val path = file ?: return
        if (!dirty && !force) return
        val tmp = path.resolveSibling("kami_claims.json.tmp")
        Files.writeString(tmp, json.encodeToString(data))
        if (Files.exists(path)) Files.copy(path, path.resolveSibling("kami_claims.json.bak"), StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        dirty = false
    }

    fun reset(next: Data) {
        data = next
        data.claims.removeAll { it.country !in data.countries }
        index.clear()
        home.clear()
        byCountry.clear()
        data.claims.forEach { index[it.key] = it; byCountry.getOrPut(it.country) { mutableListOf() } += it }
        data.countries.values.forEach { c -> c.members.keys.forEach { home[it] = c.id } }
        data.countries.values.forEach { c -> if (c.parent != null && country(c.parent) == null) { c.parent = null; c.provinceDebt = 0; c.independenceRequested = false } }
        data.countries.values.forEach { c -> c.provinces.removeAll { it !in data.countries || country(it)?.parent != c.id } }
        data.countries.values.filter { it.parent == null }.forEach { syncFamily(it.id) }
    }

    fun country(name: String?) = name?.let { data.countries[it.lowercase()] }
    fun of(id: String) = country(home[id])
    fun claims(name: String): List<Claim> = byCountry[name]?.toList() ?: emptyList()
    fun at(dim: String, x: Int, z: Int) = index[Key(dim, x, z)]

    fun reservedFor(dim: String, x: Int, z: Int): String? {
        val t = now()
        data.reserves.removeAll { it.until < t }
        return data.reserves.firstOrNull { it.dim == dim && it.x == x && it.z == z }?.country
    }

    fun add(c: Claim) {
        data.claims += c
        index[c.key] = c
        byCountry.getOrPut(c.country) { mutableListOf() } += c
        data.reserves.removeAll { it.dim == c.dim && it.x == c.x && it.z == c.z }
        changed()
    }

    fun unclaim(c: Claim, reserve: Boolean) {
        data.claims.remove(c)
        index.remove(c.key)
        byCountry[c.country]?.remove(c)
        if (reserve) data.reserves += Reserve(c.dim, c.x, c.z, c.country, now() + Config.s.reserveDays * Config.s.dayMillis)
        changed()
    }

    fun join(c: Country, id: String, rank: Rank) {
        c.members[id] = Member(rank)
        c.outsiders.remove(id)
        c.invites.remove(id)
        c.requests.remove(id)
        home[id] = c.id
        syncFamily(c.id)
        changed()
    }

    fun leave(c: Country, id: String) {
        c.members.remove(id)
        home.remove(id)
        claims(c.id).filter { it.owner == id || it.roles.containsKey(id) }.forEach {
            if (it.owner == id) { it.owner = null; it.roles.clear(); it.lapse = 0 } else it.roles.remove(id)
        }
        syncFamily(c.id)
        changed()
    }

    fun disband(c: Country) {
        claims(c.id).forEach { unclaim(it, false) }
        c.members.keys.forEach { home.remove(it) }
        val parentId = c.parent
        val formerProvinces = c.provinces.toList()
        c.parent?.let { country(it)?.provinces?.remove(c.id) }
        c.provinces.forEach { pid -> country(pid)?.let { it.parent = null; it.provinceDebt = 0; it.independenceRequested = false } }
        data.countries.remove(c.id)
        parentId?.let { syncFamily(it) }
        formerProvinces.forEach { syncFamily(it) }
        changed()
    }

    fun family(id: String): List<Country> {
        val c = country(id) ?: return emptyList()
        val top = c.parent?.let { country(it) } ?: c
        return listOf(top) + top.provinces.mapNotNull { country(it) }
    }

    fun syncFamily(id: String) {
        val fam = family(id)
        if (fam.size <= 1) {
            val c = country(id) ?: return
            c.autoAllies.toList().forEach { pid -> if (c.outsiders[pid] == Rank.ALLIED) c.outsiders.remove(pid); c.autoAllies.remove(pid) }
            return
        }
        val allMembers = fam.flatMap { it.members.keys }.toSet()
        fam.forEach { c ->
            val others = allMembers - c.members.keys
            others.forEach { pid -> if (c.outsiders[pid] == null) { c.outsiders[pid] = Rank.ALLIED; c.autoAllies += pid } }
            (c.autoAllies - others).forEach { pid -> if (c.outsiders[pid] == Rank.ALLIED) c.outsiders.remove(pid); c.autoAllies.remove(pid) }
        }
    }

    fun neighbors(c: Claim) = listOf(Key(c.dim, c.x + 1, c.z), Key(c.dim, c.x - 1, c.z), Key(c.dim, c.x, c.z + 1), Key(c.dim, c.x, c.z - 1))

    fun removable(target: Claim): Boolean {
        val rest = claims(target.country).filter { it !== target && it.dim == target.dim }.associateBy { it.key }
        val start = rest.values.firstOrNull() ?: return true
        val seen = hashSetOf(start.key)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) neighbors(queue.removeFirst()).forEach { k ->
            rest[k]?.let { if (seen.add(k)) queue.addLast(it) }
        }
        return seen.size == rest.size
    }

    fun freeAllowed(c: Country): Int {
        if (now() - c.lastActive > Config.s.inactiveDays * Config.s.dayMillis) return 0
        return Config.s.freeChunks + minOf(Config.s.freeBonusCap, c.members.size / max(1, Config.s.freeBonusMembers))
    }

    fun refreshFree(c: Country) {
        val allowed = freeAllowed(c)
        claims(c.id).sortedWith(compareBy({ !it.capital }, { it.at })).forEachIndexed { i, cl -> cl.free = i < allowed }
    }

    fun price(cl: Claim) = cl.def?.price ?: 0
    fun period(cl: Claim) = max(1, cl.def?.period ?: 1)
}
