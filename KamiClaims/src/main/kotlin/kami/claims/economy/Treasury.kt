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

object Treasury {
    fun room(c: Country): Long = (Levels.capacity(c, Capacity.TREASURY) - c.treasury).coerceAtLeast(0)

    fun move(c: Country, kind: LedgerKind, delta: Long, actor: String? = null, note: String = "", capped: Boolean = true): Long {
        val applied = when {
            delta <= 0 -> delta
            capped && kind != LedgerKind.ADJUST -> minOf(delta, room(c))
            else -> minOf(delta, Long.MAX_VALUE - c.treasury.coerceAtLeast(0))
        }
        c.treasury += applied
        if (applied != 0L) Realm.saveSoon()
        record(c, kind, applied, actor, note)
        WarDebt.garnish(c, kind, applied)
        return applied
    }

    fun record(c: Country, kind: LedgerKind, delta: Long, actor: String? = null, note: String = "") {
        if (delta == 0L) return
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
        val overflow = c.history.size - Config.s.historyDays
        if (overflow > 0) c.history.subList(0, overflow).clear()
    }
}
