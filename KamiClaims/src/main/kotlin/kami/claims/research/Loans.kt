package kami.claims.research

import kami.claims.Country
import kami.claims.Loan
import kami.claims.LedgerKind
import kami.claims.Realm
import kami.claims.economy.Treasury
import kami.claims.net.ActiveLoanView
import kami.claims.net.LoanOfferView
import kami.claims.net.LoansView
import kami.claims.net.ResearchSync
import kami.claims.service.Fail
import kami.claims.service.Words
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.claims.Locks
import kami.libs.text.Phrase

class LoanOffer(val node: Node, val unlock: LoanUnlock) {
    val total get() = unlock.amount * (100 + unlock.interestPct) / 100
    val perDay get() = (total + unlock.termDays - 1) / unlock.termDays
}

object Loans {
    const val COOLDOWN_DAYS = 7
    private const val LATE_FEE_PCT = 10

    fun offers(): List<LoanOffer> =
        Research.defs.nodes.values.flatMap { node -> node.unlocks.filterIsInstance<LoanUnlock>().map { LoanOffer(node, it) } }.sortedBy { it.unlock.amount }

    fun unlocked(country: Country, offer: LoanOffer) = offer.node.key in country.research.done

    fun slots(country: Country): Int = country.research.done.keys.sumOf { key ->
        Research.defs.node(key)?.unlocks.orEmpty().filterIsInstance<LoanSlotsUnlock>().sumOf { it.add }
    }

    fun inDefault(country: Country) = country.loans.any { it.overdue > 0 }

    fun remaining(loan: Loan) = loan.total - loan.paid

    fun dueNext(country: Country): Long = country.loans.sumOf { minOf(remaining(it), it.perDay + it.overdue) }

    fun cooldownDays(country: Country, id: String, day: Long): Int =
        country.loanCooldowns[id]?.let { (it + COOLDOWN_DAYS - day).coerceAtLeast(0).toInt() } ?: 0

    fun requireNoLoans(country: Country) {
        if (country.loans.isNotEmpty()) throw Fail("kami_claims.loans.error.withdraw")
    }

    fun take(country: Country, id: String, actor: String?): Phrase {
        Features.require(country, Features.LOANS)
        val offer = offers().firstOrNull { it.unlock.id == id } ?: throw Fail("kami_claims.loans.error.unknown", Words.v(id))
        if (!unlocked(country, offer)) throw Fail(Locks.research(offer.node.label().asValue()))
        val day = Levels.day()
        if (inDefault(country)) throw Fail("kami_claims.loans.error.default")
        if (country.loans.size >= slots(country)) throw Fail("kami_claims.loans.error.slots", Words.num(slots(country)))
        if (country.loans.any { it.id == id }) throw Fail("kami_claims.loans.error.active")
        cooldownDays(country, id, day).takeIf { it > 0 }?.let { throw Fail("kami_claims.loans.error.cooldown", Words.days(it)) }
        if (offer.unlock.amount > Treasury.room(country)) throw Fail("kami_claims.error.treasury_full", Words.money(Treasury.room(country)))
        Treasury.move(country, LedgerKind.LOAN_IN, offer.unlock.amount, actor, id)
        country.loans += Loan(id, offer.unlock.amount, offer.total, 0, offer.perDay, day)
        Realm.changed()
        ResearchSync.refresh(country)
        return Phrase.of("kami_claims.loans.done.taken", Words.money(offer.unlock.amount), Words.money(offer.perDay))
    }

    fun repay(country: Country, id: String, actor: String?): Phrase {
        val loan = country.loans.firstOrNull { it.id == id } ?: throw Fail("kami_claims.loans.error.none")
        val owed = remaining(loan)
        if (country.treasury < owed) throw Fail("kami_claims.loans.error.repay_short", Words.money(owed), Words.money(country.treasury))
        Treasury.move(country, LedgerKind.LOAN_PAYMENT, -owed, actor, id)
        loan.paid = loan.total
        close(country, loan, Levels.day())
        return Phrase.of("kami_claims.loans.done.repaid", Words.money(owed))
    }

    fun collect(country: Country, day: Long) {
        if (country.loans.isEmpty()) return
        country.loans.toList().filter { day > it.takenDay }.forEach { loan ->
            val due = minOf(remaining(loan), loan.perDay + loan.overdue)
            val pay = minOf(due, country.treasury.coerceAtLeast(0))
            if (pay > 0) Treasury.move(country, LedgerKind.LOAN_PAYMENT, -pay, note = loan.id)
            loan.paid += pay
            val missing = due - pay
            val fee = (missing * LATE_FEE_PCT + 99) / 100
            loan.total += fee
            loan.overdue = if (missing > 0) missing + fee else 0
            when {
                remaining(loan) <= 0 -> close(country, loan, day)
                missing > 0 -> Mail.officers(country, Phrase.of("kami_claims.mail.loan_missed", Words.v(loan.id), Words.money(loan.overdue)), Tone.BAD)
            }
        }
        Realm.changed()
        ResearchSync.refresh(country)
    }

    private fun close(country: Country, loan: Loan, day: Long) {
        country.loans.remove(loan)
        country.loanCooldowns[loan.id] = day
        Mail.officers(country, Phrase.of("kami_claims.mail.loan_repaid", Words.v(loan.id)), Tone.OK)
        Realm.changed()
        ResearchSync.refresh(country)
    }

    fun view(country: Country): LoansView {
        val day = Levels.day()
        return LoansView(
            Features.unlocked(country, Features.LOANS), slots(country),
            country.loans.map { ActiveLoanView(it.id, it.principal, it.total, it.paid, it.perDay, ((remaining(it) + it.perDay - 1) / it.perDay).toInt(), it.overdue) },
            offers().map { offer ->
                LoanOfferView(
                    offer.unlock.id, offer.unlock.amount, offer.unlock.interestPct, offer.unlock.termDays, offer.total, offer.perDay,
                    unlocked(country, offer), offer.node.level, cooldownDays(country, offer.unlock.id, day)
                )
            },
            inDefault(country)
        )
    }
}
