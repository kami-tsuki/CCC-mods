package kami.claims.service

import kami.claims.*
import kami.claims.economy.Bank
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

class Fail(msg: String) : kami.libs.command.CommandFail(msg)

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

    private fun member(c: Country, id: String) = c.members[id] ?: throw Fail("Not a member of your country.")

    private fun mine(c: Country, k: Key) = Realm.index[k]?.takeIf { it.country == c.id } ?: throw Fail("That chunk does not belong to your country.")

    fun claimError(c: Country, k: Key, type: String): String? {
        if (k.dim !in s.dimensions) return "You can't claim land in this dimension."
        val def = s.types[type] ?: return "Unknown chunk type."
        if (Realm.index[k] != null) return "This chunk is already claimed."
        Realm.reservedFor(k.dim, k.x, k.z)?.let { if (it != c.id) return "This chunk is reserved for another country." }
        val touches = listOf(Key(k.dim, k.x + 1, k.z), Key(k.dim, k.x - 1, k.z), Key(k.dim, k.x, k.z + 1), Key(k.dim, k.x, k.z - 1)).any { Realm.index[it]?.country == c.id }
        if (Realm.claims(c.id).isNotEmpty() && !touches) return "New chunks must connect to your land."
        if (Realm.claims(c.id).size >= Realm.freeAllowed(c) && c.treasury < def.price) return "The treasury cannot pay the first day, {${spur(def.price)}}."
        return null
    }

    fun addClaim(c: Country, k: Key, type: String, capital: Boolean = false) {
        val free = Realm.claims(c.id).size < Realm.freeAllowed(c)
        if (!free) c.treasury -= s.types.getValue(type).price
        Realm.add(Claim(c.id, k.dim, k.x, k.z, type, capital = capital, free = free))
    }

    private fun dispatch(p: ServerPlayer, name: String, a: List<String>): String {
        val text = when (name) {
            "create" -> create(p, arg(a, 0))
            "disband" -> need(p, Rank.PRESIDENT).let { Realm.disband(it); Effects.chime(p, false); "Country disbanded." }
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
            "president" -> president(p, who(p, a, 0))
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
            "job_set" -> jobSet(p, arg(a, 0), arg(a, 1), num(a, 2))
            "job_assign" -> jobAssign(p, who(p, a, 0), arg(a, 1))
            "job_unassign" -> need(p, Cap.JOBS).let { c -> member(c, who(p, a, 0)).let { it.job = null; it.progress = 0; it.zone.clear() }; "Job removed." }
            "zone" -> zone(p, who(p, a, 0), a.getOrNull(1) == "clear")
            "color" -> need(p, Cap.RULES).let { it.color = arg(a, 0).removePrefix("#").toIntOrNull(16)?.and(0xFFFFFF) ?: throw Fail("Use a hex color like {ff8800}."); "Country color updated." }
            "claimrect" -> claimRect(p, arg(a, 0), rect(p, a, 1))
            "unclaimrect" -> unclaimRect(p, rect(p, a, 0))
            "typerect" -> typeRect(p, arg(a, 0), rect(p, a, 1))
            "plot_claim" -> plotClaim(p, spot(p, a, 0))
            "plot_evict" -> mine(need(p, Cap.CLAIM), spot(p, a, 0)).let { cl -> cl.owner = null; cl.roles.clear(); cl.lapse = 0; "Plot cleared." }
            "plot_release" -> plotOwner(p, spot(p, a, 0)).let { cl -> cl.owner = null; cl.roles.clear(); cl.lapse = 0; "Plot released." }
            "plot_trust" -> plotOwner(p, spot(p, a, 2)).let { cl -> who(p, a, 0).let { id -> if (id == cl.owner) throw Fail("That is the plot owner."); cl.roles[id] = parse(Role.values(), arg(a, 1)) }; "Role set." }
            "plot_untrust" -> plotOwner(p, spot(p, a, 1)).let { it.roles.remove(who(p, a, 0)); "Role removed." }
            "province_invite" -> provinceInvite(p, arg(a, 0), parse(TaxMode.values(), arg(a, 1)), arg(a, 2))
            "province_request" -> provinceRequest(p, arg(a, 0))
            "province_accept" -> provinceAccept(p, arg(a, 0))
            "province_approve" -> provinceApprove(p, arg(a, 0), parse(TaxMode.values(), arg(a, 1)), arg(a, 2))
            "province_deny" -> provinceDeny(p, arg(a, 0))
            "province_release" -> provinceRelease(p, arg(a, 0))
            "province_forgive" -> provinceForgive(p, arg(a, 0))
            "province_independence" -> provinceIndependence(p)
            "province_tax" -> provinceTax(p, arg(a, 0), parse(TaxMode.values(), arg(a, 1)), arg(a, 2))
            "province_give" -> provinceGive(p, arg(a, 0), arg(a, 1))
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
        return "{$n} is founded. This chunk is your capital, and {${plural(Realm.freeAllowed(c), "chunk")}} are free."
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

    private fun president(p: ServerPlayer, id: String): String {
        val c = need(p, Rank.PRESIDENT)
        val m = member(c, id)
        val mine = c.members.getValue(p.stringUUID)
        if (m === mine) throw Fail("You are already president.")
        mine.rank = if (m.rank >= Rank.OFFICER) m.rank else Rank.OFFICER
        m.rank = Rank.PRESIDENT
        Mail.broadcast(c, "The presidency changed hands.")
        return "Presidency transferred."
    }

    private fun claimCells(c: Country, type: String, cells: List<Key>): Int {
        var count = 0
        for (pass in 0..min(cells.size, 64)) {
            var progress = false
            cells.forEach { if (Realm.index[it] == null && claimError(c, it, type) == null) { addClaim(c, it, type); count++; progress = true } }
            if (!progress) break
        }
        return count
    }

    private fun claimReport(c: Country, type: String, count: Int, before: Long): String {
        val def = s.types.getValue(type)
        if (count > 0) return "Claimed {${plural(count, "chunk")}} as {$type} for {${spur(before - c.treasury)}}. Upkeep {${spur(def.price)}} ${every(def.period)} each, treasury {${spur(c.treasury)}}."
        return "Nothing claimed. New chunks must touch your land and the treasury must cover the first day."
    }

    private fun claim(p: ServerPlayer, type: String, radius: Int, at: Key?): String {
        val c = need(p, Cap.CLAIM)
        s.types[type] ?: throw Fail("Unknown chunk type.")
        val origin = at ?: here(p)
        val cells = (-radius..radius).flatMap { dx -> (-radius..radius).map { dz -> Key(origin.dim, origin.x + dx, origin.z + dz) } }
            .sortedBy { max(abs(it.x - origin.x), abs(it.z - origin.z)) }
        if (radius == 0) claimError(c, origin, type)?.let { throw Fail(it) }
        val before = c.treasury
        val count = claimCells(c, type, cells)
        if (count > 0) Effects.chime(p, true)
        return claimReport(c, type, count, before)
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

    private fun claimRect(p: ServerPlayer, type: String, r: Rect): String {
        val c = need(p, Cap.CLAIM)
        s.types[type] ?: throw Fail("Unknown chunk type.")
        val before = c.treasury
        val count = claimCells(c, type, r.cells)
        if (count > 0) Effects.chime(p, true)
        return claimReport(c, type, count, before)
    }

    private fun unclaimRect(p: ServerPlayer, r: Rect): String {
        val c = need(p, Cap.CLAIM)
        val set = r.cells.toHashSet()
        var count = 0
        for (pass in 0 until 6) {
            val before = count
            Realm.claims(c.id).filter { it.key in set && !it.capital }.sortedByDescending { it.at }.forEach {
                if (Realm.removable(it)) { Realm.unclaim(it, false); count++ }
            }
            if (count == before) break
        }
        return "Released {${plural(count, "chunk")}}."
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
        if (n < 1) throw Fail("Amount must be positive.")
        if (!Bank.take(p.uuid, n)) throw Fail("You don't have {${spur(n)}}.")
        c.treasury += n
        c.pending += n
        return "Deposited {${spur(n)}}, treasury {${spur(c.treasury)}}."
    }

    private fun withdraw(p: ServerPlayer, n: Int): String {
        val c = need(p, Cap.WITHDRAW)
        if (n < 1) throw Fail("Amount must be positive.")
        if (c.treasury < n) throw Fail("The treasury only holds {${spur(c.treasury)}}.")
        if (!Bank.give(p.uuid, n)) throw Fail("Could not pay out.")
        c.treasury -= n
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

    private fun taxAmount(text: String, mode: TaxMode): Double {
        val v = text.toDoubleOrNull() ?: throw Fail("Expected a number.")
        return if (mode == TaxMode.PERCENT) (v / 100).coerceIn(s.provinceTaxRateBounds[0], s.provinceTaxRateBounds[1]) else max(0.0, v)
    }

    internal fun finalizeProvince(child: Country, parent: Country, mode: TaxMode, amount: Double) {
        child.provinces.forEach { pid -> Realm.country(pid)?.let { it.parent = parent.id }; parent.provinces += pid }
        child.provinces.clear()
        child.parent = parent.id
        child.taxMode = mode
        child.taxAmount = amount
        child.provinceDebt = 0
        child.independenceRequested = false
        child.provinceInvites.clear()
        child.provinceRequests.clear()
        parent.provinces += child.id
        Realm.syncFamily(parent.id)
        Mail.broadcast(child, "{${child.name}} is now a province of {${parent.name}}.")
        Mail.broadcast(parent, "{${child.name}} joined as a province.")
    }

    private fun provinceInvite(p: ServerPlayer, name: String, mode: TaxMode, amountText: String): String {
        val c = need(p, Cap.PROVINCE)
        if (c.parent != null) throw Fail("A province cannot have its own provinces.")
        val target = Realm.country(name) ?: throw Fail("Unknown country.")
        if (target.id == c.id) throw Fail("A country cannot be its own province.")
        if (target.parent == c.id) throw Fail("Already your province.")
        val amount = taxAmount(amountText, mode)
        target.provinceInvites[c.id] = ProvinceOffer(now() + s.inviteDays * s.dayMillis, mode, amount)
        Mail.officers(target, "{${c.name}} invites you to become their province. Answer in the country screen.")
        return "Invitation sent."
    }

    private fun provinceAccept(p: ServerPlayer, name: String): String {
        val c = need(p, Cap.PROVINCE)
        if (c.parent != null) throw Fail("Already a province.")
        val parent = Realm.country(name) ?: throw Fail("Unknown country.")
        val offer = c.provinceInvites[parent.id] ?: throw Fail("No invitation from that country.")
        if (offer.until < now()) { c.provinceInvites.remove(parent.id); throw Fail("The invitation expired.") }
        finalizeProvince(c, parent, offer.mode, offer.amount)
        return "{${c.name}} is now a province of {${parent.name}}."
    }

    private fun provinceRequest(p: ServerPlayer, name: String): String {
        val c = need(p, Cap.PROVINCE)
        if (c.parent != null) throw Fail("Already a province.")
        val target = Realm.country(name) ?: throw Fail("Unknown country.")
        if (target.id == c.id) throw Fail("A country cannot be its own province.")
        target.provinceRequests[c.id] = now() + s.inviteDays * s.dayMillis
        Mail.officers(target, "{${c.name}} wants to become your province. Answer in the country screen.")
        return "Request sent."
    }

    private fun provinceApprove(p: ServerPlayer, name: String, mode: TaxMode, amountText: String): String {
        val parent = need(p, Cap.PROVINCE)
        if (parent.parent != null) throw Fail("A province cannot have its own provinces.")
        val child = Realm.country(name) ?: throw Fail("Unknown country.")
        if ((parent.provinceRequests[child.id] ?: 0) < now()) throw Fail("No valid request from that country.")
        if (child.parent != null) throw Fail("That country already has a parent.")
        parent.provinceRequests.remove(child.id)
        finalizeProvince(child, parent, mode, taxAmount(amountText, mode))
        return "{${child.name}} is now your province."
    }

    private fun provinceDeny(p: ServerPlayer, name: String): String {
        val c = need(p, Cap.PROVINCE)
        val target = Realm.country(name) ?: throw Fail("Unknown country.")
        if (c.provinceRequests.remove(target.id) == null) throw Fail("No request from that country.")
        return "Request denied."
    }

    private fun provinceRelease(p: ServerPlayer, name: String): String {
        val parent = need(p, Cap.PROVINCE)
        val child = Realm.country(name) ?: throw Fail("Unknown country.")
        if (child.parent != parent.id) throw Fail("That is not your province.")
        child.parent = null
        child.provinceDebt = 0
        child.independenceRequested = false
        parent.provinces.remove(child.id)
        Realm.syncFamily(child.id)
        Realm.syncFamily(parent.id)
        Mail.broadcast(child, "{${child.name}} is independent again.", Tone.OK)
        Mail.broadcast(parent, "{${child.name}} is no longer your province.")
        return "{${child.name}} is now independent."
    }

    private fun provinceForgive(p: ServerPlayer, name: String): String {
        val parent = need(p, Cap.PROVINCE)
        val child = Realm.country(name) ?: throw Fail("Unknown country.")
        if (child.parent != parent.id) throw Fail("That is not your province.")
        child.provinceDebt = 0
        return "Debt forgiven."
    }

    private fun provinceIndependence(p: ServerPlayer): String {
        val c = need(p, Cap.PROVINCE)
        val parent = c.parent?.let { Realm.country(it) } ?: throw Fail("Not a province.")
        c.independenceRequested = true
        Mail.officers(parent, "{${c.name}} asks for independence. Answer in the country screen.")
        return "Independence requested."
    }

    private fun provinceTax(p: ServerPlayer, name: String, mode: TaxMode, amountText: String): String {
        val parent = need(p, Cap.PROVINCE)
        val child = Realm.country(name) ?: throw Fail("Unknown country.")
        if (child.parent != parent.id) throw Fail("That is not your province.")
        child.taxMode = mode
        child.taxAmount = taxAmount(amountText, mode)
        return "Tax terms updated."
    }

    private fun provinceGive(p: ServerPlayer, name: String, newParentName: String): String {
        val parent = need(p, Cap.PROVINCE)
        val child = Realm.country(name) ?: throw Fail("Unknown country.")
        if (child.parent != parent.id) throw Fail("That is not your province.")
        val newParent = Realm.country(newParentName) ?: throw Fail("Unknown country.")
        if (newParent.id == child.id) throw Fail("A country cannot be its own province.")
        if (newParent.parent != null) throw Fail("{${newParent.name}} is a province itself and cannot hold one.")
        parent.provinces.remove(child.id)
        child.parent = newParent.id
        child.independenceRequested = false
        newParent.provinces += child.id
        Realm.syncFamily(parent.id)
        Realm.syncFamily(newParent.id)
        Mail.broadcast(child, "{${child.name}} was given to {${newParent.name}}.")
        Mail.broadcast(newParent, "{${child.name}} is now your province.")
        return "{${child.name}} now belongs to {${newParent.name}}."
    }
}
