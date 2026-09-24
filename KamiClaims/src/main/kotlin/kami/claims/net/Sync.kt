package kami.claims.net

import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.service.Service
import kami.claims.service.Upkeep
import kami.claims.service.View

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

@Serializable class Line(val name: String, val members: Int, val chunks: Int)
@Serializable class Mem(val id: String, val name: String, val rank: String, val job: String = "", val progress: Int = 0, val seen: Long = 0)
@Serializable class JobLine(val name: String, val type: String, val pay: Int, val quota: Int, val period: Int)
@Serializable class TypeLine(val name: String, val price: Int, val period: Int, val access: List<String>, val machines: Boolean, val fire: Boolean, val fluid: Boolean)
@Serializable class ClaimLine(val x: Int, val z: Int, val type: String, val capital: Boolean, val free: Boolean, val debt: Int, val owner: String, val tax: Int, val lapse: Int, val roles: Int)
@Serializable class Break(val type: String, val count: Int, val perDay: Double)
@Serializable class ProvinceOfferLine(val name: String, val mode: String, val amount: Double)
@Serializable class ProvinceLine(val name: String, val mode: String, val amount: Double, val debt: Int, val wantsIndependence: Boolean)
@Serializable class Citizen(val country: String, val role: String, val via: String = "")
@Serializable class PlayerLine(val id: String, val name: String, val citizenships: List<Citizen>)
@Serializable class Detail(
    val x: Int, val z: Int, val country: String, val relation: Int, val type: String, val owner: String, val roles: List<String>,
    val debt: Int, val tax: Int, val lapse: Int, val price: Int, val period: Int, val free: Boolean, val capital: Boolean, val note: String
)
@Serializable class Info(
    val name: String, val rank: String, val treasury: Long, val upkeep: Long, val income: Long, val jobs: Long, val runway: String, val tax: Int,
    val chunks: Int, val free: Int, val shutdown: Int, val release: Int, val color: Int, val nextBilling: Long, val details: Boolean,
    val members: List<Mem>, val requests: List<Mem>, val relations: List<Mem>, val jobList: List<JobLine>,
    val claimList: List<ClaimLine>, val breakdown: List<Break>, val plots: Int,
    val parent: String, val taxMode: String, val taxAmount: Double, val provinceDebt: Int, val independenceRequested: Boolean,
    val provinceInvites: List<ProvinceOfferLine>, val provinceRequests: List<String>, val provinces: List<ProvinceLine>, val delegated: Boolean
)
@Serializable class Snap(
    val info: Info?, val countries: List<Line>, val invites: List<String>, val types: List<TypeLine>, val jobNames: List<String>,
    val caps: Map<String, String>, val detail: Detail?, val funds: Long, val freeChunks: Int, val jobShare: Int, val maxPlots: Int, val maxProvinceDebt: Int,
    val players: List<PlayerLine>, val msg: String, val ok: Boolean, val open: Boolean, val px: Int, val pz: Int
)

object Sync {
    private val json = Json { encodeDefaults = true }
    private val focus = HashMap<UUID, Key>()
    private val viewing = HashMap<UUID, String>()

    fun focus(p: ServerPlayer, x: Int, z: Int) { focus[p.uuid] = Key(Service.here(p).dim, x, z) }
    fun view(p: ServerPlayer, name: String) { if (name.isBlank()) viewing.remove(p.uuid) else viewing[p.uuid] = name }
    fun forget(p: ServerPlayer) { focus.remove(p.uuid); viewing.remove(p.uuid) }

    private fun citizenships(id: String): List<Citizen> {
        val list = ArrayList<Citizen>()
        val own = Realm.of(id)
        own?.members?.get(id)?.let { list += Citizen(own.name, it.rank.name.lowercase()) }
        Realm.data.countries.values.forEach { c ->
            if (id in c.autoAllies) c.outsiders[id]?.let { list += Citizen(c.name, it.name.lowercase(), own?.name ?: "") }
        }
        return list
    }

    private fun mem(p: ServerPlayer, id: String, rank: String, m: Member? = null) =
        Mem(id, Names.of(p.server, id), rank, m?.job ?: "", m?.progress ?: 0, m?.seen ?: 0)

    private fun detail(p: ServerPlayer, k: Key): Detail {
        val me = p.stringUUID
        val mine = Realm.of(me)
        val cl = Realm.index[k]
        val c = cl?.let { Realm.data.countries[it.country] }
        if (cl == null || c == null) {
            val reserved = Realm.reservedFor(k.dim, k.x, k.z)?.let { Realm.data.countries[it]?.name }
            val note = when {
                reserved != null -> "Reserved for $reserved after an unclaim."
                mine == null -> "Nomansland."
                else -> Service.claimError(mine, k, Config.s.defaultType) ?: "Free to claim."
            }
            return Detail(k.x, k.z, "", 0, "", "", emptyList(), 0, -1, 0, 0, 0, false, false, note)
        }
        val rel = View.relation(c, me)
        if (rel == 0) return Detail(k.x, k.z, c.name, 0, "", "", emptyList(), 0, -1, 0, 0, 0, false, false, "Claimed by ${c.name}.")
        val priv = View.privileged(c, me)
        val own = priv || cl.owner == me
        return Detail(
            k.x, k.z, c.name, rel, cl.type, cl.owner?.let { Names.of(p.server, it) } ?: "",
            if (own) cl.roles.map { (id, r) -> "${Names.of(p.server, id)}: ${r.name.lowercase()}" } else emptyList(),
            if (priv) cl.debt else 0, if (own) (if (cl.tax >= 0) cl.tax else c.tax) else -1, if (own) cl.lapse else 0,
            Realm.price(cl), Realm.period(cl), priv && cl.free, cl.capital, ""
        )
    }

    fun encode(p: ServerPlayer, msg: String, ok: Boolean, open: Boolean): String {
        val s = Config.s
        val me = p.stringUUID
        val own = Realm.of(me)
        val viewed = viewing[p.uuid]?.let { Realm.country(it) }
            ?.takeIf { own != null && it.parent == own.id && Service.rankOf(own, p) >= Rank.CHANCELLOR }
        val delegated = viewed != null
        val c = viewed ?: own
        val here = Service.here(p)
        val info = c?.let {
            val claims = Realm.claims(it.id)
            val sum = Upkeep.summary(it)
            val priv = delegated || View.privileged(it, me)
            val staff = !delegated && Service.rankOf(it, p) >= s.min(Cap.INVITE)
            val provStaff = !delegated && Service.rankOf(it, p) >= s.min(Cap.PROVINCE)
            val rank = if (delegated) Service.rankOf(own!!, p) else Service.rankOf(it, p)
            Info(
                it.name, rank.name.lowercase(), it.treasury, sum.upkeep, sum.income, sum.jobs,
                sum.runway(it.treasury), it.tax, claims.size, claims.count { cl -> cl.free }, it.shutdown, it.release, View.color(it),
                (today() + 1) * s.dayMillis - now(), priv,
                it.members.map { (id, m) -> mem(p, id, m.rank.name.lowercase(), m) }.sortedByDescending { m -> Rank.valueOf(m.rank.uppercase()) },
                if (staff) it.requests.keys.map { id -> mem(p, id, "") } else emptyList(),
                if (staff) it.outsiders.map { (id, r) -> mem(p, id, r.name.lowercase()) } else emptyList(),
                s.jobs.keys.mapNotNull { j -> it.job(j)?.let { d -> JobLine(j, s.jobs.getValue(j).type, d.pay, d.quota, d.period) } },
                claims.sortedWith(compareBy({ cl -> cl.type }, { cl -> cl.x }, { cl -> cl.z })).map { cl ->
                    val own = priv || cl.owner == me
                    ClaimLine(
                        cl.x, cl.z, cl.type, cl.capital, cl.free && priv, if (priv) cl.debt else 0, cl.owner?.let { o -> Names.of(p.server, o) } ?: "",
                        if (own) (if (cl.tax >= 0) cl.tax else it.tax) else -1, if (own) cl.lapse else 0, if (own) cl.roles.size else 0
                    )
                },
                claims.filter { cl -> !cl.free }.groupBy { cl -> cl.type }.map { (t, list) -> Break(t, list.size, list.sumOf { cl -> Realm.price(cl).toDouble() / Realm.period(cl) }) },
                claims.count { cl -> cl.owner == me },
                it.parent?.let { pid -> Realm.country(pid)?.name } ?: "", it.taxMode.name.lowercase(), it.taxAmount, it.provinceDebt, it.independenceRequested,
                if (provStaff) it.provinceInvites.filterValues { o -> o.until > now() }.mapNotNull { (pid, o) -> Realm.country(pid)?.let { par -> ProvinceOfferLine(par.name, o.mode.name.lowercase(), o.amount) } } else emptyList(),
                if (provStaff) it.provinceRequests.filterValues { until -> until > now() }.mapNotNull { (pid, _) -> Realm.country(pid)?.name } else emptyList(),
                it.provinces.mapNotNull { pid -> Realm.country(pid)?.let { pr -> ProvinceLine(pr.name, pr.taxMode.name.lowercase(), pr.taxAmount, pr.provinceDebt, pr.independenceRequested) } },
                delegated
            )
        }
        val types = s.types.map { (n, d) ->
            TypeLine(
                n, d.price, d.period, Action.values().map { a -> (c?.rules?.get(n)?.get(a) ?: d.rule.access[a] ?: Access.NONE).name.lowercase() },
                c?.machines?.get(n) ?: d.rule.machines, c?.fire?.get(n) ?: d.rule.fire, c?.fluid?.get(n) ?: d.rule.fluid
            )
        }
        val players = Realm.home.keys.map { id -> PlayerLine(id, Names.of(p.server, id), citizenships(id)) }.sortedBy { it.name }
        val snap = Snap(
            info, Realm.data.countries.values.map { Line(it.name, it.members.size, Realm.claims(it.id).size) },
            Realm.data.countries.values.filter { (it.invites[me] ?: 0) > now() }.map { it.name },
            types, s.jobs.keys.toList(), s.caps.mapKeys { it.key.name.lowercase() }.mapValues { it.value.name.lowercase() },
            focus[p.uuid]?.let { detail(p, it) }, Bank.funds(p.uuid), s.freeChunks, (s.jobShare * 100).toInt(), s.maxPlots, s.maxProvinceDebt,
            players, msg, ok, open, here.x, here.z
        )
        return json.encodeToString(snap)
    }
}
