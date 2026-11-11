package kami.claims.research

import kami.claims.Config
import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.LendOffer
import kami.claims.LendState
import kami.claims.Loan
import kami.claims.Realm
import kami.claims.economy.Treasury
import kami.claims.gov.Perm
import kami.claims.net.ActiveLoanView
import kami.claims.net.LoanOfferView
import kami.claims.net.LoanTerms
import kami.claims.net.LoansView
import kami.claims.net.MarketLoanView
import kami.claims.net.OwnLoanView
import kami.claims.net.ResearchSync
import kami.claims.research.Limits.MAX_LINKS
import kami.claims.service.Diplomacy
import kami.claims.service.Fail
import kami.claims.service.Words
import kami.claims.social.Mail
import kami.claims.war.Reputation
import kami.claims.war.WarPhase
import kami.libs.chat.Tone
import kami.libs.claims.Locks
import kami.libs.economy.CountryParty
import kami.libs.stats.Stats
import kami.libs.text.Phrase
import java.util.UUID
import kotlin.math.max

class LoanOffer(val node: Node, val unlock: LoanUnlock) {
    val total get() = LoanTerms.total(unlock.amount, unlock.interestPct)
    val perDay get() = LoanTerms.perDay(total, unlock.termDays)
}

object Loans {
    private const val COOLDOWN_DAYS = 7
    private const val LATE_FEE_PCT = 10
    private const val WEEK = 7

    fun offers(): List<LoanOffer> =
        Research.defs.nodes.values.flatMap { node -> node.unlocks.filterIsInstance<LoanUnlock>().map { LoanOffer(node, it) } }.sortedBy { it.unlock.amount }

    fun unlocked(country: Country, offer: LoanOffer) = offer.node.key in country.research.done

    fun slots(country: Country): Int = Research.unlocks<LoanSlotsUnlock>(country).sumOf { it.add }

    fun inDefault(country: Country) = country.loans.any { it.overdue > 0 }

    fun remaining(loan: Loan) = loan.total - loan.paid

    fun dueNext(country: Country): Long = country.loans.sumOf { minOf(remaining(it), it.perDay + it.overdue) }

    fun cooldownDays(country: Country, id: String, day: Long): Int =
        country.loanCooldowns[id]?.let { (it + COOLDOWN_DAYS - day).coerceAtLeast(0).toInt() } ?: 0

    private fun lent(country: Country) = country.lendOffers.count { it.state == LendState.LENT }

    private fun committed(country: Country) = country.loans.size + lent(country)

    fun used(country: Country) = country.loans.size + country.lendOffers.count { it.state != LendState.DISABLED }

    fun blockDays(country: Country, day: Long) = (country.loanBlockDay - day).coerceAtLeast(0).toInt()

    fun requireNoLoans(country: Country, key: String) {
        if (country.loans.isNotEmpty() || lent(country) > 0) throw Fail(key)
    }

    fun requireNoDebt(country: Country, key: String) {
        if (country.loans.isNotEmpty()) throw Fail(key)
    }

    fun requireNoResearchLoans(country: Country, key: String) {
        if (country.loans.any { it.lender == null }) throw Fail(key)
    }

    private fun refreshAll() = Realm.data.countries.values.forEach(ResearchSync::refresh)

    private fun party(loan: Loan) = loan.lender?.let { "c:$it" } ?: "r:${loan.id}"

    private fun sync(country: Country, peers: Boolean) {
        Realm.changed(false)
        if (peers) refreshAll() else ResearchSync.refresh(country)
    }

    private fun repFarm(country: Country, loan: Loan) = loan.lender?.let(Realm::country)?.let { Diplomacy.sameFamily(it, country) } == true

    private fun takeLock(country: Country, day: Long) = when {
        inDefault(country) -> "kami_claims.loans.blocked.default"
        blockDays(country, day) > 0 -> "kami_claims.loans.blocked.block"
        committed(country) >= slots(country) -> "kami_claims.loans.blocked.slots"
        else -> ""
    }

    private fun lendLock(country: Country, limit: Boolean = true) = when {
        !Features.unlocked(country, Features.LOANS) -> "kami_claims.loans.blocked.locked"
        country.loans.isNotEmpty() -> "kami_claims.loans.error.lender_borrowing"
        used(country) >= slots(country) -> "kami_claims.loans.blocked.slots"
        limit && country.lendOffers.size >= MAX_LINKS -> "kami_claims.loans.error.offer_limit"
        else -> ""
    }

    private fun lock(key: String) { if (key.isNotEmpty()) throw Fail(key) }

    fun take(country: Country, id: String, actor: String?): Phrase {
        Features.require(country, Features.LOANS)
        if (country.parent != null) throw Fail("kami_claims.loans.error.province")
        val offer = offers().firstOrNull { it.unlock.id == id } ?: throw Fail("kami_claims.loans.error.unknown", Words.v(id))
        if (!unlocked(country, offer)) throw Fail(Locks.research(offer.node.label().asValue()))
        val day = Levels.day()
        lock(takeLock(country, day))
        if (country.loans.any { it.id == id }) throw Fail("kami_claims.loans.error.active")
        cooldownDays(country, id, day).takeIf { it > 0 }?.let { throw Fail("kami_claims.loans.error.cooldown", Words.days(it)) }
        if (offer.unlock.amount > Treasury.room(country)) throw Fail("kami_claims.error.treasury_full", Words.money(Treasury.room(country)))
        Treasury.bank(country, LedgerKind.LOAN_IN, offer.unlock.amount, actor, id)
        country.loans += Loan(id, offer.unlock.amount, offer.total, 0, offer.perDay, day)
        Stats.event("loan.take", "c:${country.id}", "r:$id", offer.unlock.amount)
        disableOffers(country)
        sync(country, true)
        return Phrase.of("kami_claims.loans.done.taken", Words.money(offer.unlock.amount), Words.money(offer.perDay))
    }

    fun repay(country: Country, id: String, actor: String?): Phrase {
        val loan = country.loans.firstOrNull { it.id == id } ?: throw Fail("kami_claims.loans.error.none")
        if (orphaned(country, loan)) {
            sync(country, false)
            throw Fail("kami_claims.loans.error.none")
        }
        val owed = remaining(loan)
        if (country.treasury < owed) throw Fail("kami_claims.loans.error.repay_short", Words.money(owed), Words.money(country.treasury))
        settle(country, loan, owed, actor)
        val day = Levels.day()
        loan.paid = loan.total
        close(country, loan, day, earned(loan, day))
        sync(country, loan.lender != null)
        return Phrase.of("kami_claims.loans.done.repaid", Words.money(owed))
    }

    fun collect(country: Country, day: Long) {
        if (country.loans.isEmpty()) return
        val peers = country.loans.any { it.lender != null }
        country.loans.toList().filter { day > it.takenDay }.forEach { loan ->
            if (orphaned(country, loan)) return@forEach
            val due = minOf(remaining(loan), loan.perDay + loan.overdue)
            val pay = minOf(due, country.treasury.coerceAtLeast(0))
            if (pay > 0) settle(country, loan, pay, null)
            loan.paid += pay
            val missing = due - pay
            if (missing > 0) {
                loan.missed++
                Stats.add("loan.miss", "c:${country.id}", party(loan), missing)
            }
            if (missing <= 0) {
                if (loan.overdue <= 0 && !repFarm(country, loan)) Reputation.instalment(country)
                loan.overdueDay = 0
            } else if (loan.overdueDay == 0L || day - loan.overdueDay >= WEEK) {
                loan.overdueDay = day
                Reputation.overdueLoan(country)
            }
            val fee = (minOf(missing, loan.perDay) * LATE_FEE_PCT + 99) / 100
            loan.total += fee
            loan.overdue = if (missing > 0) missing + fee else 0
            when {
                remaining(loan) <= 0 -> close(country, loan, day, earned(loan, day))
                missing > 0 -> {
                    Mail.holders(country, Perm.WITHDRAW, Phrase.of("kami_claims.mail.loan_missed", Words.v(loan.id), Words.money(loan.overdue)), Tone.BAD)
                    loan.lender?.let(Realm::country)?.let { Mail.holders(it, Perm.WITHDRAW, Phrase.of("kami_claims.mail.loan_lender_missed", Words.v(country.name), Words.money(missing)), Tone.BAD) }
                }
            }
        }
        sync(country, peers)
    }

    private fun orphaned(country: Country, loan: Loan): Boolean {
        if (loan.lender == null || Realm.country(loan.lender) != null) return false
        country.loans.remove(loan)
        Mail.holders(country, Perm.WITHDRAW, Phrase.of("kami_claims.mail.loan_forgiven", Words.v(loan.id)), Tone.INFO)
        return true
    }

    private fun settle(country: Country, loan: Loan, pay: Long, actor: String?) {
        val lender = loan.lender?.let(Realm::country)
        if (lender == null) Treasury.bank(country, LedgerKind.LOAN_PAYMENT, -pay, actor, loan.id)
        else {
            Treasury.move(country, LedgerKind.LOAN_PAYMENT, -pay, actor, loan.id, counterparty = CountryParty(lender.id))
            Treasury.move(lender, LedgerKind.LOAN_RETURN, pay, note = loan.id, capped = false, counterparty = CountryParty(country.id))
            Stats.add("loan.received", "c:${lender.id}", n = pay)
        }
        Stats.add("loan.paid", "c:${country.id}", party(loan), pay)
    }

    private fun earned(loan: Loan, day: Long) = (day - loan.takenDay) * 2 * loan.perDay >= loan.total

    private fun close(country: Country, loan: Loan, day: Long, earned: Boolean) {
        country.loans.remove(loan)
        country.loanBlockDay = max(country.loanBlockDay, day + loan.missed)
        if (earned && !repFarm(country, loan)) Reputation.payoff(country)
        Stats.event("loan.close", "c:${country.id}", party(loan), loan.missed.toLong())
        Mail.holders(country, Perm.WITHDRAW, Phrase.of("kami_claims.mail.loan_repaid", Words.v(loan.id)), Tone.OK)
        val lender = loan.lender?.let(Realm::country)
        if (loan.lender == null) country.loanCooldowns[loan.id] = day
        else if (lender != null) {
            Stats.add("loan.interest", "c:${lender.id}", n = loan.total - loan.principal)
            Mail.holders(lender, Perm.WITHDRAW, Phrase.of("kami_claims.mail.loan_returned", Words.v(country.name), Words.v(loan.id)), Tone.OK)
            lender.lendOffers.firstOrNull { it.id == loan.id }?.let { relist(lender, it) }
        }
    }

    private fun relist(lender: Country, offer: LendOffer) {
        offer.state = LendState.DISABLED
        offer.borrower = null
        if (!offer.relist) return
        try {
            activate(lender, offer, null)
        } catch (e: Fail) {
            Mail.holders(lender, Perm.WITHDRAW, Phrase.of("kami_claims.mail.loan_relist_failed", Words.v(offer.id), Words.money(offer.amount)), Tone.BAD)
        }
    }

    private fun activate(lender: Country, offer: LendOffer, actor: String?) {
        lock(lendLock(lender, false))
        if (lender.treasury < offer.amount) throw Fail("kami_claims.error.treasury_short", Words.money(lender.treasury))
        Treasury.move(lender, LedgerKind.LOAN_RESERVE, -offer.amount, actor, offer.id, counterparty = null)
        offer.state = LendState.ACTIVE
    }

    private fun release(lender: Country, offer: LendOffer) {
        Treasury.move(lender, LedgerKind.LOAN_RELEASE, offer.amount, note = offer.id, capped = false, counterparty = null)
        offer.state = LendState.DISABLED
    }

    private fun disableOffers(country: Country) {
        val active = country.lendOffers.filter { it.state == LendState.ACTIVE }
        if (active.isEmpty()) return
        active.forEach { release(country, it) }
        Stats.add("loan.offer.off", "c:${country.id}", n = active.size.toLong())
    }

    private fun own(country: Country, id: String) = country.lendOffers.firstOrNull { it.id == id } ?: throw Fail("kami_claims.loans.error.offer_unknown")

    private fun repOk(rep: Int) = rep == -1 || rep in 0..Config.s.war.repMax.toInt()

    fun createOffer(country: Country, amount: Long, termDays: Int, interestPct: Int, tariff: Boolean, minRep: Int, maxRep: Int, relist: Boolean, actor: String?): Phrase {
        Features.require(country, Features.LOANS)
        if (amount < LoanTerms.MIN_AMOUNT || termDays !in 1..LoanTerms.MAX_TERM || interestPct !in 0..LoanTerms.MAX_INTEREST || !repOk(minRep) || !repOk(maxRep) || minRep >= 0 && maxRep >= 0 && minRep > maxRep)
            throw Fail("kami_claims.loans.error.offer_invalid", Words.money(LoanTerms.MIN_AMOUNT), Words.num(LoanTerms.MAX_TERM), Words.num(LoanTerms.MAX_INTEREST))
        if (country.lendOffers.size >= MAX_LINKS) throw Fail("kami_claims.loans.error.offer_limit")
        val taken = Realm.data.countries.values.flatMap { c -> c.lendOffers.map { it.id } }.toSet()
        val id = generateSequence { "c" + UUID.randomUUID().toString().take(8) }.first { it !in taken }
        val offer = LendOffer(id, amount, termDays, interestPct, tariff, minRep, maxRep, relist)
        activate(country, offer, actor)
        country.lendOffers += offer
        Stats.event("loan.offer.create", "c:${country.id}", id, amount)
        sync(country, true)
        return Phrase.of("kami_claims.loans.done.offer_created", Words.money(amount))
    }

    fun toggleOffer(country: Country, id: String, on: Boolean, actor: String?): Phrase {
        val offer = own(country, id)
        if (offer.state == LendState.LENT) throw Fail("kami_claims.loans.error.offer_lent")
        val done = Phrase.of(if (on) "kami_claims.loans.done.offer_on" else "kami_claims.loans.done.offer_off")
        if (on == (offer.state == LendState.ACTIVE)) return done
        if (on) {
            Features.require(country, Features.LOANS)
            activate(country, offer, actor)
        } else release(country, offer)
        Stats.add(if (on) "loan.offer.on" else "loan.offer.off", "c:${country.id}")
        sync(country, true)
        return done
    }

    fun deleteOffer(country: Country, id: String): Phrase {
        val offer = own(country, id)
        var done = Phrase.of("kami_claims.loans.done.offer_deleted")
        when (offer.state) {
            LendState.ACTIVE -> release(country, offer)
            LendState.LENT -> {
                val borrower = Realm.country(offer.borrower)
                val loan = borrower?.loans?.firstOrNull { it.id == offer.id }
                if (borrower != null && loan != null) {
                    borrower.loans.remove(loan)
                    Stats.event("loan.forgive", "c:${country.id}", "c:${borrower.id}", remaining(loan))
                    Mail.holders(borrower, Perm.WITHDRAW, Phrase.of("kami_claims.mail.loan_forgiven_by", Words.v(country.name), Words.v(loan.id)), Tone.INFO)
                    done = Phrase.of("kami_claims.loans.done.forgiven", Words.money(remaining(loan)))
                }
            }
            else -> Unit
        }
        country.lendOffers.remove(offer)
        Stats.add("loan.offer.delete", "c:${country.id}")
        sync(country, true)
        return done
    }

    fun borrow(country: Country, id: String, expectedTotal: Long, actor: String?): Phrase {
        Features.require(country, Features.LOANS)
        if (country.parent != null) throw Fail("kami_claims.loans.error.province")
        val (lender, offer) = Realm.data.countries.values.firstNotNullOfOrNull { c -> c.lendOffers.firstOrNull { it.id == id }?.let { c to it } }
            ?: throw Fail("kami_claims.loans.error.offer_unknown")
        if (lender === country) throw Fail("kami_claims.loans.error.own_offer")
        if (offer.state != LendState.ACTIVE) throw Fail("kami_claims.loans.error.offer_unknown")
        val day = Levels.day()
        lock(blocked(country, lender, offer, day))
        val total = LoanTerms.total(offer.amount, effective(offer, lender, country))
        if (total != expectedTotal) throw Fail("kami_claims.loans.error.terms_changed")
        val perDay = LoanTerms.perDay(total, offer.termDays)
        Treasury.move(country, LedgerKind.LOAN_IN, offer.amount, actor, id, counterparty = CountryParty(lender.id))
        offer.state = LendState.LENT
        offer.borrower = country.id
        country.loans += Loan(id, offer.amount, total, 0, perDay, day, lender = lender.id)
        Stats.event("loan.take", "c:${country.id}", "c:${lender.id}", offer.amount)
        Stats.add("loan.lend", "c:${lender.id}", "c:${country.id}", offer.amount)
        Mail.holders(lender, Perm.WITHDRAW, Phrase.of("kami_claims.mail.loan_borrowed", Words.v(country.name), Words.money(offer.amount)), Tone.OK)
        disableOffers(country)
        sync(country, true)
        return Phrase.of("kami_claims.loans.done.taken", Words.money(offer.amount), Words.money(perDay))
    }

    private fun effective(offer: LendOffer, lender: Country, borrower: Country) =
        if (offer.tariff) offer.interestPct * (100 + Diplomacy.tariff(lender, borrower)) / 100 else offer.interestPct

    private fun atWar(a: Country, b: Country) =
        Realm.data.wars.any { w -> w.phase == WarPhase.WAR && w.side(a.id)?.let { b.id in w.opponent(it).countries } == true }

    private fun blocked(borrower: Country, lender: Country, offer: LendOffer, day: Long) = when {
        Diplomacy.embargoed(borrower, lender) -> "kami_claims.loans.blocked.embargo"
        atWar(borrower, lender) -> "kami_claims.loans.blocked.war"
        offer.minRep >= 0 && borrower.reputation < offer.minRep -> "kami_claims.loans.blocked.rep_min"
        offer.maxRep >= 0 && borrower.reputation > offer.maxRep -> "kami_claims.loans.blocked.rep_max"
        else -> takeLock(borrower, day).ifEmpty { if (offer.amount > Treasury.room(borrower)) "kami_claims.loans.blocked.room" else "" }
    }

    fun dissolve(country: Country) {
        country.lendOffers.filter { it.state == LendState.ACTIVE }.forEach { release(country, it) }
        Realm.data.countries.values.filter { it !== country }.forEach { other ->
            other.loans.filter { it.lender == country.id }.forEach {
                other.loans.remove(it)
                Mail.holders(other, Perm.WITHDRAW, Phrase.of("kami_claims.mail.loan_forgiven", Words.v(it.id)), Tone.INFO)
            }
            other.lendOffers.filter { it.borrower == country.id }.forEach {
                it.state = LendState.DISABLED
                it.borrower = null
                Mail.holders(other, Perm.WITHDRAW, Phrase.of("kami_claims.mail.loan_defunct", Words.v(country.name)), Tone.BAD)
            }
        }
        country.lendOffers.clear()
        country.loans.clear()
        sync(country, true)
    }

    private fun activeView(loan: Loan, party: String) =
        ActiveLoanView(loan.id, loan.principal, loan.total, loan.paid, loan.perDay, ((remaining(loan) + loan.perDay - 1) / loan.perDay).toInt(), loan.overdue, party, loan.missed)

    fun view(country: Country): LoansView {
        val day = Levels.day()
        return LoansView(
            Features.unlocked(country, Features.LOANS), slots(country), used(country), blockDays(country, day),
            country.loans.map { activeView(it, it.lender?.let(Realm::country)?.name.orEmpty()) },
            country.lendOffers.filter { it.state == LendState.LENT }.mapNotNull { o ->
                Realm.country(o.borrower)?.let { b -> b.loans.firstOrNull { it.id == o.id }?.let { activeView(it, b.name) } }
            },
            offers().map { offer ->
                LoanOfferView(
                    offer.unlock.id, offer.unlock.amount, offer.unlock.interestPct, offer.unlock.termDays, offer.total, offer.perDay,
                    unlocked(country, offer), offer.node.level, cooldownDays(country, offer.unlock.id, day)
                )
            },
            Realm.data.countries.values.filter { it.active && it !== country }
                .flatMap { l -> l.lendOffers.filter { it.state == LendState.ACTIVE }.map { o ->
                    val pct = effective(o, l, country)
                    val total = LoanTerms.total(o.amount, pct)
                    MarketLoanView(o.id, l.name, o.amount, o.termDays, o.interestPct, pct, total, LoanTerms.perDay(total, o.termDays), o.minRep, o.maxRep, blocked(country, l, o, day))
                } }
                .sortedWith(compareBy({ it.blocked.isNotEmpty() }, { it.amount })).take(MAX_LINKS),
            country.lendOffers.take(MAX_LINKS).map { OwnLoanView(it.id, it.amount, it.termDays, it.interestPct, it.tariff, it.minRep, it.maxRep, it.relist, it.state.ordinal, it.borrower?.let(Realm::country)?.name.orEmpty()) },
            inDefault(country), Config.s.war.repMax.toInt(), takeLock(country, day), lendLock(country, false)
        )
    }
}
