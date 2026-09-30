package kami.claims.service

import kami.claims.*
import kami.claims.economy.Bank
import kami.claims.economy.Treasury
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.claims.service.Words.chunks
import kami.claims.service.Words.money
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.libs.text.Phrase

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
        catchUp(today())
    }

    internal fun catchUp(today: Long) {
        if (Realm.data.day < 0) Realm.data.day = today
        var d = Realm.data.day
        var n = 0
        while (d < today && n++ < s.maxCatchUp) process(++d)
        if (d != Realm.data.day) Realm.dirty = true
        Realm.data.day = d
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
        c.allianceOffers.values.removeAll { it < t }
        if (c.members.isEmpty()) return Realm.disband(c)
        succession(c)
        expire(c)
        val income = taxes(c) + c.pending
        c.pending = 0
        Realm.refreshFree(c)
        bill(c, day)
        provinceTax(c, income)
        jobs(c, day, income)
        Treasury.snapshot(c, day)
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
        Mail.broadcast(c, Phrase.of("kami_claims.mail.succession"))
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
                income += tax
            } else {
                val owner = cl.owner!!
                if (++cl.lapse >= c.shutdown + c.release) {
                    cl.owner = null
                    cl.roles.clear()
                    cl.lapse = 0
                    Mail.direct(owner, Phrase.of("kami_claims.mail.plot_lost"), Tone.BAD)
                } else Mail.direct(owner, Phrase.of("kami_claims.mail.plot_tax_unpaid", money(tax)), Tone.BAD)
            }
        }
        Treasury.move(c, LedgerKind.PLOT_TAX, income)
        return income
    }

    private fun bill(c: Country, day: Long) {
        val claims = Realm.claims(c.id)
        var paid = 0L
        claims.filter { day > it.since && (day - it.since) % Realm.period(it) == 0L }.sortedBy { it.at }.forEach { cl ->
            if (cl.free) {
                cl.upkeepCycles++
                return@forEach
            }

            val cost = Realm.price(cl).toLong() * (1 + cl.debt)
            if (c.treasury >= cost) {
                c.treasury -= cost
                paid += cost
                cl.debt = 0
                cl.upkeepCycles++
            } else cl.debt++
        }
        Treasury.record(c, LedgerKind.UPKEEP, -paid)
        val lost = claims.filter { it.debt >= s.maxDebt }.sortedByDescending { it.at }.count {
            (!it.capital && Realm.removable(it)).also { ok -> if (ok) Realm.unclaim(it, true) }
        }
        val indebt = Realm.claims(c.id).count { it.debt > 0 }
        if (lost > 0) Mail.broadcast(c, Phrase.of("kami_claims.mail.debt_lost", chunks(lost)), Tone.BAD)
        if (indebt > 0) Mail.broadcast(c, Phrase.of("kami_claims.mail.debt", chunks(indebt)), Tone.WARN)
    }

    private fun provinceTax(c: Country, income: Long) {
        val parent = c.parent?.let { Realm.country(it) } ?: return
        val owed = when (c.taxMode) {
            TaxMode.PERCENT -> floor(income * c.taxAmount).toLong()
            TaxMode.FLAT -> c.taxAmount.toLong()
        }.coerceAtLeast(0)
        if (owed == 0L) { c.provinceDebt = 0; return }
        if (c.treasury >= owed) {
            Treasury.move(c, LedgerKind.TRIBUTE_OUT, -owed, note = parent.name)
            Treasury.move(parent, LedgerKind.TRIBUTE_IN, owed, note = c.name)
            c.provinceDebt = 0
        } else {
            c.provinceDebt++
            val urgent = c.provinceDebt >= s.maxProvinceDebt
            Mail.officers(c, Phrase.of("kami_claims.mail.tribute_unpaid", money(owed), v(parent.name), num(c.provinceDebt)), Tone.BAD)
            Mail.officers(parent, if (urgent) Phrase.of("kami_claims.mail.tribute_urgent", v(c.name), num(c.provinceDebt)) else Phrase.of("kami_claims.mail.tribute_missed", v(c.name), money(owed), num(c.provinceDebt)), Tone.BAD)
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
                Treasury.move(c, LedgerKind.JOB_PAY, -def.pay.toLong(), id, name)
                budget -= def.pay
                Mail.direct(id, Phrase.of("kami_claims.mail.wage_paid", money(def.pay), Words.job(name)), Tone.OK)
            } else Mail.direct(id, Phrase.of("kami_claims.mail.wage_unpaid", Words.job(name)), Tone.BAD)
        }
    }

    class Summary(val upkeep: Long, val income: Long, val jobs: Long) {
        private val net get() = upkeep + jobs - income
        fun runway(treasury: Long): Phrase = if (net <= 0) Phrase.of("kami_claims.runway.stable") else Words.days(treasury / net)
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
