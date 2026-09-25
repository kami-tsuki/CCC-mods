package kami.claims.service

import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.chat.plural
import kami.libs.chat.spur

import net.minecraft.server.MinecraftServer
import java.util.UUID
import kotlin.math.floor

object Upkeep {
    private val s get() = Config.s

    fun tick(server: MinecraftServer) {
        val t = now()
        server.playerList.players.forEach { p ->
            val c = Realm.of(p.stringUUID) ?: return@forEach
            c.lastActive = t
            c.members[p.stringUUID]?.seen = t
        }
        val today = today()
        if (Realm.data.day < 0) Realm.data.day = today
        var d = Realm.data.day
        var n = 0
        while (d < today && n++ < s.maxCatchUp) process(++d)
        if (Realm.data.day != today) Realm.dirty = true
        Realm.data.day = today
    }

    fun process(day: Long) {
        Realm.data.countries.values.toList().forEach { runCatching { country(it, day) }.onFailure { e -> KamiClaims.LOG.error("Upkeep failed for ${it.name}", e) } }
        Realm.data.reserves.removeAll { it.until < now() }
        Realm.changed()
    }

    private fun country(c: Country, day: Long) {
        val t = now()
        c.invites.values.removeAll { it < t }
        c.requests.values.removeAll { it < t }
        c.provinceInvites.values.removeAll { it.until < t }
        c.provinceRequests.values.removeAll { it < t }
        if (c.members.isEmpty()) return Realm.disband(c)
        succession(c)
        expire(c)
        val income = taxes(c) + c.pending
        c.pending = 0
        Realm.refreshFree(c)
        bill(c, day)
        provinceTax(c, income)
        jobs(c, day, income)
    }

    private fun seniority() = compareByDescending<Map.Entry<String, Member>> { it.value.rank }.thenBy { it.value.since }

    private fun succession(c: Country) {
        val boss = c.president()?.let { c.members[it] } ?: return
        if (now() - boss.seen <= s.successionDays * s.dayMillis) return
        val heir = c.members.entries.filter { it.value !== boss && it.value.rank != Rank.BANISHED }.sortedWith(seniority()).firstOrNull() ?: return
        boss.rank = Rank.CITIZEN
        heir.value.rank = Rank.PRESIDENT
        if (c.members.values.none { it.rank == Rank.CHANCELLOR }) {
            c.members.entries.filter { it.value !== heir.value && it.value.rank != Rank.BANISHED }.sortedWith(seniority()).firstOrNull()?.value?.rank = Rank.CHANCELLOR
        }
        Mail.broadcast(c, "The president was inactive, so the presidency passed on.")
    }

    private fun expire(c: Country) {
        if (now() - c.lastActive <= s.inactiveDays * s.dayMillis) return
        val claims = Realm.claims(c.id).sortedByDescending { it.at }
        claims.filter { it.free && !it.capital }.forEach { if (Realm.removable(it)) Realm.unclaim(it, true) }
        claims.firstOrNull { it.capital }?.let { if (Realm.claims(c.id).size == 1) Realm.unclaim(it, true) }
    }

    private fun taxes(c: Country): Long {
        var income = 0L
        Realm.claims(c.id).filter { it.owner != null }.forEach { cl ->
            val tax = if (cl.tax >= 0) cl.tax else c.tax
            if (tax <= 0 || Bank.take(UUID.fromString(cl.owner), tax)) {
                cl.lapse = 0
                c.treasury += tax
                income += tax
            } else {
                val owner = cl.owner!!
                if (++cl.lapse >= c.shutdown + c.release) {
                    cl.owner = null
                    cl.roles.clear()
                    cl.lapse = 0
                    Mail.direct(owner, "You lost your plot, the tax was not paid.", Tone.BAD)
                } else Mail.direct(owner, "Your plot tax of {${spur(tax)}} could not be paid.", Tone.BAD)
            }
        }
        return income
    }

    private fun bill(c: Country, day: Long) {
        val claims = Realm.claims(c.id)
        claims.filter { !it.free && day > it.since && (day - it.since) % Realm.period(it) == 0L }.sortedBy { it.at }.forEach { cl ->
            val cost = Realm.price(cl).toLong() * (1 + cl.debt)
            if (c.treasury >= cost) {
                c.treasury -= cost
                cl.debt = 0
            } else cl.debt++
        }
        val lost = claims.filter { it.debt >= s.maxDebt }.sortedByDescending { it.at }.count {
            (!it.capital && Realm.removable(it)).also { ok -> if (ok) Realm.unclaim(it, true) }
        }
        val indebt = Realm.claims(c.id).count { it.debt > 0 }
        if (lost > 0) Mail.broadcast(c, "{${plural(lost, "chunk")}} lost to debt, they are nomansland now.", Tone.BAD)
        if (indebt > 0) Mail.broadcast(c, "{${plural(indebt, "chunk")}} in debt. Deposit coins into the treasury.", Tone.WARN)
    }

    private fun provinceTax(c: Country, income: Long) {
        val parent = c.parent?.let { Realm.country(it) } ?: return
        val owed = when (c.taxMode) {
            TaxMode.PERCENT -> floor(income * c.taxAmount).toLong()
            TaxMode.FLAT -> c.taxAmount.toLong()
        }.coerceAtLeast(0)
        if (owed == 0L) { c.provinceDebt = 0; return }
        if (c.treasury >= owed) {
            c.treasury -= owed
            parent.treasury += owed
            c.provinceDebt = 0
        } else {
            c.provinceDebt++
            val urgent = c.provinceDebt >= s.maxProvinceDebt
            Mail.officers(c, "Could not pay {${spur(owed)}} tribute to {${parent.name}}. Missed payments: {${c.provinceDebt}}.", Tone.BAD)
            Mail.officers(parent, if (urgent) "{${c.name}} missed tribute {${c.provinceDebt}} times. Release them or forgive the debt." else "{${c.name}} could not pay {${spur(owed)}} tribute. Missed payments: {${c.provinceDebt}}.", Tone.BAD)
        }
    }

    private fun jobs(c: Country, day: Long, income: Long) {
        var budget = floor(income * s.jobShare).toLong()
        c.members.forEach { (id, m) ->
            val name = m.job ?: return@forEach
            val def = c.job(name) ?: return@forEach
            if (day - m.start < def.period) return@forEach
            val due = m.progress >= def.quota
            m.progress = 0
            m.start = day
            if (!due) return@forEach
            if (def.pay <= budget && def.pay <= c.treasury && Bank.give(UUID.fromString(id), def.pay)) {
                c.treasury -= def.pay
                budget -= def.pay
                Mail.direct(id, "Paid {${spur(def.pay)}} for your work as {$name}.", Tone.OK)
            } else Mail.direct(id, "The treasury could not pay your {$name} wage.", Tone.BAD)
        }
    }

    class Summary(val upkeep: Long, val income: Long, val jobs: Long) {
        private val net get() = upkeep + jobs - income
        fun runway(treasury: Long) = if (net <= 0) "stable" else "${treasury / net} days"
    }

    fun summary(c: Country): Summary {
        val claims = Realm.claims(c.id)
        return Summary(
            claims.filter { !it.free }.sumOf { Realm.price(it).toLong() * 1000 / Realm.period(it) } / 1000,
            claims.filter { it.owner != null }.sumOf { (if (it.tax >= 0) it.tax else c.tax).toLong() },
            c.members.values.sumOf { m -> (m.job?.let { j -> c.job(j)?.pay } ?: 0).toLong() }
        )
    }
}
