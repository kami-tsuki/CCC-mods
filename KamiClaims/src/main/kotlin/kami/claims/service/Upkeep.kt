package kami.claims.service

import kami.claims.net.ResearchSync
import kami.claims.*
import kami.claims.economy.Treasury
import kami.claims.research.Buffs
import kami.claims.research.Features
import kami.claims.research.Levels
import kami.claims.research.Loans
import kami.claims.research.Progress
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.claims.service.Words.chunks
import kami.claims.service.Words.money
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.libs.text.Phrase

import net.minecraft.server.MinecraftServer
import kotlin.math.floor

object Upkeep {
    private val s get() = Config.s

    private const val SWEEP_MS = 60_000L

    private var swept = 0L

    fun tick(server: MinecraftServer) {
        val t = now()
        if (t - swept >= SWEEP_MS) {
            swept = t
            if (Housing.sweep()) Realm.changed()
        }
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
        while (d < today && n++ < s.maxCatchUp) {
            process(d + 1)
            d++
        }
        if (d != Realm.data.day) Realm.dirty = true
        Realm.data.day = d
    }

    /** Settles [day] for every country; a failing country is logged and skipped, the day is never retried (no double billing). */
    fun process(day: Long) {
        Realm.data.countries.values.toList().forEach {
            val pending = it.pending
            runCatching { country(it, day) }.onFailure { e ->
                if (it.pending < pending) it.pending = pending
                KamiClaims.LOG.error("Upkeep failed for country ${it.id} (${it.name})", e)
            }
        }
        Realm.data.reserves.removeAll { it.until < now() }
        Realm.changed()
    }

    fun reset() { swept = 0L }

    private fun country(c: Country, day: Long) {
        val t = now()
        c.invites.values.removeAll { it < t }
        c.requests.values.removeAll { it < t }
        c.provinceInvites.values.removeAll { it.until < t }
        c.provinceRequests.values.removeAll { it < t }
        c.allianceOffers.values.removeAll { it < t }
        c.reclaimLocks.values.removeAll { it < t }
        if (day >= today()) Housing.warn(c)
        if (!c.active) return
        if (c.members.isEmpty()) return Realm.disband(c)
        succession(c)
        expire(c)
        val income = Housing.collect(c) + c.pending
        c.pending = 0
        Loans.collect(c, day)
        Realm.refreshFree(c)
        Levels.add(c, "claims", Realm.claims(c.id).size.toDouble())
        bill(c, day)
        provinceTax(c, income)
        Work.pay(c, day, income)
        Treasury.snapshot(c, day)
    }

    private fun seniority() = compareByDescending<Map.Entry<String, Member>> { it.value.rank }.thenBy { it.value.since }

    private fun succession(c: Country) {
        val boss = c.president()?.let { c.members[it] } ?: return
        if (now() - boss.seen <= s.successionDays * s.dayMillis) return
        val heir = c.members.entries.filter { it.value !== boss && it.value.rank != Rank.BANISHED }.sortedWith(seniority()).firstOrNull() ?: return
        boss.rank = Rank.CITIZEN
        heir.value.rank = Rank.PRESIDENT
        if (Features.unlocked(c, Features.CHANCELLOR) && c.members.values.none { it.rank == Rank.CHANCELLOR }) {
            c.members.entries.filter { it.value !== heir.value && it.value.rank != Rank.BANISHED }.sortedWith(seniority()).firstOrNull()?.value?.rank = Rank.CHANCELLOR
        }
        Mail.broadcast(c, Phrase.of("kami_claims.mail.succession"))
    }

    private fun expire(c: Country) {
        if (now() - c.lastActive <= s.inactiveDays * s.dayMillis) return
        val claims = Realm.claims(c.id).sortedByDescending { it.at }
        claims.filter { it.free && !it.capital && it.owner == null }.forEach { if (Realm.removable(it)) Realm.unclaim(it, true) }
        claims.firstOrNull { it.capital && it.owner == null }?.let { if (Realm.claims(c.id).size == 1) Realm.unclaim(it, true) }
        ResearchSync.refresh(c)
    }

    private fun bill(c: Country, day: Long) {
        val claims = Realm.claims(c.id)
        var paid = 0L
        val borderTax = Buffs.billedTax(c)
        claims.filter { day > it.since && (day - it.since) % Realm.period(it) == 0L }.sortedBy { it.at }.forEach { cl ->
            if (cl.free) {
                cl.upkeepCycles++
                return@forEach
            }

            val cost = Buffs.price(c, cl, borderTax).toLong() * (1 + cl.debt)
            if (c.treasury >= cost) {
                c.treasury -= cost
                Realm.saveSoon()
                paid += cost
                cl.debt = 0
                cl.upkeepCycles++
            } else cl.debt++
        }
        Treasury.record(c, LedgerKind.UPKEEP, -paid)
        Buffs.settle(c)
        val lost = claims.filter { it.debt >= s.maxDebt }.sortedWith(compareBy<Claim> { it.owner != null }.thenByDescending { it.at }).count {
            val ok = !it.capital && Realm.removable(it)
            if (ok) {
                it.owner?.let { owner -> Mail.direct(owner, Phrase.of("kami_claims.mail.plot_lost", v("${it.x}, ${it.z}")), Tone.BAD) }
                Realm.unclaim(it, true)
            }
            ok
        }
        val indebt = Realm.claims(c.id).count { it.debt > 0 }
        if (lost > 0) Mail.broadcast(c, Phrase.of("kami_claims.mail.debt_lost", chunks(lost)), Tone.BAD)
        if (lost > 0) ResearchSync.refresh(c)
        if (indebt > 0) Mail.broadcast(c, Phrase.of("kami_claims.mail.debt", chunks(indebt)), Tone.WARN)
    }

    private fun provinceTax(c: Country, income: Long) {
        val parent = c.parent?.let { Realm.country(it) } ?: return
        val owed = when (c.taxMode) {
            TaxMode.PERCENT -> floor(income * c.taxAmount).toLong()
            TaxMode.FLAT -> c.taxAmount.toLong()
        }.coerceIn(0, Treasury.room(parent))
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

    class Summary(val upkeep: Long, val income: Long, val jobs: Long) {
        private val net get() = upkeep + jobs - income
        fun runway(treasury: Long): Phrase = if (net <= 0) Phrase.of("kami_claims.runway.stable") else Words.days(treasury / net)
    }

    fun summary(c: Country): Summary {
        val claims = Realm.claims(c.id)
        val borderTax = Buffs.tax(c)
        return Summary(
            claims.filter { !it.free }.sumOf { Buffs.price(c, it, borderTax).toLong() * 1000 / Realm.period(it) } / 1000,
            Housing.income(c),
            Work.payroll(c)
        )
    }
}
