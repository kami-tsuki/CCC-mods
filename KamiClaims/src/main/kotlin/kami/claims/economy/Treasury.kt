package kami.claims.economy

import kami.claims.net.ResearchSync
import kami.claims.Config
import kami.claims.Country
import kami.claims.DayStat
import kami.claims.LedgerEntry
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.now
import kami.claims.research.Capacity
import kami.claims.research.Levels
import kami.claims.war.WarDebt
import kami.libs.economy.CountryParty
import kami.libs.economy.Flow
import kami.libs.economy.Journal
import kami.libs.economy.Party
import kami.libs.economy.ServerBank
import kami.libs.economy.ServerParty
import kami.libs.stats.Stats

object Treasury {
    fun room(c: Country): Long = (Levels.capacity(c, Capacity.TREASURY) - c.treasury).coerceAtLeast(0)

    fun move(
        c: Country, kind: LedgerKind, delta: Long, actor: String? = null, note: String = "", capped: Boolean = true,
        flow: Flow = flowOf(kind), counterparty: Party? = actor?.let { Party.parse(it) }
    ): Long {
        val applied = applicable(c, kind, delta, capped)
        c.treasury += applied
        record(c, kind, applied, actor, note, flow, counterparty)
        WarDebt.garnish(c, kind, applied)
        return applied
    }

    fun bank(c: Country, kind: LedgerKind, delta: Long, actor: String? = null, note: String = "", capped: Boolean = true, flow: Flow = flowOf(kind)): Long {
        val applied = applicable(c, kind, delta, capped)
        if (applied != 0L && settle(CountryParty(c.id), applied, flow, note) == 0L) return 0
        return move(c, kind, applied, actor, note, capped, flow, ServerParty)
    }

    private fun applicable(c: Country, kind: LedgerKind, delta: Long, capped: Boolean) = when {
        delta <= 0 -> delta
        capped && kind != LedgerKind.ADJUST -> minOf(delta, room(c))
        else -> minOf(delta, Long.MAX_VALUE - c.treasury.coerceAtLeast(0))
    }

    private fun settle(party: Party, delta: Long, flow: Flow, note: String) =
        if (delta > 0) ServerBank.pay(party, delta, flow, note) else ServerBank.collect(party, -delta, flow, note)

    private fun flowOf(kind: LedgerKind) = when (kind) {
        LedgerKind.DEPOSIT, LedgerKind.WITHDRAW -> Flow.TREASURY
        LedgerKind.CLAIM -> Flow.CLAIM
        LedgerKind.UPKEEP -> Flow.UPKEEP
        LedgerKind.PLOT_TAX -> Flow.RENT
        LedgerKind.JOB_PAY -> Flow.WAGES
        LedgerKind.TRIBUTE_IN, LedgerKind.TRIBUTE_OUT, LedgerKind.TARIFF -> Flow.TAX
        LedgerKind.ADJUST -> Flow.ADMIN
        LedgerKind.RESEARCH -> Flow.RESEARCH
        LedgerKind.LEVEL_REWARD -> Flow.REWARD
        LedgerKind.FEE -> Flow.FEE
        LedgerKind.LOAN_IN, LedgerKind.LOAN_PAYMENT -> Flow.LOAN
        LedgerKind.WAR_FEE, LedgerKind.WAR_STAKE, LedgerKind.WAR_BILL, LedgerKind.WAR_DEBT -> Flow.WAR
    }

    fun drain(c: Country, flow: Flow, note: String = "") {
        if (c.treasury != 0L) bank(c, LedgerKind.ADJUST, -c.treasury, note = note, capped = false, flow = flow)
    }

    private fun record(c: Country, kind: LedgerKind, delta: Long, actor: String?, note: String, flow: Flow, counterparty: Party?) {
        if (delta == 0L) return
        Journal.leg(CountryParty(c.id), delta, flow, counterparty, note, kind.name)
        ResearchSync.refresh(c)
        c.ledger += LedgerEntry(now(), kind, delta, c.treasury, actor, note)
        val overflow = c.ledger.size - Config.s.ledgerSize
        if (overflow > 0) c.ledger.subList(0, overflow).clear()
    }

    fun snapshot(c: Country, day: Long) {
        val since = c.history.lastOrNull()?.at ?: 0L
        val window = c.ledger.filter { it.at > since }
        fun sum(kind: LedgerKind) = window.filter { it.kind == kind }.sumOf { it.amount }
        val claims = Realm.claims(c.id)
        c.history += DayStat(
            day, now(), c.treasury, sum(LedgerKind.PLOT_TAX), -sum(LedgerKind.UPKEEP), -sum(LedgerKind.JOB_PAY),
            sum(LedgerKind.TRIBUTE_IN), -sum(LedgerKind.TRIBUTE_OUT), sum(LedgerKind.DEPOSIT), -sum(LedgerKind.WITHDRAW),
            claims.size, claims.count { it.debt > 0 }, c.members.size, claims.count { it.owner != null },
            claims.groupingBy { it.type }.eachCount(), window.groupBy { it.kind.name }.mapValues { (_, list) -> list.sumOf { it.amount } }
        )
        Stats.add("country.history", "c:${c.id}", "day")
        val overflow = c.history.size - Config.s.historyDays
        if (overflow > 0) c.history.subList(0, overflow).clear()
    }
}
