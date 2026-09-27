package kami.claims.service

import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.economy.Treasury
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

class NeedsConfirm(val lines: List<Phrase>) : RuntimeException()

object Service {
    private val s get() = Config.s
    private var acting: String? = null
    val delegableCaps = setOf(Cap.CLAIM, Cap.CAPITAL, Cap.TAX, Cap.RULES, Cap.JOBS)

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

    private fun need(p: ServerPlayer, min: Rank): Country {
        val c = home(p)
        if (rankOf(c, p) < min) throw Fail("kami_claims.error.rank", Words.rank(min))
        return c
    }

    private fun need(p: ServerPlayer, cap: Cap): Country {
        if (!Perms.has(p, Perms.capNode(cap))) throw Fail("kami_claims.error.permission")
        val target = acting
        if (cap in delegableCaps && target != null) {
            val own = home(p)
            if (target != own.id) {
                val delegate = Realm.country(target) ?: throw Fail("kami_claims.error.unknown_country")
                if (delegate.parent != own.id) throw Fail("kami_claims.error.not_your_province")
                if (rankOf(own, p) < Rank.CHANCELLOR) throw Fail("kami_claims.error.delegate_rank")
                return delegate
            }
        }
        return need(p, s.min(cap))
    }

    private fun arg(a: List<String>, i: Int) = a.getOrNull(i) ?: throw Fail("kami_claims.error.missing_argument")
    private fun num(a: List<String>, i: Int) = arg(a, i).toIntOrNull() ?: throw Fail("kami_claims.error.number")
    private fun <T : Enum<T>> parse(values: Array<T>, text: String): T =
        values.firstOrNull { it.name.equals(text, true) } ?: throw Fail("kami_claims.error.choice", v(values.joinToString { it.name.lowercase() }))

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

    private fun mine(c: Country, k: Key) = Realm.index[k]?.takeIf { it.country == c.id } ?: throw Fail("kami_claims.error.not_your_chunk")

    fun claimError(c: Country, k: Key, type: String): Phrase? {
        val owned = Realm.claims(c.id)
        return Planner.blockReason(c, k, type, owned.map { it.key }.toHashSet(), c.treasury, owned.size)
    }

    fun addClaim(c: Country, k: Key, type: String, capital: Boolean = false) {
        val free = Realm.claims(c.id).size < Realm.freeAllowed(c)
        if (!free) Treasury.move(c, LedgerKind.CLAIM, -s.types.getValue(type).price.toLong(), note = "${k.x}, ${k.z}")
        Realm.add(Claim(c.id, k.dim, k.x, k.z, type, capital = capital, free = free))
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
            "deposit" -> deposit(p, num(a, 0))
            "withdraw" -> withdraw(p, num(a, 0))
            "tax" -> need(p, Cap.TAX).let { it.tax = max(0, num(a, 0)); Phrase.of("kami_claims.done.tax", Words.rate(it.tax, 1)) }
            "plot_tax" -> mine(need(p, Cap.TAX), spot(p, a, 1)).let { it.tax = num(a, 0); Phrase.of("kami_claims.done.plot_tax") }
            "lapse" -> need(p, Cap.TAX).let { it.shutdown = max(0, num(a, 0)); it.release = max(0, num(a, 1)); Phrase.of("kami_claims.done.lapse") }
            "rule" -> rule(p, arg(a, 0), arg(a, 1), arg(a, 2))
            "rules" -> arg(a, 0).split(';').filter { it.isNotBlank() }.let { changes ->
                changes.forEach { change -> change.split(':').takeIf { it.size == 3 }?.let { (t, f, v) -> rule(p, t, f, v) } ?: throw Fail("kami_claims.error.rule_format") }
                Phrase.of("kami_claims.done.rules", count("kami_claims.unit.change", changes.size))
            }
            "plot_law" -> need(p, Cap.TAX).let { c ->
                c.tax = max(0, num(a, 0)); c.shutdown = max(0, num(a, 1)); c.release = max(0, num(a, 2))
                Phrase.of("kami_claims.done.plot_law", Words.rate(c.tax, 1), days(c.shutdown), days(c.release))
            }
            "job_set" -> jobSet(p, arg(a, 0), arg(a, 1), num(a, 2))
            "job_edit" -> { jobSet(p, arg(a, 0), "pay", num(a, 1)); jobSet(p, arg(a, 0), "quota", num(a, 2)); jobSet(p, arg(a, 0), "period", num(a, 3)) }
            "job_assign" -> jobAssign(p, who(p, a, 0), arg(a, 1))
            "job_unassign" -> need(p, Cap.JOBS).let { c -> member(c, who(p, a, 0)).let { it.job = null; it.progress = 0; it.zone.clear() }; Phrase.of("kami_claims.done.job_removed") }
            "zone" -> zone(p, who(p, a, 0), a.getOrNull(1) == "clear")
            "flag" -> need(p, Cap.RULES).let {
                fun hex(i: Int) = arg(a, i).removePrefix("#").toIntOrNull(16)?.and(0xFFFFFF) ?: throw Fail("kami_claims.error.hex", v("ffffff"))
                it.color = hex(0)
                it.flag = Flag(num(a, 1).coerceIn(0, 31), num(a, 2).coerceIn(0, 63), hex(3))
                Phrase.of("kami_claims.done.flag", v(it.name))
            }
            "color" -> need(p, Cap.RULES).let {
                it.color = arg(a, 0).removePrefix("#").toIntOrNull(16)?.and(0xFFFFFF) ?: throw Fail("kami_claims.error.hex", v("ff8800"))
                Phrase.of("kami_claims.done.color")
            }
            "claimrect" -> claimRect(p, arg(a, 0), rect(p, a, 1))
            "claimcells" -> claimRect(p, arg(a, 0), cells(p, a, 1))
            "typecells" -> typeRect(p, arg(a, 0), cells(p, a, 1))
            "unclaimcells" -> unclaimRect(p, cells(p, a, 0))
            "unclaimrect" -> unclaimRect(p, rect(p, a, 0))
            "typerect" -> typeRect(p, arg(a, 0), rect(p, a, 1))
            "plot_claim" -> plotClaim(p, spot(p, a, 0))
            "plot_evict" -> mine(need(p, Cap.CLAIM), spot(p, a, 0)).let { cl -> cl.owner = null; cl.roles.clear(); cl.lapse = 0; Phrase.of("kami_claims.done.plot_evicted") }
            "plot_release" -> plotOwner(p, spot(p, a, 0)).let { cl -> cl.owner = null; cl.roles.clear(); cl.lapse = 0; Phrase.of("kami_claims.done.plot_released") }
            "plot_trust" -> plotOwner(p, spot(p, a, 2)).let { cl ->
                who(p, a, 0).let { id -> if (id == cl.owner) throw Fail("kami_claims.error.plot_owner_self"); cl.roles[id] = parse(Role.values(), arg(a, 1)) }
                Phrase.of("kami_claims.done.role_set")
            }
            "plot_untrust" -> plotOwner(p, spot(p, a, 1)).let { it.roles.remove(who(p, a, 0)); Phrase.of("kami_claims.done.role_removed") }
            "province_accept" -> provinceAccept(p, arg(a, 0), confirmed(a))
            "province_invite", "province_request", "province_approve", "province_deny", "province_release", "province_forgive",
            "province_independence", "province_withdraw", "province_decline", "province_tax" -> province(p, name, a)
            "province_give" -> provinceGive(p, arg(a, 0), arg(a, 1), confirmed(a))
            else -> throw Fail("kami_claims.error.unknown_action")
        }
        Realm.changed()
        return text
    }

    private fun create(p: ServerPlayer, n: String): Phrase {
        if (Realm.of(p.stringUUID) != null) throw Fail("kami_claims.error.leave_first")
        if (n.length !in s.nameLength[0]..s.nameLength[1] || !n.all { it.isLetterOrDigit() || it == '_' || it == '-' }) throw Fail("kami_claims.error.name", num(s.nameLength[0]), num(s.nameLength[1]))
        if (Realm.country(n) != null) throw Fail("kami_claims.error.name_taken")
        val c = Country(n)
        claimError(c, here(p), s.defaultType)?.let { throw Fail(it) }
        Realm.data.countries[c.id] = c
        Realm.join(c, p.stringUUID, Rank.PRESIDENT)
        addClaim(c, here(p), s.defaultType, true)
        Effects.founded(p, c)
        return Phrase.of("kami_libs.common.join", Phrase.of("kami_claims.done.founded", v(n), chunks(Realm.freeAllowed(c))), Phrase.of("kami_claims.notice.release_lock"))
    }

    private fun disband(p: ServerPlayer, confirmed: Boolean): Phrase {
        val c = need(p, Rank.PRESIDENT)
        if (!confirmed) throw NeedsConfirm(listOf(
            Phrase.of("kami_claims.confirm.disband.title", v(c.name)),
            Phrase.of("kami_claims.confirm.disband.land", chunks(Realm.claims(c.id).size)),
            Phrase.of("kami_claims.confirm.disband.members", count("kami_claims.unit.member", c.members.size)),
            Phrase.of("kami_claims.confirm.disband.treasury", money(c.treasury))
        ))
        Realm.disband(c)
        Effects.chime(p, false)
        return Phrase.of("kami_claims.done.disbanded")
    }

    private fun leave(p: ServerPlayer): Phrase {
        val c = home(p)
        if (rankOf(c, p) == Rank.PRESIDENT && c.members.size > 1) throw Fail("kami_claims.error.transfer_first")
        Realm.leave(c, p.stringUUID)
        if (c.members.isEmpty()) Realm.disband(c)
        return Phrase.of("kami_claims.done.left", v(c.name))
    }

    private fun invite(p: ServerPlayer, id: String): Phrase {
        val c = need(p, Cap.INVITE)
        if (Realm.of(id) != null) throw Fail("kami_claims.error.already_member")
        if (c.outsiders[id] == Rank.BANISHED) throw Fail("kami_claims.error.target_banished")
        c.invites[id] = now() + s.inviteDays * s.dayMillis
        Effects.invite(p.server, id, c)
        return Phrase.of("kami_claims.done.invited")
    }

    private fun accept(p: ServerPlayer, country: String): Phrase {
        val c = Realm.country(country) ?: throw Fail("kami_claims.error.unknown_country")
        if (Realm.of(p.stringUUID) != null) throw Fail("kami_claims.error.leave_first")
        if ((c.invites[p.stringUUID] ?: 0) < now()) throw Fail("kami_claims.error.no_invite")
        Realm.join(c, p.stringUUID, Rank.CITIZEN)
        Mail.broadcast(c, Phrase.of("kami_claims.mail.joined", v(p.name.string)), Tone.OK)
        return Phrase.of("kami_claims.done.welcome", v(c.name))
    }

    private fun join(p: ServerPlayer, country: String): Phrase {
        val c = Realm.country(country) ?: throw Fail("kami_claims.error.unknown_country")
        if (Realm.of(p.stringUUID) != null) throw Fail("kami_claims.error.leave_first")
        if (c.outsiders[p.stringUUID] == Rank.BANISHED) throw Fail("kami_claims.error.you_banished", v(c.name))
        c.requests[p.stringUUID] = now() + s.inviteDays * s.dayMillis
        Mail.officers(c, Phrase.of("kami_claims.mail.join_request", v(p.name.string)))
        return Phrase.of("kami_claims.done.request_sent")
    }

    private fun approve(p: ServerPlayer, id: String): Phrase {
        val c = need(p, Cap.INVITE)
        if (c.requests.remove(id) == null) throw Fail("kami_claims.error.no_request")
        if (Realm.of(id) != null) throw Fail("kami_claims.error.already_member")
        Realm.join(c, id, Rank.CITIZEN)
        Mail.broadcast(c, Phrase.of("kami_claims.mail.joined", v(Names.of(p.server, id))), Tone.OK)
        return Phrase.of("kami_claims.done.request_approved")
    }

    private fun kick(p: ServerPlayer, id: String, banish: Boolean): Phrase {
        val c = need(p, Cap.MEMBERS)
        c.members[id]?.let {
            if (it.rank >= rankOf(c, p)) throw Fail("kami_claims.error.lower_ranks")
            Realm.leave(c, id)
            Mail.direct(id, Phrase.of(if (banish) "kami_claims.mail.banished" else "kami_claims.mail.removed", v(c.name)), Tone.BAD)
        }
        if (banish) c.outsiders[id] = Rank.BANISHED
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
        m.rank = rank
        Mail.direct(id, Phrase.of("kami_claims.mail.rank", Words.rank(rank)))
        return Phrase.of("kami_claims.done.rank")
    }

    private fun president(p: ServerPlayer, id: String, confirmed: Boolean): Phrase {
        val c = need(p, Rank.PRESIDENT)
        val m = member(c, id)
        val mine = c.members.getValue(p.stringUUID)
        if (m === mine) throw Fail("kami_claims.error.already_president")
        val stepDown = if (m.rank >= Rank.OFFICER) m.rank else Rank.OFFICER
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
        return if (first) Phrase.of("kami_libs.common.join", report, Phrase.of("kami_claims.notice.release_lock")) else report
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
        hit.forEach { applyType(it, type) }
        return Phrase.of("kami_claims.done.retyped", chunks(hit.size), Words.type(type))
    }

    private fun applyType(cl: Claim, type: String) {
        if (cl.type == "residential" && type != "residential") { cl.owner = null; cl.roles.clear(); cl.lapse = 0 }
        cl.type = type
    }

    private fun unclaim(p: ServerPlayer, k: Key): Phrase {
        val cl = mine(need(p, Cap.CLAIM), k)
        if (cl.capital) throw Fail("kami_claims.error.capital")
        ensureUnclaimUnlocked(cl)
        if (!Realm.removable(cl)) throw Fail("kami_claims.error.split")
        Realm.unclaim(cl, false)
        return Phrase.of("kami_claims.done.chunk_released")
    }

    private fun retype(p: ServerPlayer, type: String, k: Key): Phrase {
        val cl = mine(need(p, Cap.CLAIM), k)
        if (s.types[type] == null) throw Fail("kami_claims.error.unknown_type")
        applyType(cl, type)
        return Phrase.of("kami_claims.done.chunk_retyped", Words.type(type), Words.rate(Realm.price(cl), Realm.period(cl)))
    }

    private fun capital(p: ServerPlayer, k: Key): Phrase {
        val c = need(p, Cap.CAPITAL)
        val cl = mine(c, k)
        if (cl.capital) throw Fail("kami_claims.error.already_capital")
        if (now() - c.moved < s.capitalCooldownDays * s.dayMillis) throw Fail("kami_claims.error.capital_cooldown")
        Realm.claims(c.id).forEach { it.capital = false }
        cl.capital = true
        c.moved = now()
        return Phrase.of("kami_claims.done.capital")
    }

    private fun deposit(p: ServerPlayer, n: Int): Phrase {
        val c = home(p)
        if (n < 1) throw Fail(Phrase.of("kami_claims.error.amount"), "AMOUNT")
        if (!Bank.take(p.uuid, n)) throw Fail(Phrase.of("kami_claims.error.funds", money(n)), "FUNDS")
        Treasury.move(c, LedgerKind.DEPOSIT, n.toLong(), p.stringUUID)
        c.pending += n
        return Phrase.of("kami_claims.done.deposited", money(n), money(c.treasury))
    }

    private fun withdraw(p: ServerPlayer, n: Int): Phrase {
        val c = need(p, Cap.WITHDRAW)
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
            "fluid" -> c.fluid[type] = flag()
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

    private fun jobAssign(p: ServerPlayer, id: String, job: String): Phrase {
        val m = member(need(p, Cap.JOBS), id)
        if (job !in s.jobs) throw Fail("kami_claims.error.unknown_job")
        m.job = job
        m.progress = 0
        m.start = today()
        m.zone.clear()
        Mail.direct(id, Phrase.of("kami_claims.mail.job", Words.job(job)))
        return Phrase.of("kami_claims.done.job_assigned", Words.job(job))
    }

    private fun zone(p: ServerPlayer, id: String, clear: Boolean): Phrase {
        val c = need(p, Cap.JOBS)
        val m = member(c, id)
        if (clear) { m.zone.clear(); return Phrase.of("kami_claims.done.zone_cleared") }
        val cl = mine(c, here(p))
        if (s.jobs[m.job]?.type != cl.type) throw Fail("kami_claims.error.zone_type")
        m.zone += cl.key.toString()
        return Phrase.of("kami_claims.done.zone_added")
    }

    private fun plotClaim(p: ServerPlayer, k: Key): Phrase {
        val c = need(p, Cap.PLOT)
        val cl = mine(c, k)
        if (cl.type != "residential") throw Fail("kami_claims.error.plot_type")
        if (cl.owner != null) throw Fail("kami_claims.error.plot_taken")
        if (Realm.claims(c.id).count { it.owner == p.stringUUID } >= s.maxPlots) throw Fail("kami_claims.error.plot_limit", count("kami_claims.unit.plot", s.maxPlots))
        cl.owner = p.stringUUID
        cl.lapse = 0
        cl.roles.clear()
        return Phrase.of("kami_claims.done.plot_claimed", Words.rate(if (cl.tax >= 0) cl.tax else c.tax, 1))
    }

    private fun plotOwner(p: ServerPlayer, k: Key): Claim {
        val cl = mine(home(p), k)
        if (cl.owner == null) throw Fail("kami_claims.error.plot_free")
        if (cl.owner != p.stringUUID && cl.roles[p.stringUUID] != Role.OWNER) throw Fail("kami_claims.error.plot_owner_only")
        return cl
    }

    private fun country(name: String) = Realm.country(name) ?: throw Fail("kami_claims.error.unknown_country")

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
        fun mode() = parse(TaxMode.values(), arg(a, 1))
        return when (name) {
            else -> throw Fail("kami_claims.error.unknown_province_action")
        }
    }
}
