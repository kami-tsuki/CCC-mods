package kami.claims.service

import kami.claims.net.ResearchSync
import kami.claims.*
import kami.claims.economy.Treasury
import kami.claims.research.Buffs
import kami.claims.gov.CITIZEN
import kami.claims.gov.Leadership
import kami.claims.gov.ProvinceRule
import kami.claims.gov.PRESIDENT
import kami.claims.gov.Permissions
import kami.claims.gov.changeRank
import kami.claims.gov.heirEligible
import kami.claims.gov.leaderChanged
import kami.claims.research.Levels
import kami.claims.research.Loans
import kami.claims.social.Mail
import kami.claims.war.Reputation
import kami.claims.war.WarLocks
import kami.claims.war.Wars
import kami.libs.chat.Tone
import kami.libs.economy.CountryParty
import kami.claims.service.Words.chunks
import kami.claims.service.Words.money
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.libs.text.Phrase

import net.minecraft.server.MinecraftServer

object Upkeep {
    private val s get() = Config.s

    private const val SWEEP_MS = 60_000L
    private const val ELECTORATE_DAYS = 3

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
        var n = 0
        while (Realm.data.day < today && n++ < s.maxCatchUp) {
            process(Realm.data.day + 1)
            Realm.data.day++
        }
    }

    fun process(day: Long) {
        Realm.data.countries.values.toList().forEach {
            val pending = it.pending
            runCatching { country(it, day) }.onFailure { e ->
                if (it.pending < pending) it.pending = pending
                KamiClaims.LOG.error("Upkeep failed for country ${it.id} (${it.name})", e)
            }
        }
        Realm.data.reserves.removeAll { it.until < now() }
        runCatching { Reputation.decay() }.onFailure { KamiClaims.LOG.error("Reputation decay failed", it) }
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
        if (c.members.isEmpty() && !Wars.involved(c)) return Realm.disband(c)
        succession(c)
        expire(c)
        if (!c.active) return
        val rent = Housing.collect(c)
        val income = rent + c.pending
        c.pending = 0
        Loans.collect(c, day)
        Realm.refreshFree(c)
        Levels.add(c, "claims", Realm.claimCount(c.id).toDouble())
        bill(c, day)
        provinceTax(c, rent)
        Work.pay(c, day, income)
        Treasury.snapshot(c, day)
    }

    private fun seniority(c: Country) = compareBy<Map.Entry<String, Member>> { Permissions.position(c, it.value.rank) }.thenBy { it.value.since }

    private fun recentlySeen(m: Member) = now() - m.seen <= s.successionDays * s.dayMillis

    private fun tenured(m: Member) = now() - m.since >= ELECTORATE_DAYS * s.dayMillis

    internal fun heir(c: Country, bossId: String): String? {
        val candidates = c.members.filter { it.key != bossId && recentlySeen(it.value) && tenured(it.value) }
        val bySeniority = candidates.entries.minWithOrNull(seniority(c))?.key
        val named = c.government.heir?.takeIf { it in candidates && c.government.heirEligible(candidates.getValue(it)) }
        return when (c.government.levers.leadership) {
            Leadership.HEREDITARY -> named ?: bySeniority
            Leadership.APPOINTED -> named ?: elected(c, candidates) ?: bySeniority
            Leadership.ELECTED -> elected(c, candidates) ?: bySeniority
            Leadership.SEIZED -> bySeniority
        }
    }

    private fun elected(c: Country, candidates: Map<String, Member>): String? {
        val electorate = c.members.filterValues(::tenured).keys
        val tally = c.government.votes.filterKeys { it in electorate }.values.filter { it in candidates }.groupingBy { it }.eachCount()
        val leading = tally.maxByOrNull { it.value } ?: return null
        return leading.key.takeIf { leading.value * 2 > electorate.size }
    }

    private fun succession(c: Country) {
        val bossId = Permissions.president(c) ?: return
        val boss = c.members.getValue(bossId)
        if (recentlySeen(boss)) return
        val heirId = heir(c, bossId) ?: return
        val old = Announce.name(bossId)
        Housing.repriced(c) {
            boss.changeRank(CITIZEN)
            c.members.getValue(heirId).let { it.changeRank(PRESIDENT); it.seen = now() }
        }
        c.leaderChanged()
        Mail.broadcast(c, Phrase.of("kami_claims.mail.succession"))
        Announce.fire("succession", c, mapOf("country" to c.name, "old" to old, "leader" to Announce.name(heirId)))
    }

    private fun expire(c: Country) {
        if (now() - c.lastActive <= s.inactiveDays * s.dayMillis || Wars.involved(c)) return
        val claims = Realm.claims(c.id).sortedByDescending { it.at }
        claims.filter { it.free && !it.capital && it.owner == null }.forEach { if (Realm.removable(it)) Realm.unclaim(it, true) }
        claims.firstOrNull { it.capital && it.owner == null }?.let { if (Realm.claimCount(c.id) == 1) Realm.unclaim(it, true) }
        ResearchSync.refresh(c)
        if (Realm.claimCount(c.id) == 0) Realm.disband(c)
    }

    private fun bill(c: Country, day: Long) {
        val claims = Realm.claims(c.id)
        var paid = 0L
        var left = c.treasury
        val borderTax = Buffs.billedTax(c)
        claims.filter { day > it.since && (day - it.since) % Realm.period(it) == 0L }.sortedBy { it.at }.forEach { cl ->
            if (cl.free) {
                cl.upkeepCycles++
                return@forEach
            }

            val cost = Buffs.price(c, cl, borderTax).toLong() * (1 + cl.debt)
            if (left >= cost) {
                left -= cost
                paid += cost
                cl.debt = 0
                cl.upkeepCycles++
            } else cl.debt = WarLocks.upkeepDebt(c, cl.debt + 1)
        }
        Treasury.bank(c, LedgerKind.UPKEEP, -paid)
        Buffs.settle(c)
        val lost = if (!WarLocks.upkeepRelease(c)) 0 else claims.filter { it.debt >= s.maxDebt }.sortedWith(compareBy<Claim> { it.owner != null }.thenByDescending { it.at }).count {
            val ok = !it.capital && Realm.removable(it)
            if (ok) {
                it.owner?.let { owner -> Mail.direct(owner, Phrase.of("kami_claims.mail.plot_lost", v("${it.x}, ${it.z}")), Tone.BAD) }
                Realm.unclaim(it, true)
            }
            ok
        }
        val indebt = Realm.claimsView(c.id).count { it.debt > 0 }
        if (lost > 0) Mail.broadcast(c, Phrase.of("kami_claims.mail.debt_lost", chunks(lost)), Tone.BAD)
        if (lost > 0) ResearchSync.refresh(c)
        if (lost > 0) Reputation.upkeepLoss(c)
        if (indebt > 0) Mail.broadcast(c, Phrase.of("kami_claims.mail.debt", chunks(indebt)), Tone.WARN)
    }

    private fun provinceTax(c: Country, rent: Long) {
        val parent = c.parent?.let { Realm.country(it) } ?: return
        val rule = parent.government.levers.provinces
        if (rule == ProvinceRule.FREE) { c.provinceDebt = 0; return }
        Reputation.tribute(c, parent, rent)
        val amount = if (rule == ProvinceRule.AUTONOMOUS) c.taxAmount / 2 else c.taxAmount
        val full = tributeOwed(c.taxMode, amount, rent)
        if (full == 0L) { c.provinceDebt = 0; return }
        val owed = full.coerceAtMost(Treasury.room(parent))
        if (owed == 0L) return
        if (c.treasury >= owed) {
            Treasury.move(c, LedgerKind.TRIBUTE_OUT, -owed, note = parent.name, counterparty = CountryParty(parent.id))
            Treasury.move(parent, LedgerKind.TRIBUTE_IN, owed, note = c.name, counterparty = CountryParty(c.id))
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
        val borderTax = Buffs.billedTax(c)
        return Summary(
            claims.filter { !it.free }.sumOf { Buffs.price(c, it, borderTax).toLong() * 1000 / Realm.period(it) } / 1000,
            Housing.income(c),
            Work.payroll(c)
        )
    }
}
