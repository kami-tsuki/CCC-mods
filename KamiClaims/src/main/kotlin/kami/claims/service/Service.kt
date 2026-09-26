package kami.claims.service

import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.economy.Treasury
import kami.claims.social.Mail
import kami.claims.social.Perms
import kami.libs.chat.Tone
import kami.libs.chat.every
import kami.libs.chat.plural
import kami.libs.chat.spur
import kami.claims.world.Effects

import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Fail(msg: String, val reason: String = "", val target: Key? = null) : kami.libs.command.CommandFail(msg)

class NeedsConfirm(val lines: List<String>) : RuntimeException(lines.firstOrNull() ?: "Confirmation needed.")

object Service {
    private val s get() = Config.s
    private var acting: String? = null
    val delegableCaps = setOf(Cap.CLAIM, Cap.CAPITAL, Cap.TAX, Cap.RULES, Cap.JOBS)

    fun here(p: ServerPlayer) = Key(p.level().dimension().location().toString(), p.chunkPosition().x, p.chunkPosition().z)
    fun home(p: ServerPlayer) = Realm.of(p.stringUUID) ?: throw Fail("You are not in a country.")
    fun rankOf(c: Country, p: ServerPlayer) = c.members[p.stringUUID]?.rank ?: Rank.CITIZEN

    fun act(p: ServerPlayer, name: String, a: List<String>, asCountry: String = ""): String {
        acting = asCountry.ifBlank { null }
        try {
            return dispatch(p, name, a)
        } finally {
            acting = null
        }
    }

    private fun need(p: ServerPlayer, min: Rank): Country {
        val c = home(p)
        if (rankOf(c, p) < min) throw Fail("Requires rank {${min.name.lowercase()}} or higher.")
        return c
    }

    private fun need(p: ServerPlayer, cap: Cap): Country {
        if (!Perms.has(p, Perms.capNode(cap))) throw Fail("You lack permission for that.")
        val target = acting
        if (cap in delegableCaps && target != null) {
            val own = home(p)
            if (target != own.id) {
                val delegate = Realm.country(target) ?: throw Fail("Unknown country.")
                if (delegate.parent != own.id) throw Fail("You can only act on behalf of your own provinces.")
                if (rankOf(own, p) < Rank.CHANCELLOR) throw Fail("Requires rank chancellor or higher to act on behalf of a province.")
                return delegate
            }
        }
        return need(p, s.min(cap))
    }

    private fun arg(a: List<String>, i: Int) = a.getOrNull(i) ?: throw Fail("Missing argument.")
    private fun num(a: List<String>, i: Int) = arg(a, i).toIntOrNull() ?: throw Fail("Expected a number.")
    private fun <T : Enum<T>> parse(values: Array<T>, text: String): T =
        values.firstOrNull { it.name.equals(text, true) } ?: throw Fail("Pick one of {${values.joinToString { it.name.lowercase() }}}.")

    private fun spot(p: ServerPlayer, a: List<String>, i: Int) =
        if (a.size > i + 1) Key(here(p).dim, num(a, i), num(a, i + 1)) else here(p)

    private fun who(p: ServerPlayer, a: List<String>, i: Int): String {
        val text = arg(a, i)
        return runCatching { UUID.fromString(text).toString() }.getOrNull()
            ?: Names.id(p.server, text)
            ?: throw Fail("Unknown player.")
    }

    private fun confirmed(a: List<String>) = a.lastOrNull() == "confirm"

    private fun member(c: Country, id: String) = c.members[id] ?: throw Fail("Not a member of your country.")

    private fun mine(c: Country, k: Key) = Realm.index[k]?.takeIf { it.country == c.id } ?: throw Fail("That chunk does not belong to your country.")

    fun claimError(c: Country, k: Key, type: String): String? {
        val owned = Realm.claims(c.id)
        return Planner.blockReason(c, k, type, owned.map { it.key }.toHashSet(), c.treasury, owned.size)?.let { "$it." }
    }

    fun addClaim(c: Country, k: Key, type: String, capital: Boolean = false) {
        val free = Realm.claims(c.id).size < Realm.freeAllowed(c)
        if (!free) Treasury.move(c, LedgerKind.CLAIM, -s.types.getValue(type).price.toLong(), note = "${k.x}, ${k.z}")
        Realm.add(Claim(c.id, k.dim, k.x, k.z, type, capital = capital, free = free))
    }

    private fun dispatch(p: ServerPlayer, name: String, a: List<String>): String {
        val text = when (name) {
            "create" -> create(p, arg(a, 0))
            "disband" -> disband(p, confirmed(a))
            "leave" -> leave(p)
            "invite" -> invite(p, who(p, a, 0))
            "accept" -> accept(p, arg(a, 0))
            "join" -> join(p, arg(a, 0))
            "approve" -> approve(p, who(p, a, 0))
            "deny" -> need(p, Cap.INVITE).let { it.requests.remove(who(p, a, 0)); "Request denied." }
            "kick" -> kick(p, who(p, a, 0), false)
            "banish" -> kick(p, who(p, a, 0), true)
            "ally" -> ally(p, who(p, a, 0))
            "clear" -> need(p, Cap.MEMBERS).let { it.outsiders.remove(who(p, a, 0)); "Relation cleared." }
            "rank" -> rank(p, who(p, a, 0), parse(Rank.values(), arg(a, 1)))
            "president" -> president(p, who(p, a, 0), confirmed(a))
            "claim" -> claim(p, arg(a, 0), num(a, 1).coerceIn(0, 8), a.getOrNull(2)?.let { spot(p, a, 2) })
            "unclaim" -> unclaim(p, spot(p, a, 0))
            "type" -> retype(p, arg(a, 0), spot(p, a, 1))
            "capital" -> capital(p, spot(p, a, 0))
            "deposit" -> deposit(p, num(a, 0))
            "withdraw" -> withdraw(p, num(a, 0))
            "tax" -> need(p, Cap.TAX).let { it.tax = max(0, num(a, 0)); "Residential tax is now {${spur(it.tax)}} a day." }
            "plot_tax" -> mine(need(p, Cap.TAX), spot(p, a, 1)).let { it.tax = num(a, 0); "Plot tax updated." }
            "lapse" -> need(p, Cap.TAX).let { it.shutdown = max(0, num(a, 0)); it.release = max(0, num(a, 1)); "Rent timers updated." }
            "rule" -> rule(p, arg(a, 0), arg(a, 1), arg(a, 2))
            "rules" -> arg(a, 0).split(';').filter { it.isNotBlank() }.let { changes ->
                changes.forEach { change -> change.split(':').takeIf { it.size == 3 }?.let { (t, f, v) -> rule(p, t, f, v) } ?: throw Fail("Broken rule change.") }
                "Saved {${plural(changes.size, "rule change")}}."
            }
            "plot_law" -> need(p, Cap.TAX).let { c ->
                c.tax = max(0, num(a, 0)); c.shutdown = max(0, num(a, 1)); c.release = max(0, num(a, 2))
                "Plot law saved: {${spur(c.tax)}} a day, locked after {${c.shutdown}} days, lost {${c.release}} days later."
            }
            "job_set" -> jobSet(p, arg(a, 0), arg(a, 1), num(a, 2))
            "job_edit" -> { jobSet(p, arg(a, 0), "pay", num(a, 1)); jobSet(p, arg(a, 0), "quota", num(a, 2)); jobSet(p, arg(a, 0), "period", num(a, 3)) }
            "job_assign" -> jobAssign(p, who(p, a, 0), arg(a, 1))
            "job_unassign" -> need(p, Cap.JOBS).let { c -> member(c, who(p, a, 0)).let { it.job = null; it.progress = 0; it.zone.clear() }; "Job removed." }
            "zone" -> zone(p, who(p, a, 0), a.getOrNull(1) == "clear")
            "flag" -> need(p, Cap.RULES).let {
                fun hex(i: Int) = arg(a, i).removePrefix("#").toIntOrNull(16)?.and(0xFFFFFF) ?: throw Fail("Use a hex color like {ffffff}.")
                it.color = hex(0)
                it.flag = Flag(num(a, 1).coerceIn(0, 31), num(a, 2).coerceIn(0, 63), hex(3))
                "Colour and flag of {${it.name}} updated."
            }
            "color" -> need(p, Cap.RULES).let { it.color = arg(a, 0).removePrefix("#").toIntOrNull(16)?.and(0xFFFFFF) ?: throw Fail("Use a hex color like {ff8800}."); "Country color updated." }
            "claimrect" -> claimRect(p, arg(a, 0), rect(p, a, 1))
            "claimcells" -> claimRect(p, arg(a, 0), cells(p, a, 1))
            "typecells" -> typeRect(p, arg(a, 0), cells(p, a, 1))
            "unclaimcells" -> unclaimRect(p, cells(p, a, 0))
            "unclaimrect" -> unclaimRect(p, rect(p, a, 0))
            "typerect" -> typeRect(p, arg(a, 0), rect(p, a, 1))
            "plot_claim" -> plotClaim(p, spot(p, a, 0))
            "plot_evict" -> mine(need(p, Cap.CLAIM), spot(p, a, 0)).let { cl -> cl.owner = null; cl.roles.clear(); cl.lapse = 0; "Plot cleared." }
            "plot_release" -> plotOwner(p, spot(p, a, 0)).let { cl -> cl.owner = null; cl.roles.clear(); cl.lapse = 0; "Plot released." }
            "plot_trust" -> plotOwner(p, spot(p, a, 2)).let { cl -> who(p, a, 0).let { id -> if (id == cl.owner) throw Fail("That is the plot owner."); cl.roles[id] = parse(Role.values(), arg(a, 1)) }; "Role set." }
            "plot_untrust" -> plotOwner(p, spot(p, a, 1)).let { it.roles.remove(who(p, a, 0)); "Role removed." }
            "province_accept" -> provinceAccept(p, arg(a, 0), confirmed(a))
            "province_invite", "province_request", "province_approve", "province_deny", "province_release", "province_forgive",
            "province_independence", "province_withdraw", "province_decline", "province_tax" -> province(p, name, a)
            "province_give" -> provinceGive(p, arg(a, 0), arg(a, 1), confirmed(a))
            else -> throw Fail("Unknown action, is your client up to date?")
        }
        Realm.changed()
        return text
    }

    private fun create(p: ServerPlayer, n: String): String {
        if (Realm.of(p.stringUUID) != null) throw Fail("Leave your country first.")
        if (n.length !in s.nameLength[0]..s.nameLength[1] || !n.all { it.isLetterOrDigit() || it == '_' || it == '-' }) throw Fail("Names use {${s.nameLength[0]}}-{${s.nameLength[1]}} letters, digits, _ or -.")
        if (Realm.country(n) != null) throw Fail("Name already taken.")
        val c = Country(n)
        claimError(c, here(p), s.defaultType)?.let { throw Fail(it) }
        Realm.data.countries[c.id] = c
        Realm.join(c, p.stringUUID, Rank.PRESIDENT)
        addClaim(c, here(p), s.defaultType, true)
        Effects.founded(p, c)
        return "{$n} is founded. This chunk is your capital, and {${plural(Realm.freeAllowed(c), "chunk")}} are free. Newly claimed land can only be released after 24h and one upkeep cycle."
    }

    private fun disband(p: ServerPlayer, confirmed: Boolean): String {
        val c = need(p, Rank.PRESIDENT)
        if (!confirmed) throw NeedsConfirm(listOf(
            "Disband {${c.name}}?",
            "All {${plural(Realm.claims(c.id).size, "chunk")}} become nomansland and {${plural(c.members.size, "member")}} lose their country.",
            "The treasury of {${spur(c.treasury)}} is lost. This cannot be undone."
        ))
        Realm.disband(c)
        Effects.chime(p, false)
        return "Country disbanded."
    }

    private fun leave(p: ServerPlayer): String {
        val c = home(p)
        if (rankOf(c, p) == Rank.PRESIDENT && c.members.size > 1) throw Fail("Transfer the presidency first.")
        Realm.leave(c, p.stringUUID)
        if (c.members.isEmpty()) Realm.disband(c)
        return "You left {${c.name}}."
    }

    private fun invite(p: ServerPlayer, id: String): String {
        val c = need(p, Cap.INVITE)
        if (Realm.of(id) != null) throw Fail("Already in a country.")
        if (c.outsiders[id] == Rank.BANISHED) throw Fail("That player is banished.")
        c.invites[id] = now() + s.inviteDays * s.dayMillis
        Effects.invite(p.server, id, c)
        return "Invitation sent."
    }

    private fun accept(p: ServerPlayer, country: String): String {
        val c = Realm.country(country) ?: throw Fail("Unknown country.")
        if (Realm.of(p.stringUUID) != null) throw Fail("Leave your country first.")
        if ((c.invites[p.stringUUID] ?: 0) < now()) throw Fail("No valid invitation.")
        Realm.join(c, p.stringUUID, Rank.CITIZEN)
        Mail.broadcast(c, "{${p.name.string}} joined the country.", Tone.OK)
        return "Welcome to {${c.name}}."
    }

    private fun join(p: ServerPlayer, country: String): String {
        val c = Realm.country(country) ?: throw Fail("Unknown country.")
        if (Realm.of(p.stringUUID) != null) throw Fail("Leave your country first.")
        if (c.outsiders[p.stringUUID] == Rank.BANISHED) throw Fail("You are banished from {${c.name}}.")
        c.requests[p.stringUUID] = now() + s.inviteDays * s.dayMillis
        Mail.officers(c, "{${p.name.string}} wants to join. Answer in the country screen.")
        return "Request sent."
    }

    private fun approve(p: ServerPlayer, id: String): String {
        val c = need(p, Cap.INVITE)
        if (c.requests.remove(id) == null) throw Fail("No request from that player.")
        if (Realm.of(id) != null) throw Fail("Already in a country.")
        Realm.join(c, id, Rank.CITIZEN)
        Mail.broadcast(c, "A new citizen joined.", Tone.OK)
        return "Request approved."
    }

    private fun kick(p: ServerPlayer, id: String, banish: Boolean): String {
        val c = need(p, Cap.MEMBERS)
        c.members[id]?.let {
            if (it.rank >= rankOf(c, p)) throw Fail("You can only remove lower ranks.")
            Realm.leave(c, id)
            Mail.direct(id, "You were ${if (banish) "banished from" else "removed from"} {${c.name}}.", Tone.BAD)
        }
        if (banish) c.outsiders[id] = Rank.BANISHED
        return if (banish) "Banished." else "Removed."
    }

    private fun ally(p: ServerPlayer, id: String): String {
        val c = need(p, Cap.MEMBERS)
        if (c.members.containsKey(id)) throw Fail("Members cannot be allied.")
        c.outsiders[id] = Rank.ALLIED
        return "Allied."
    }

    private fun rank(p: ServerPlayer, id: String, rank: Rank): String {
        val c = need(p, Cap.RANK)
        val m = member(c, id)
        val mine = rankOf(c, p)
        if (rank !in listOf(Rank.CITIZEN, Rank.OFFICER, Rank.CHANCELLOR)) throw Fail("Choose citizen, officer or chancellor.")
        if (m.rank >= mine || rank >= mine) throw Fail("You can only manage ranks below your own.")
        if (rank == Rank.CHANCELLOR && c.members.values.any { it.rank == Rank.CHANCELLOR }) throw Fail("There is already a chancellor.")
        m.rank = rank
        Mail.direct(id, "Your rank is now {${rank.name.lowercase()}}.")
        return "Rank updated."
    }

    private fun president(p: ServerPlayer, id: String, confirmed: Boolean): String {
        val c = need(p, Rank.PRESIDENT)
        val m = member(c, id)
        val mine = c.members.getValue(p.stringUUID)
        if (m === mine) throw Fail("You are already president.")
        if (!confirmed) throw NeedsConfirm(listOf(
            "Make {${Names.of(p.server, id)}} president of {${c.name}}?",
            "They get every right in the country, including disbanding it. You become {${(if (m.rank >= Rank.OFFICER) m.rank else Rank.OFFICER).name.lowercase()}} and can't take it back yourself."
        ))
        mine.rank = if (m.rank >= Rank.OFFICER) m.rank else Rank.OFFICER
        m.rank = Rank.PRESIDENT
        Mail.broadcast(c, "The presidency changed hands.")
        return "Presidency transferred."
    }

    private fun claimCells(c: Country, type: String, cells: List<Key>): Int {
        val plan = Planner.claim(c, type, cells)
        plan.ready.forEach { addClaim(c, it.key, type) }
        return plan.ready.size
    }

    private fun claimReport(c: Country, type: String, count: Int, before: Long): String {
        val def = s.types.getValue(type)
        if (count > 0) return "Claimed {${plural(count, "chunk")}} as {$type} for {${spur(before - c.treasury)}}. Upkeep {${spur(def.price)}} ${every(def.period)} each, treasury {${spur(c.treasury)}}."
        return "Nothing claimed. New chunks must touch your land and the treasury must cover the first day."
    }

    private fun unclaimLockReason(cl: Claim): String? {
        if (now() - cl.at < s.dayMillis) return "This chunk is newly claimed. Wait 24h before releasing it."
        if (cl.upkeepCycles < 1) return "This chunk must complete at least one upkeep cycle before it can be released."
        return null
    }

    private fun ensureUnclaimUnlocked(cl: Claim) {
        val reason = unclaimLockReason(cl) ?: return
        if (!cl.unclaimWarned) {
            cl.unclaimWarned = true
            Realm.changed()
            throw Fail("$reason First release attempt was cancelled intentionally so this rule is visible.")
        }
        throw Fail(reason)
    }

    private fun claim(p: ServerPlayer, type: String, radius: Int, at: Key?): String {
        val c = need(p, Cap.CLAIM)
        s.types[type] ?: throw Fail("Unknown chunk type.")
        val origin = at ?: here(p)
        val cells = (-radius..radius).flatMap { dx -> (-radius..radius).map { dz -> Key(origin.dim, origin.x + dx, origin.z + dz) } }
            .sortedBy { max(abs(it.x - origin.x), abs(it.z - origin.z)) }
        if (radius == 0) claimError(c, origin, type)?.let { throw Fail(it) }
        val before = c.treasury
        val beforeClaims = Realm.claims(c.id).size
        val count = claimCells(c, type, cells)
        if (count > 0) Effects.chime(p, true)
        val report = claimReport(c, type, count, before)
        return if (count > 0 && beforeClaims == 0) "$report Warning: newly claimed land can only be released after 24h and one upkeep cycle." else report
    }

    private class Rect(val cells: List<Key>)

    private fun rect(p: ServerPlayer, a: List<String>, i: Int): Rect {
        val dim = here(p).dim
        val x1 = min(num(a, i), num(a, i + 2))
        val x2 = max(num(a, i), num(a, i + 2))
        val z1 = min(num(a, i + 1), num(a, i + 3))
        val z2 = max(num(a, i + 1), num(a, i + 3))
        if ((x2 - x1 + 1).toLong() * (z2 - z1 + 1) > s.maxRect) throw Fail("That selection is too big, the limit is {${plural(s.maxRect, "chunk")}}.")
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
        if (keys.size > s.maxRect) throw Fail("That selection is too big, the limit is {${plural(s.maxRect, "chunk")}}.")
        return Rect(keys)
    }

    private fun claimRect(p: ServerPlayer, type: String, r: Rect): String {
        val c = need(p, Cap.CLAIM)
        s.types[type] ?: throw Fail("Unknown chunk type.")
        val before = c.treasury
        val beforeClaims = Realm.claims(c.id).size
        val count = claimCells(c, type, r.cells)
        if (count > 0) Effects.chime(p, true)
        val report = claimReport(c, type, count, before)
        return if (count > 0 && beforeClaims == 0) "$report Warning: newly claimed land can only be released after 24h and one upkeep cycle." else report
    }

    private fun unclaimRect(p: ServerPlayer, r: Rect): String {
        val c = need(p, Cap.CLAIM)
        val plan = Planner.unclaim(c, r.cells)
        plan.ready.forEach { cell -> Realm.index[cell.key]?.let { Realm.unclaim(it, false) } }
        val blocked = plan.blocked
        if (plan.ready.isEmpty() && blocked.isNotEmpty()) throw Fail("Nothing released: ${blocked.first().reason}.", "BLOCKED", blocked.first().key)
        val kept = if (blocked.isEmpty()) "" else " {${plural(blocked.size, "chunk")}} kept: ${blocked.first().reason}."
        return "Released {${plural(plan.ready.size, "chunk")}}.$kept"
    }

    private fun typeRect(p: ServerPlayer, type: String, r: Rect): String {
        val c = need(p, Cap.CLAIM)
        if (s.types[type] == null) throw Fail("Unknown type.")
        val set = r.cells.toHashSet()
        val hit = Realm.claims(c.id).filter { it.key in set }
        hit.forEach { applyType(it, type) }
        return "{${plural(hit.size, "chunk")}} changed to {$type}."
    }

    private fun applyType(cl: Claim, type: String) {
        if (cl.type == "residential" && type != "residential") { cl.owner = null; cl.roles.clear(); cl.lapse = 0 }
        cl.type = type
    }

    private fun unclaim(p: ServerPlayer, k: Key): String {
        val cl = mine(need(p, Cap.CLAIM), k)
        if (cl.capital) throw Fail("Move the capital first.")
        ensureUnclaimUnlocked(cl)
        if (!Realm.removable(cl)) throw Fail("Unclaiming would split your territory.")
        Realm.unclaim(cl, false)
        return "Chunk released."
    }

    private fun retype(p: ServerPlayer, type: String, k: Key): String {
        val cl = mine(need(p, Cap.CLAIM), k)
        if (s.types[type] == null) throw Fail("Unknown type.")
        applyType(cl, type)
        return "Chunk is now {$type}, {${spur(Realm.price(cl))}} ${every(Realm.period(cl))}."
    }

    private fun capital(p: ServerPlayer, k: Key): String {
        val c = need(p, Cap.CAPITAL)
        val cl = mine(c, k)
        if (cl.capital) throw Fail("Already the capital.")
        if (now() - c.moved < s.capitalCooldownDays * s.dayMillis) throw Fail("The capital was moved recently.")
        Realm.claims(c.id).forEach { it.capital = false }
        cl.capital = true
        c.moved = now()
        return "Capital moved."
    }

    private fun deposit(p: ServerPlayer, n: Int): String {
        val c = home(p)
        if (n < 1) throw Fail("Amount must be positive.", "AMOUNT")
        if (!Bank.take(p.uuid, n)) throw Fail("You don't have {${spur(n)}}.", "FUNDS")
        Treasury.move(c, LedgerKind.DEPOSIT, n.toLong(), p.stringUUID)
        c.pending += n
        return "Deposited {${spur(n)}}, treasury {${spur(c.treasury)}}."
    }

    private fun withdraw(p: ServerPlayer, n: Int): String {
        val c = need(p, Cap.WITHDRAW)
        if (n < 1) throw Fail("Amount must be positive.", "AMOUNT")
        if (c.treasury < n) throw Fail("The treasury only holds {${spur(c.treasury)}}.", "FUNDS")
        if (!Bank.give(p.uuid, n)) throw Fail("Could not pay out.")
        Treasury.move(c, LedgerKind.WITHDRAW, -n.toLong(), p.stringUUID)
        return "Withdrew {${spur(n)}}."
    }

    private fun rule(p: ServerPlayer, type: String, field: String, value: String): String {
        val c = need(p, Cap.RULES)
        if (s.types[type] == null) throw Fail("Unknown type.")
        val flag = { value.toBooleanStrictOrNull() ?: throw Fail("Use true or false.") }
        when (field) {
            "machines" -> c.machines[type] = flag()
            "fire" -> c.fire[type] = flag()
            "fluid" -> c.fluid[type] = flag()
            else -> c.rules.getOrPut(type) { mutableMapOf() }[parse(Action.values(), field)] = parse(Access.values(), value)
        }
        return "Rule updated."
    }

    private fun jobSet(p: ServerPlayer, job: String, field: String, v: Int): String {
        val c = need(p, Cap.JOBS)
        val def = c.jobs.getOrPut(job) { c.job(job) ?: throw Fail("Unknown job.") }
        when (field) {
            "pay" -> def.pay = v.coerceIn(0, s.maxJobPay)
            "quota" -> def.quota = max(0, v)
            "period" -> def.period = max(1, v)
            else -> throw Fail("Fields: pay, quota, period.")
        }
        return "{$job} pays {${spur(def.pay)}} for {${def.quota}} actions ${every(def.period)}."
    }

    private fun jobAssign(p: ServerPlayer, id: String, job: String): String {
        val m = member(need(p, Cap.JOBS), id)
        if (job !in s.jobs) throw Fail("Unknown job.")
        m.job = job
        m.progress = 0
        m.start = today()
        m.zone.clear()
        Mail.direct(id, "You work as {$job} now.")
        return "Assigned {$job}."
    }

    private fun zone(p: ServerPlayer, id: String, clear: Boolean): String {
        val c = need(p, Cap.JOBS)
        val m = member(c, id)
        if (clear) { m.zone.clear(); return "Zone cleared." }
        val cl = mine(c, here(p))
        if (s.jobs[m.job]?.type != cl.type) throw Fail("This chunk type does not match the worker's job.")
        m.zone += cl.key.toString()
        return "Chunk added to the worker's zone."
    }

    private fun plotClaim(p: ServerPlayer, k: Key): String {
        val c = need(p, Cap.PLOT)
        val cl = mine(c, k)
        if (cl.type != "residential") throw Fail("Only residential chunks can be claimed as plots.")
        if (cl.owner != null) throw Fail("Plot already owned.")
        if (Realm.claims(c.id).count { it.owner == p.stringUUID } >= s.maxPlots) throw Fail("You already own {${s.maxPlots}} plots, that is the limit.")
        cl.owner = p.stringUUID
        cl.lapse = 0
        cl.roles.clear()
        return "Plot claimed, tax {${spur(if (cl.tax >= 0) cl.tax else c.tax)}} a day."
    }

    private fun plotOwner(p: ServerPlayer, k: Key): Claim {
        val cl = mine(home(p), k)
        if (cl.owner == null) throw Fail("Nobody owns this plot.")
        if (cl.owner != p.stringUUID && cl.roles[p.stringUUID] != Role.OWNER) throw Fail("Only plot owners can do that.")
        return cl
    }

    private fun country(name: String) = Realm.country(name) ?: throw Fail("Unknown country.")

    private fun provinceAccept(p: ServerPlayer, name: String, confirmed: Boolean): String {
        val child = need(p, Cap.PROVINCE)
        val parent = country(name)
        val offer = Provinces.offer(child, parent)
        if (!confirmed) throw NeedsConfirm(Provinces.agreementLines(child, parent, offer))
        Provinces.accept(child, parent)
        return "{${child.name}} is now a province of {${parent.name}}."
    }

    private fun provinceGive(p: ServerPlayer, name: String, newParentName: String, confirmed: Boolean): String {
        val parent = need(p, Cap.PROVINCE)
        val child = country(name)
        val newParent = country(newParentName)
        Provinces.checkGive(parent, child, newParent)
        if (!confirmed) throw NeedsConfirm(listOf(
            "Give {${child.name}} to {${newParent.name}}?",
            "{${newParent.name}} takes over the tribute of {${Provinces.tributeText(child.taxMode, child.taxAmount)}} and the right to manage {${child.name}}. You can't take it back."
        ))
        Provinces.give(parent, child, newParent)
        return "{${child.name}} now belongs to {${newParent.name}}."
    }

    private fun province(p: ServerPlayer, name: String, a: List<String>): String {
        val c = need(p, Cap.PROVINCE)
        fun mode() = parse(TaxMode.values(), arg(a, 1))
        return when (name) {
            else -> throw Fail("Unknown province action.")
        }
    }
}
