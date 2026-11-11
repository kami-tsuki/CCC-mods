package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.WithdrawalRequest
import kami.claims.economy.Bank
import kami.claims.economy.Treasury
import kami.claims.now
import kami.claims.research.Loans
import kami.claims.service.Fail
import kami.claims.service.Service
import kami.claims.service.Words.money
import kami.claims.service.Words.v
import kami.claims.war.Wars
import kami.libs.economy.CountryParty
import kami.libs.economy.Flow
import kami.libs.economy.Statement
import kami.libs.text.Phrase
import kami.libs.util.Time
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import kotlin.math.max

object Council {
    private const val SMALL_COUNTRY = 3
    private const val SMALL_DAILY_SHARE = 5
    private const val APPROVAL_FLOOR = 1000L
    private const val TREASURY_DIVISOR = 10

    fun requireApproval(c: Country, amount: Long) =
        c.government.levers.treasury == TreasuryControl.COUNCIL && withdrawnToday(c) + amount > max(APPROVAL_FLOOR, c.treasury / TREASURY_DIVISOR)

    fun pending(c: Country): List<WithdrawalRequest> {
        expire(c)
        return c.withdrawals
    }

    fun gate(c: Country, actor: String, amount: Long): Phrase? {
        if (requireApproval(c, amount)) return request(c, actor, amount)
        ensureDaily(c, amount)
        return null
    }

    fun clear(c: Country) = c.withdrawals.clear()

    fun request(c: Country, actor: String, amount: Long): Phrase {
        if (!requireApproval(c, amount)) throw Fail("kami_claims.error.withdraw_no_approval")
        expire(c)
        c.withdrawals.removeIf { it.requester == actor }
        val id = UUID.randomUUID().toString().take(6)
        c.withdrawals += WithdrawalRequest(id, actor, amount, now())
        return Phrase.of("kami_claims.done.withdraw_requested", v(id), money(amount))
    }

    fun approve(p: ServerPlayer, id: String): Phrase {
        val c = Service.need(p, Perm.WITHDRAW)
        val approver = p.stringUUID
        if (c.government.levers.treasury != TreasuryControl.COUNCIL) throw Fail("kami_claims.error.budget_bound")
        expire(c)
        val request = c.withdrawals.firstOrNull { it.id == id } ?: throw Fail("kami_claims.error.withdraw_unknown")
        if (request.requester == approver && !soleWithdrawer(c, approver)) throw Fail("kami_claims.error.withdraw_self")
        if (!Permissions.holds(c, request.requester, Perm.WITHDRAW)) throw Fail("kami_claims.error.withdraw_unknown")
        pay(c, request)
        c.withdrawals.remove(request)
        Audit.record(c, approver, "withdraw_approve", request.requester, request.amount.toString())
        return Phrase.of("kami_claims.done.withdraw_approved", money(request.amount))
    }

    private fun soleWithdrawer(c: Country, id: String) =
        Permissions.isPresident(c, id) && c.members.keys.count { Permissions.holds(c, it, Perm.WITHDRAW) } == 1

    private fun pay(c: Country, request: WithdrawalRequest) {
        Loans.requireNoDebt(c, "kami_claims.loans.error.disband")
        if (Wars.involved(c) || c.warDebts.isNotEmpty()) throw Fail("kami_claims.error.withdraw_war")
        if (c.treasury < request.amount) throw Fail(Phrase.of("kami_claims.error.treasury_short", money(c.treasury)), "FUNDS")
        ensureDaily(c, request.amount)
        if (!Bank.give(UUID.fromString(request.requester), request.amount.toInt(), Flow.TREASURY, CountryParty(c.id).key, "kami_libs.note.withdraw")) throw Fail("kami_claims.error.payout")
        Treasury.move(c, LedgerKind.WITHDRAW, -request.amount, request.requester, Statement.note("kami_libs.note.withdraw"))
    }

    private fun withdrawnToday(c: Country) = c.withdrawn.total(now() / Config.s.dayMillis)

    private fun ensureDaily(c: Country, amount: Long) {
        val withdrawn = withdrawnToday(c)
        if (c.members.size < SMALL_COUNTRY && withdrawn + amount > (c.treasury + withdrawn) / SMALL_DAILY_SHARE) throw Fail("kami_claims.error.withdraw_cap")
    }

    private fun expire(c: Country) {
        val cutoff = now() - Time.DAY_MS
        c.withdrawals.removeIf { it.at < cutoff }
    }
}
