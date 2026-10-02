package kami.claims.service

import kami.claims.net.ResearchSync
import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.economy.Treasury
import kami.claims.research.Buffs
import kami.claims.research.Loans
import kami.claims.research.Capacity
import kami.claims.research.Deposit
import kami.claims.research.Features
import kami.claims.research.Tokens
import kami.claims.research.Levels
import kami.claims.research.Progress
import kami.claims.research.Queue
import kami.claims.service.Words.chunks
import kami.claims.service.Words.count
import kami.claims.service.Words.days
import kami.claims.service.Words.money
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.claims.social.Perms
import kami.claims.world.Effects
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Fail(val phrase: Phrase, val reason: String = "", val target: Key? = null) : kami.libs.command.CommandFail(phrase::component) {
    constructor(key: String, vararg args: Any) : this(Phrase.of(key, *args))
}

internal val DELEGATE_RANK = Rank.CHANCELLOR

class NeedsConfirm(val lines: List<Phrase>) : RuntimeException()

object Service {
    private val s get() = Config.s
    private var acting: String? = null
    val delegableCaps = setOf(Cap.CLAIM, Cap.CAPITAL, Cap.TAX, Cap.RULES, Cap.JOBS, Cap.HOUSING)

    fun here(p: ServerPlayer) = Key(p.level().dimension().location().toString(), p.chunkPosition().x, p.chunkPosition().z)
    fun home(p: ServerPlayer) = Realm.of(p.stringUUID) ?: throw Fail("kami_claims.error.no_country")
    fun rankOf(c: Country, p: ServerPlayer) = c.members[p.stringUUID]?.rank ?: Rank.CITIZEN

    fun act(p: ServerPlayer, name: String, a: List<String>, asCountry: String = ""): Phrase {
        acting = asCountry.ifBlank { null }
        try {
            return dispatch(p, name, a)
        } finally {
            acting = null
        }
    }

    internal fun need(p: ServerPlayer, min: Rank): Country {
        val c = home(p)
        if (rankOf(c, p) < min) throw Fail("kami_claims.error.rank", Words.rank(min))
        return c
    }

    private fun permit(p: ServerPlayer, cap: Cap) {
        if (!Perms.has(p, Perms.capNode(cap))) throw Fail("kami_claims.error.permission")
    }

    internal fun need(p: ServerPlayer, cap: Cap): Country {
        permit(p, cap)
        val target = acting
        if (cap in delegableCaps && target != null) {
            val own = home(p)
            if (Realm.country(target) !== own) {
                val delegate = Realm.country(target) ?: throw Fail("kami_claims.error.unknown_country")
                if (delegate.parent != own.id) throw Fail("kami_claims.error.not_your_province")
                if (rankOf(own, p) < DELEGATE_RANK) throw Fail("kami_claims.error.delegate_rank")
                return delegate
            }
        }
        return need(p, s.min(cap))
    }

    private fun arg(a: List<String>, i: Int) = a.getOrNull(i) ?: throw Fail("kami_claims.error.missing_argument")
    private fun num(a: List<String>, i: Int) = arg(a, i).toIntOrNull() ?: throw Fail("kami_claims.error.number")
    private fun <T : Enum<T>> find(values: Array<T>, text: String): T? = values.firstOrNull { it.name.equals(text, true) }
    private fun <T : Enum<T>> parse(values: Array<T>, text: String): T =
        find(values, text) ?: throw Fail("kami_claims.error.choice", v(values.joinToString { it.name.lowercase() }))

    private fun spot(p: ServerPlayer, a: List<String>, i: Int) =
        if (a.size > i + 1) Key(here(p).dim, num(a, i), num(a, i + 1)) else here(p)

    private fun who(p: ServerPlayer, a: List<String>, i: Int): String {
        val text = arg(a, i)
        return runCatching { UUID.fromString(text).toString() }.getOrNull()
            ?: Names.id(p.server, text)
            ?: throw Fail("kami_claims.error.unknown_player")
    }

    private fun confirmed(a: List<String>) = a.lastOrNull() == "confirm"

    private fun member(c: Country, id: String) = c.members[id] ?: throw Fail("kami_claims.error.not_member")

    private fun citizenRoom(c: Country) {
        Features.requireRoom(c, Capacity.CITIZENS, c.members.size)
    }

    private fun mine(c: Country, k: Key) = Realm.index[k]?.takeIf { it.country == c.id } ?: throw Fail("kami_claims.error.not_your_chunk")

    fun claimError(c: Country, k: Key, type: String): Phrase? {
        val owned = Realm.claims(c.id)
        return Planner.blockReason(c, k, type, owned.map { it.key }.toHashSet(), c.treasury, owned.size)
    }

    fun addClaim(c: Country, k: Key, type: String, capital: Boolean = false) {
        val free = Realm.claims(c.id).size < Realm.freeAllowed(c)
        if (!free) Treasury.move(c, LedgerKind.CLAIM, -s.types.getValue(type).price.toLong(), note = "${k.x}, ${k.z}")
        Realm.add(Claim(c.id, k.dim, k.x, k.z, type, capital = capital, free = free))
        ResearchSync.refresh(c)
    }

    private fun dispatch(p: ServerPlayer, name: String, a: List<String>): Phrase {
        val text = when (name) {
            "create" -> create(p, arg(a, 0))
            "disband" -> disband(p, confirmed(a))
            "leave" -> leave(p)
            "invite" -> invite(p, who(p, a, 0))
            "accept" -> accept(p, arg(a, 0))
            "join" -> join(p, arg(a, 0))
            "approve" -> approve(p, who(p, a, 0))
            "deny" -> need(p, Cap.INVITE).let { it.requests.remove(who(p, a, 0)); Phrase.of("kami_claims.done.request_denied") }
            "kick" -> kick(p, who(p, a, 0), false)
            "banish" -> kick(p, who(p, a, 0), true)
            "ally" -> ally(p, who(p, a, 0))
            "clear" -> need(p, Cap.MEMBERS).let { it.outsiders.remove(who(p, a, 0)); Phrase.of("kami_claims.done.relation_cleared") }
            "rank" -> rank(p, who(p, a, 0), parse(Rank.values(), arg(a, 1)))
            "president" -> president(p, who(p, a, 0), confirmed(a))
            "claim" -> claim(p, arg(a, 0), num(a, 1).coerceIn(0, 8), a.getOrNull(2)?.let { spot(p, a, 2) })
            "unclaim" -> unclaim(p, spot(p, a, 0))
            "type" -> retype(p, arg(a, 0), spot(p, a, 1))
            "capital" -> capital(p, spot(p, a, 0))
            "rename" -> Naming.rename(need(p, Rank.PRESIDENT), arg(a, 0), p.stringUUID)
            "deposit" -> deposit(p, num(a, 0))
            "withdraw" -> withdraw(p, num(a, 0))
            "tax" -> need(p, Cap.TAX).let { Housing.edit(it, null, Claimant.CITIZEN, true, num(a, 0)); Phrase.of("kami_claims.done.tax", Words.rate(Housing.rent(it), 1)) }
            "rule" -> rule(p, arg(a, 0), arg(a, 1), arg(a, 2))
            "rules" -> arg(a, 0).split(';').filter { it.isNotBlank() }.let { changes ->
                changes.forEach { change -> change.split(':').takeIf { it.size == 3 }?.let { (t, f, v) -> rule(p, t, f, v) } ?: throw Fail("kami_claims.error.rule_format") }
                Phrase.of("kami_claims.done.rules", count("kami_claims.unit.change", changes.size))
            }
            "plot_law" -> plotLaw(need(p, Cap.TAX), num(a, 0), num(a, 1))
            "plot_offer" -> plotOffer(p, a)
            "plot_limit" -> plotLimit(p, need(p, Cap.TAX), arg(a, 0), num(a, 1))
            "job_set" -> jobSet(p, arg(a, 0), arg(a, 1), num(a, 2))
            "job_edit" -> { jobSet(p, arg(a, 0), "pay", num(a, 1)); jobSet(p, arg(a, 0), "quota", num(a, 2)); jobSet(p, arg(a, 0), "period", num(a, 3)) }
            "job_add" -> Work.jobAdd(need(p, Cap.JOBS), who(p, a, 0), arg(a, 1))
            "job_remove" -> Work.jobRemove(need(p, Cap.JOBS), who(p, a, 0), arg(a, 1))
            "assign" -> need(p, Cap.JOBS).let { Work.assign(it, who(p, a, 0), mine(it, spot(p, a, 1))) }
            "unassign" -> need(p, Cap.JOBS).let { Work.unassign(it, delegatedRank(it, p), p.stringUUID, who(p, a, 0), mine(it, spot(p, a, 1))) }
            "flag" -> need(p, Cap.RULES).let {
                fun hex(i: Int) = arg(a, i).removePrefix("#").toIntOrNull(16)?.and(0xFFFFFF) ?: throw Fail("kami_claims.error.hex", v("ffffff"))
                it.color = hex(0)
                it.flag = Flag(num(a, 1).coerceIn(0, 31), num(a, 2).coerceIn(0, 63), hex(3))
                Phrase.of("kami_claims.done.flag", v(it.name))
            }
            "claimcells" -> claimRect(p, arg(a, 0), cells(p, a, 1))
            "typecells" -> typeRect(p, arg(a, 0), cells(p, a, 1))
            "unclaimcells" -> unclaimRect(p, cells(p, a, 0))
            "plot_claim" -> plotClaim(p, spot(p, a, 0))
            "plot_remove" -> plotRemove(p, spot(p, a, 0), confirmed(a))
            "plot_release" -> plotRelease(p, spot(p, a, 0))
            "plot_trust" -> plotOwner(p, spot(p, a, 2)).let { cl ->
                who(p, a, 0).let { id -> if (id == cl.owner) throw Fail("kami_claims.error.plot_owner_self"); cl.roles[id] = parse(Role.values(), arg(a, 1)) }
                Phrase.of("kami_claims.done.role_set")
            }
            "plot_untrust" -> plotOwner(p, spot(p, a, 1)).let { it.roles.remove(who(p, a, 0)); Phrase.of("kami_claims.done.role_removed") }
            "province_accept" -> provinceAccept(p, arg(a, 0), confirmed(a))
            "province_invite", "province_request", "province_approve", "province_deny", "province_release", "province_forgive",
            "province_independence", "province_withdraw", "province_decline", "province_tax" -> province(p, name, a)
            "province_give" -> provinceGive(p, arg(a, 0), arg(a, 1), confirmed(a))
            "loan_take" -> Loans.take(need(p, Cap.WITHDRAW), arg(a, 0), p.stringUUID)
            "loan_repay" -> Loans.repay(need(p, Cap.WITHDRAW), arg(a, 0), p.stringUUID)
            "buff_toggle" -> Buffs.toggle(need(p, Cap.RESEARCH), arg(a, 0))
            "research_enqueue", "research_start", "research_pause", "research_move", "research_deposit" -> researchAct(p, name, a)
            "alliance" -> alliance(p, arg(a, 0), country(arg(a, 1)))
            "tariff" -> Diplomacy.setTariff(need(p, Cap.TRADE), country(arg(a, 0)), num(a, 1))
            "embargo" -> Diplomacy.setEmbargo(need(p, Cap.TRADE), country(arg(a, 0)), arg(a, 1) == "on")
            else -> throw Fail("kami_claims.error.unknown_action")
        }
        Realm.changed()
        return text
    }

    private fun create(p: ServerPlayer, n: String): Phrase {
        if (Realm.of(p.stringUUID) != null) throw Fail("kami_claims.error.leave_first")
        Naming.check(n)
        val independent = Realm.data.countries.values.count { it.parent == null }
        if (s.maxCountries > 0 && independent >= s.maxCountries) throw Fail("kami_claims.error.country_limit", Words.num(s.maxCountries))
        val c = Country(n)
        claimError(c, here(p), s.defaultType)?.let { throw Fail(it) }
        Realm.data.countries[c.id] = c
        Realm.join(c, p.stringUUID, Rank.PRESIDENT)
        addClaim(c, here(p), s.defaultType, true)
        Effects.founded(p, c)
        return Phrase.of("kami_libs.format.join", Phrase.of("kami_claims.done.founded", v(n), chunks(Realm.freeAllowed(c))), Phrase.of("kami_claims.notice.release_lock"))
    }

    private fun disband(p: ServerPlayer, confirmed: Boolean): Phrase {
        val c = need(p, Rank.PRESIDENT)
        Loans.requireNoLoans(c, "kami_claims.loans.error.disband")
        if (!confirmed) throw NeedsConfirm(listOf(
            Phrase.of("kami_claims.common.disband_x", v(c.name)),
            Phrase.of("kami_claims.confirm.disband.land", chunks(Realm.claims(c.id).size)),
            Phrase.of("kami_claims.confirm.disband.members", count("kami_claims.unit.member", c.members.size)),
            Phrase.of("kami_claims.confirm.disband.treasury", money(c.treasury))
        ) + Realm.claims(c.id).count { it.owner != null }.takeIf { it > 0 }?.let { listOf(Phrase.of("kami_claims.confirm.disband.tenants", count("kami_claims.unit.plot", it))) }.orEmpty())
        Realm.disband(c)
        Effects.chime(p, false)
        return Phrase.of("kami_claims.done.disbanded")
    }

    private fun leave(p: ServerPlayer): Phrase {
        val c = home(p)
        if (rankOf(c, p) == Rank.PRESIDENT && c.members.size > 1) throw Fail("kami_claims.error.transfer_first")
        if (c.members.size == 1) Loans.requireNoLoans(c, "kami_claims.loans.error.disband")
        Realm.leave(c, p.stringUUID, Leave.LEAVE)
        ResearchSync.refresh(c)
        if (c.members.isEmpty()) Realm.disband(c)
        return Phrase.of("kami_claims.done.left", v(c.name))
    }

    private fun invite(p: ServerPlayer, id: String): Phrase {
        val c = need(p, Cap.INVITE)
        if (Realm.of(id) != null) throw Fail("kami_claims.error.already_member")
        if (c.outsiders[id] == Rank.BANISHED) throw Fail("kami_claims.error.target_banished")
        citizenRoom(c)
        c.invites[id] = now() + s.inviteDays * s.dayMillis
        Effects.invite(p.server, id, c)
        return Phrase.of("kami_claims.done.invited")
    }

    private fun accept(p: ServerPlayer, country: String): Phrase {
        val c = Realm.live(country) ?: throw Fail("kami_claims.error.unknown_country")
        if (Realm.of(p.stringUUID) != null) throw Fail("kami_claims.error.leave_first")
        if (c.outsiders[p.stringUUID] == Rank.BANISHED) throw Fail("kami_claims.error.you_banished", v(c.name))
        if ((c.invites[p.stringUUID] ?: 0) < now()) throw Fail("kami_claims.error.no_invite")
        citizenRoom(c)
        Realm.join(c, p.stringUUID, Rank.CITIZEN)
        Progress.citizenJoined(c, p.stringUUID)
        Mail.broadcast(c, Phrase.of("kami_claims.mail.joined", v(p.name.string)), Tone.OK)
        return Phrase.of("kami_claims.done.welcome", v(c.name))
    }

    private fun join(p: ServerPlayer, country: String): Phrase {
        val c = Realm.live(country) ?: throw Fail("kami_claims.error.unknown_country")
        if (Realm.of(p.stringUUID) != null) throw Fail("kami_claims.error.leave_first")
        if (c.outsiders[p.stringUUID] == Rank.BANISHED) throw Fail("kami_claims.error.you_banished", v(c.name))
        c.requests[p.stringUUID] = now() + s.inviteDays * s.dayMillis
        Mail.officers(c, Phrase.of("kami_claims.mail.join_request", v(p.name.string)))
        return Phrase.of("kami_claims.done.request_sent")
    }

    private fun approve(p: ServerPlayer, id: String): Phrase {
        val c = need(p, Cap.INVITE)
        if (c.requests[id] == null) throw Fail("kami_claims.error.no_request")
        if (Realm.of(id) != null) throw Fail("kami_claims.error.already_member")
        if (c.outsiders[id] == Rank.BANISHED) throw Fail("kami_claims.error.target_banished")
        citizenRoom(c)
        c.requests.remove(id)
        Realm.join(c, id, Rank.CITIZEN)
        Progress.citizenJoined(c, id)
        Mail.broadcast(c, Phrase.of("kami_claims.mail.joined", v(Names.of(p.server, id))), Tone.OK)
        return Phrase.of("kami_claims.done.request_approved")
    }

    private fun kick(p: ServerPlayer, id: String, banish: Boolean): Phrase {
        val c = need(p, Cap.MEMBERS)
        if (banish) Features.require(c, Features.BANISH)
        val member = c.members[id]
        member?.let {
            if (it.rank >= rankOf(c, p)) throw Fail("kami_claims.error.lower_ranks")
            Realm.leave(c, id, if (banish) Leave.BAN else Leave.KICK)
            ResearchSync.refresh(c)
            Mail.direct(id, Phrase.of(if (banish) "kami_claims.mail.banished" else "kami_claims.mail.removed", v(c.name)), Tone.BAD)
        }
        if (banish) {
            c.outsiders[id] = Rank.BANISHED
            if (member == null) Housing.departed(c, id, Leave.BAN)
        }
        return Phrase.of(if (banish) "kami_claims.done.banished" else "kami_claims.done.removed")
    }

    private fun ally(p: ServerPlayer, id: String): Phrase {
        val c = need(p, Cap.MEMBERS)
        if (c.members.containsKey(id)) throw Fail("kami_claims.error.member_ally")
        c.outsiders[id] = Rank.ALLIED
        return Phrase.of("kami_claims.done.allied")
    }

    private fun rank(p: ServerPlayer, id: String, rank: Rank): Phrase {
        val c = need(p, Cap.RANK)
        val m = member(c, id)
        val mine = rankOf(c, p)
        if (rank !in listOf(Rank.CITIZEN, Rank.OFFICER, Rank.CHANCELLOR)) throw Fail("kami_claims.error.rank_choice")
        if (m.rank >= mine || rank >= mine) throw Fail("kami_claims.error.rank_below")
        if (rank == Rank.CHANCELLOR && c.members.values.any { it.rank == Rank.CHANCELLOR }) throw Fail("kami_claims.error.one_chancellor")
        if (m.rank != rank) Features.requireRank(c, rank)
        m.rank = rank
        Mail.direct(id, Phrase.of("kami_claims.mail.rank", Words.rank(rank)))
        return Phrase.of("kami_claims.done.rank")
    }

    private fun president(p: ServerPlayer, id: String, confirmed: Boolean): Phrase {
        val c = need(p, Rank.PRESIDENT)
        val m = member(c, id)
        val mine = c.members.getValue(p.stringUUID)
        if (m === mine) throw Fail("kami_claims.error.already_president")
        val stepDown = when {
            m.rank >= Rank.OFFICER -> m.rank
            Features.limit(c, Capacity.OFFICERS, Levels.used(c, Capacity.OFFICERS)) == null -> Rank.OFFICER
            else -> Rank.CITIZEN
        }
        if (!confirmed) throw NeedsConfirm(listOf(
            Phrase.of("kami_claims.confirm.president.title", v(Names.of(p.server, id)), v(c.name)),
            Phrase.of("kami_claims.confirm.president.rights"),
            Phrase.of("kami_claims.confirm.president.you", Words.rank(stepDown))
        ))
        mine.rank = stepDown
        m.rank = Rank.PRESIDENT
        Mail.broadcast(c, Phrase.of("kami_claims.mail.president"))
        return Phrase.of("kami_claims.done.president")
    }

    private fun claimCells(c: Country, type: String, cells: List<Key>): Int {
        val plan = Planner.claim(c, type, cells)
        plan.ready.forEach { addClaim(c, it.key, type) }
        return plan.ready.size
    }

    private fun claimReport(c: Country, type: String, count: Int, before: Long, first: Boolean): Phrase {
        if (count == 0) return Phrase.of("kami_claims.error.nothing_claimed")
        val report = Phrase.of("kami_claims.done.claimed", chunks(count), Words.type(type), money(before - c.treasury), money(c.treasury))
        return if (first) Phrase.of("kami_libs.format.join", report, Phrase.of("kami_claims.notice.release_lock")) else report
    }

    private fun ensureUnclaimUnlocked(cl: Claim) {
        val reason = Planner.unclaimLock(cl) ?: return
        if (!cl.unclaimWarned) {
            cl.unclaimWarned = true
            Realm.changed()
        }
        throw Fail(reason)
    }

    private fun claim(p: ServerPlayer, type: String, radius: Int, at: Key?): Phrase {
        val c = need(p, Cap.CLAIM)
        s.types[type] ?: throw Fail("kami_claims.error.unknown_type")
        val origin = at ?: here(p)
        val cells = (-radius..radius).flatMap { dx -> (-radius..radius).map { dz -> Key(origin.dim, origin.x + dx, origin.z + dz) } }
            .sortedBy { max(abs(it.x - origin.x), abs(it.z - origin.z)) }
        if (radius == 0) claimError(c, origin, type)?.let { throw Fail(it) }
        val before = c.treasury
        val beforeClaims = Realm.claims(c.id).size
        val count = claimCells(c, type, cells)
        if (count > 0) Effects.chime(p, true)
        return claimReport(c, type, count, before, beforeClaims == 0)
    }

    private class Rect(val cells: List<Key>)

    private fun rect(p: ServerPlayer, a: List<String>, i: Int): Rect {
        val dim = here(p).dim
        val x1 = min(num(a, i), num(a, i + 2))
        val x2 = max(num(a, i), num(a, i + 2))
        val z1 = min(num(a, i + 1), num(a, i + 3))
        val z2 = max(num(a, i + 1), num(a, i + 3))
        if ((x2 - x1 + 1).toLong() * (z2 - z1 + 1) > s.maxRect) throw Fail("kami_claims.error.too_big", chunks(s.maxRect))
        return Rect((x1..x2).flatMap { x -> (z1..z2).map { z -> Key(dim, x, z) } })
    }

    private fun cells(p: ServerPlayer, a: List<String>, i: Int): Rect {
        if (arg(a, i) != "cells") return rect(p, a, i)
        val dim = here(p).dim
        val keys = arg(a, i + 1).split(',').mapNotNull { pair ->
            val parts = pair.split(':')
            if (parts.size != 2) return@mapNotNull null
            Key(dim, parts[0].toIntOrNull() ?: return@mapNotNull null, parts[1].toIntOrNull() ?: return@mapNotNull null)
        }.distinct()
        if (keys.size > s.maxRect) throw Fail("kami_claims.error.too_big", chunks(s.maxRect))
        return Rect(keys)
    }

    private fun claimRect(p: ServerPlayer, type: String, r: Rect): Phrase {
        val c = need(p, Cap.CLAIM)
        s.types[type] ?: throw Fail("kami_claims.error.unknown_type")
        val before = c.treasury
        val beforeClaims = Realm.claims(c.id).size
        val count = claimCells(c, type, r.cells)
        if (count > 0) Effects.chime(p, true)
        return claimReport(c, type, count, before, beforeClaims == 0)
    }

    private fun unclaimRect(p: ServerPlayer, r: Rect): Phrase {
        val c = need(p, Cap.CLAIM)
        val plan = Planner.unclaim(c, r.cells)
        plan.ready.forEach { cell -> Realm.index[cell.key]?.let { Realm.unclaim(it, false) } }
        ResearchSync.refresh(c)
        val blocked = plan.blocked
        val reason = blocked.firstOrNull()?.reason ?: Phrase.literal("")
        if (plan.ready.isEmpty() && blocked.isNotEmpty()) throw Fail(Phrase.of("kami_claims.error.nothing_released", reason), "BLOCKED", blocked.first().key)
        if (blocked.isEmpty()) return Phrase.of("kami_claims.done.released", chunks(plan.ready.size))
        return Phrase.of("kami_claims.done.released_kept", chunks(plan.ready.size), chunks(blocked.size), reason)
    }

    private fun typeRect(p: ServerPlayer, type: String, r: Rect): Phrase {
        val c = need(p, Cap.CLAIM)
        if (s.types[type] == null) throw Fail("kami_claims.error.unknown_type")
        val set = r.cells.toHashSet()
        val hit = Realm.claims(c.id).filter { it.key in set }
        hit.filter { it.type != type }.forEach(::requireFree)
        if (hit.any { it.type != type }) Features.require(c, Features.claimType(type))
        hit.forEach { applyType(it, type) }
        return Phrase.of("kami_claims.done.retyped", chunks(hit.size), Words.type(type))
    }

    private fun requireFree(cl: Claim) {
        if (cl.owner != null) throw Fail("kami_claims.error.plot_tenanted")
    }

    private fun applyType(cl: Claim, type: String) {
        if (cl.type != type) Work.retyped(cl)
        cl.type = type
    }

    private fun unclaim(p: ServerPlayer, k: Key): Phrase {
        val c = need(p, Cap.CLAIM)
        val cl = mine(c, k)
        if (cl.capital) throw Fail("kami_claims.error.capital")
        requireFree(cl)
        ensureUnclaimUnlocked(cl)
        if (!Realm.removable(cl)) throw Fail("kami_claims.error.split")
        Realm.unclaim(cl, false)
        ResearchSync.refresh(c)
        return Phrase.of("kami_claims.done.chunk_released")
    }

    private fun retype(p: ServerPlayer, type: String, k: Key): Phrase {
        val c = need(p, Cap.CLAIM)
        val cl = mine(c, k)
        if (s.types[type] == null) throw Fail("kami_claims.error.unknown_type")
        if (cl.type != type) {
            requireFree(cl)
            Features.require(c, Features.claimType(type))
        }
        applyType(cl, type)
        return Phrase.of("kami_claims.done.chunk_retyped", Words.type(type), Words.rate(Realm.price(cl), Realm.period(cl)))
    }

    private fun capital(p: ServerPlayer, k: Key): Phrase {
        val c = need(p, Cap.CAPITAL)
        val cl = mine(c, k)
        if (cl.capital) throw Fail("kami_claims.error.already_capital")
        if (now() - c.moved < s.capitalCooldownDays * s.dayMillis) throw Fail("kami_claims.error.capital_cooldown")
        Tokens.spend(c, Tokens.CAPITAL_MOVE, s.capitalMoveCost, p.stringUUID)
        Realm.claims(c.id).forEach { it.capital = false }
        cl.capital = true
        c.moved = now()
        return Phrase.of("kami_claims.done.capital")
    }

    private fun deposit(p: ServerPlayer, n: Int): Phrase {
        val c = home(p)
        if (n < 1) throw Fail(Phrase.of("kami_claims.error.amount"), "AMOUNT")
        if (n > Treasury.room(c)) throw Fail(Phrase.of("kami_claims.error.treasury_full", money(Treasury.room(c))), "AMOUNT")
        if (!Bank.take(p.uuid, n)) throw Fail(Phrase.of("kami_claims.error.funds", money(n)), "FUNDS")
        Treasury.move(c, LedgerKind.DEPOSIT, n.toLong(), p.stringUUID)
        c.pending += n
        return Phrase.of("kami_claims.done.deposited", money(n), money(c.treasury))
    }

    private fun withdraw(p: ServerPlayer, n: Int): Phrase {
        val c = need(p, Cap.WITHDRAW)
        Loans.requireNoLoans(c)
        if (n < 1) throw Fail(Phrase.of("kami_claims.error.amount"), "AMOUNT")
        if (c.treasury < n) throw Fail(Phrase.of("kami_claims.error.treasury_short", money(c.treasury)), "FUNDS")
        if (!Bank.give(p.uuid, n)) throw Fail("kami_claims.error.payout")
        Treasury.move(c, LedgerKind.WITHDRAW, -n.toLong(), p.stringUUID)
        return Phrase.of("kami_claims.done.withdrew", money(n))
    }

    private fun rule(p: ServerPlayer, type: String, field: String, value: String): Phrase {
        val c = need(p, Cap.RULES)
        if (s.types[type] == null) throw Fail("kami_claims.error.unknown_type")
        val flag = { value.toBooleanStrictOrNull() ?: throw Fail("kami_claims.error.bool") }
        when (field) {
            "machines" -> c.machines[type] = flag()
            "fire" -> c.fire[type] = flag()
            else -> c.rules.getOrPut(type) { mutableMapOf() }[parse(Action.values(), field)] = parse(Access.values(), value)
        }
        return Phrase.of("kami_claims.done.rule")
    }

    private fun jobSet(p: ServerPlayer, job: String, field: String, v: Int): Phrase {
        val c = need(p, Cap.JOBS)
        val def = c.jobs.getOrPut(job) { c.job(job) ?: throw Fail("kami_claims.error.unknown_job") }
        when (field) {
            "pay" -> def.pay = v.coerceIn(0, s.maxJobPay)
            "quota" -> def.quota = max(0, v)
            "period" -> def.period = max(1, v)
            else -> throw Fail("kami_claims.error.job_field")
        }
        return Phrase.of("kami_claims.done.job", Words.job(job), money(def.pay), num(def.quota), days(def.period))
    }

    private fun plotClaim(p: ServerPlayer, k: Key): Phrase {
        permit(p, Cap.PLOT)
        val cl = Realm.index[k] ?: throw Fail("kami_claims.error.plot_none")
        val c = Realm.live(cl.country) ?: throw Fail("kami_claims.error.unknown_country")
        Housing.claim(c, cl, p.stringUUID)
        if (p.stringUUID !in c.members) Mail.officers(c, Phrase.of("kami_claims.mail.plot_rented", v(p.name.string), v("${cl.x}, ${cl.z}")))
        return Phrase.of("kami_claims.done.plot_claimed", Words.rate(Housing.rate(c, cl), 1))
    }

    private fun plotOwner(p: ServerPlayer, k: Key): Claim {
        val cl = Realm.index[k] ?: throw Fail("kami_claims.error.plot_none")
        if (cl.owner == null) throw Fail("kami_claims.error.plot_free")
        if (cl.owner != p.stringUUID && cl.roles[p.stringUUID] != Role.OWNER) throw Fail("kami_claims.error.plot_owner_only")
        return cl
    }

    private fun plotRelease(p: ServerPlayer, k: Key): Phrase {
        val cl = plotOwner(p, k)
        val c = Realm.data.countries.getValue(cl.country)
        val tenant = cl.owner!!
        Housing.release(c, cl)
        if (tenant !in c.members) Mail.officers(c, Phrase.of("kami_claims.mail.plot_returned", v(Names.of(p.server, tenant)), v("${cl.x}, ${cl.z}")))
        return Phrase.of("kami_claims.done.plot_released")
    }

    private fun delegatedRank(c: Country, p: ServerPlayer) = if (c === Realm.of(p.stringUUID)) rankOf(c, p) else DELEGATE_RANK

    private fun plotRemove(p: ServerPlayer, k: Key, confirmed: Boolean): Phrase {
        val c = need(p, Cap.HOUSING)
        val cl = mine(c, k)
        val actor = delegatedRank(c, p)
        val tenant = Housing.checkRemove(c, cl, actor)
        if (!confirmed) throw NeedsConfirm(listOf(
            Phrase.of("kami_claims.confirm.plot_remove.title", v(Names.of(p.server, tenant))),
            Phrase.of("kami_claims.confirm.plot_remove.body", days(c.moveOutDays))
        ))
        Housing.moveOut(c, cl, "removed")
        return Phrase.of("kami_claims.done.plot_removed")
    }

    private fun plotLaw(c: Country, debt: Int, out: Int): Phrase {
        c.rentDebtLimit = max(0, debt).toLong()
        c.moveOutDays = out.coerceIn(1, 30)
        return Phrase.of("kami_claims.done.plot_law", money(c.rentDebtLimit), days(c.moveOutDays))
    }

    private fun plotOffer(p: ServerPlayer, a: List<String>): Phrase {
        val c = need(p, Cap.HOUSING)
        if (arg(a, 0) == "reset") {
            Housing.reset(c, mine(c, spot(p, a, 1)))
            return Phrase.of("kami_claims.done.plot_offer")
        }
        val cat = parse(Claimant.values(), arg(a, 0))
        val on = when (arg(a, 1)) {
            "on" -> true
            "off" -> false
            else -> throw Fail("kami_claims.error.bool")
        }
        val target = if (a.getOrNull(3) == "default") null else mine(c, spot(p, a, 3))
        Housing.edit(c, target, cat, on, num(a, 2).takeIf { it >= 0 })
        return Phrase.of("kami_claims.done.plot_offer")
    }

    private fun plotLimit(p: ServerPlayer, c: Country, target: String, n: Int): Phrase {
        val value = n.takeIf { it >= 0 }?.coerceAtMost(Levels.capacity(c, Capacity.PLOTS))
        val rank = find(Rank.values(), target)?.takeIf { it >= Rank.CITIZEN }
        val group = find(Claimant.values(), target)?.takeIf { it != Claimant.CITIZEN }
        when {
            rank != null -> c.rankPlots[rank] = value ?: s.rankPlots[rank] ?: 0
            group != null -> c.guestPlots[group] = value ?: s.guestPlots[group] ?: 0
            else -> who(p, listOf(target), 0).let { id -> if (value == null) c.playerPlots.remove(id) else c.playerPlots[id] = value }
        }
        return Phrase.of("kami_claims.done.plot_limit")
    }

    private fun country(name: String) = Realm.live(name) ?: throw Fail("kami_claims.error.unknown_country")

    private fun researchAct(p: ServerPlayer, name: String, a: List<String>): Phrase {
        if (name == "research_deposit") {
            val key = Queue.key(home(p), arg(a, 0))
            val moved = Deposit.take(p, key, num(a, 1))
            return Phrase.of("kami_claims.research.done.deposit", num(moved), Queue.node(home(p), key).label().asValue())
        }
        val c = need(p, Cap.RESEARCH)
        val key = Queue.key(c, arg(a, 0))
        when (name) {
            "research_enqueue" -> Queue.enqueue(c, key, p.stringUUID)
            "research_start" -> Queue.start(c, key, p.stringUUID)
            "research_pause" -> Queue.pause(c, key)
            else -> Queue.move(c, key, num(a, 1))
        }
        return Phrase.of("kami_claims.research.done.${name.removePrefix("research_")}", Queue.node(c, key).label().asValue())
    }

    private fun alliance(p: ServerPlayer, step: String, other: Country): Phrase {
        val c = need(p, Cap.TRADE)
        return when (step) {
            "propose" -> Diplomacy.propose(c, other)
            "accept" -> Diplomacy.accept(c, other)
            "decline" -> Diplomacy.decline(c, other)
            "end" -> Diplomacy.end(c, other)
            else -> throw Fail("kami_claims.error.choice", v("propose, accept, decline, end"))
        }
    }

    private fun provinceAccept(p: ServerPlayer, name: String, confirmed: Boolean): Phrase {
        val child = need(p, Cap.PROVINCE)
        val parent = country(name)
        val offer = Provinces.offer(child, parent)
        if (!confirmed) throw NeedsConfirm(Provinces.agreementLines(child, parent, offer))
        Provinces.accept(child, parent)
        return Phrase.of("kami_claims.done.province_joined", v(child.name), v(parent.name))
    }

    private fun provinceGive(p: ServerPlayer, name: String, newParentName: String, confirmed: Boolean): Phrase {
        val parent = need(p, Cap.PROVINCE)
        val child = country(name)
        val newParent = country(newParentName)
        Provinces.checkGive(parent, child, newParent)
        if (!confirmed) throw NeedsConfirm(listOf(
            Phrase.of("kami_claims.confirm.give.title", v(child.name), v(newParent.name)),
            Phrase.of("kami_claims.confirm.give.body", v(newParent.name), Words.tribute(child.taxMode, child.taxAmount), v(child.name))
        ))
        Provinces.give(parent, child, newParent)
        return Phrase.of("kami_claims.done.province_given", v(child.name), v(newParent.name))
    }

    private fun province(p: ServerPlayer, name: String, a: List<String>): Phrase {
        val c = need(p, Cap.PROVINCE)
        val t by lazy { country(arg(a, 0)) }
        val m by lazy { parse(TaxMode.values(), arg(a, 1)) }
        val amount by lazy { Provinces.tribute(arg(a, 2), m) }
        fun done(key: String) = Phrase.of("kami_claims.done.$key", v(t.name))
        return when (name) {
            "province_invite" -> Provinces.invite(c, t, m, amount).let { done("province_invited") }
            "province_request" -> Provinces.request(c, t).let { done("province_requested") }
            "province_approve" -> Provinces.approve(c, t, m, amount).let { done("province_approved") }
            "province_deny" -> Provinces.deny(c, t).let { done("province_denied") }
            "province_release" -> Provinces.release(c, t).let { done("province_released") }
            "province_forgive" -> Provinces.forgive(c, t).let { done("province_forgiven") }
            "province_decline" -> Provinces.decline(c, t).let { done("independence_declined") }
            "province_tax" -> Provinces.setTribute(c, t, m, amount).let { done("tribute_changed") }
            "province_independence" -> Provinces.askIndependence(c).let { Phrase.of("kami_claims.done.independence_requested") }
            "province_withdraw" -> Provinces.withdrawIndependence(c).let { Phrase.of("kami_claims.done.independence_withdrawn") }
            else -> throw Fail("kami_claims.error.unknown_province_action")
        }
    }
}
